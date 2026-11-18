package com.kewu.admin

import oshi.SystemInfo
import oshi.hardware.CentralProcessor
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * 服务器本机实时状态。总控端应运行在服务器上；进程检测不到时仅隐藏本机后端进程指标。
 *
 * 2026-11-17（§81）改用 OSHI（GitHub oshi/oshi，M.I.T.）采集系统/硬件信息。
 * 2026-11-17（§83）两处关键修复：
 *  1) 装机版 jpackage 精简 JRE 缺 `java.management` 模块，`ManagementFactory` 直接
 *     NoClassDefFoundError，导致整页「全部取不到」。已在 build.gradle.kts 显式声明模块。
 *  2) 采集改为**逐项容错**：任一指标失败只让该项为默认值并记录原因，
 *     绝不让一次异常清空整页（此前一个异常就把主机名/CPU/内存全变成 —）。
 */
data class ServerRuntimeSnapshot(
    val hostname: String,
    val osName: String,
    val osVersion: String,
    val cpuModel: String,
    val cpuCores: Int,
    val cpuLoad: Double?,
    val adminProcessCpuLoad: Double?,
    val totalRamBytes: Long,
    val freeRamBytes: Long,
    val diskTotalBytes: Long,
    val diskFreeBytes: Long,
    val adminHeapUsedBytes: Long,
    val adminHeapMaxBytes: Long,
    val backendPid: Long?,
    val backendStartedAtEpochMs: Long?,
    val backendUptimeSeconds: Long?,
    val backendWorkingSetBytes: Long?,
    val backendCpuSeconds: Double?,
    val osUptimeSeconds: Long?,
    val updatedAtEpochMs: Long,
    val cpuPhysicalCores: Int,
    val cpuLoad1Min: Double?,
    val processCount: Int?,
    val threadCount: Int?,
    val swapTotalBytes: Long,
    val swapUsedBytes: Long,
    val networkRxBytesPerSec: Long?,
    val networkTxBytesPerSec: Long?,
    val diskReadBytesPerSec: Long?,
    val diskWriteBytesPerSec: Long?,
    val monitorSource: String,          // oshi / jvm-fallback
    val degradeReason: String? = null,  // 采集降级原因（部分指标不可用时提示，不阻塞其它指标）
)

object ServerMonitor {
    // OSHI 构造较重，懒加载且允许失败（失败则走 JVM 兜底，绝不抛给界面）。
    private val oshi: SystemInfo? by lazy { runCatching { SystemInfo() }.getOrNull() }
    private val cpu: CentralProcessor? by lazy { oshi?.let { runCatching { it.hardware.processor }.getOrNull() } }
    private val os: oshi.software.os.OperatingSystem? by lazy { oshi?.let { runCatching { it.operatingSystem }.getOrNull() } }

    private var prevTicks: LongArray? = null
    private var prevNet: Pair<Long, Long>? = null
    private var prevDisk: Pair<Long, Long>? = null
    private var prevSampleAt: Long = 0L
    private var lastCpuLoad: Double? = null
    private var lastRxPerSec: Long? = null
    private var lastTxPerSec: Long? = null
    private var lastDiskReadPerSec: Long? = null
    private var lastDiskWritePerSec: Long? = null

    // ★ 修复（2026-11-19）：snapshot 会被定时刷新与预加载并发调用，而 prev* / last* 差值是共享可变状态，
    //   并发读写会算出错误的每秒增量（甚至拿到半更新的 prevNet）。加锁串行化，保证差值计算一致。
    @Synchronized
    fun snapshot(): ServerRuntimeSnapshot {
        val notes = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val elapsedSec = ((now - prevSampleAt).coerceAtLeast(1L)) / 1000.0
        val freshSample = prevSampleAt == 0L || elapsedSec >= 1.0

        // ---------- CPU 负载（OSHI tick 差值；失败则退回 MXBean） ----------
        if (freshSample) {
            val ticks = runCatching { cpu?.systemCpuLoadTicks }.getOrNull()
            if (ticks != null) {
                val prev = prevTicks
                if (prev != null && prev.size == ticks.size) {
                    lastCpuLoad = runCatching { cpu?.getSystemCpuLoadBetweenTicks(prev) }.getOrNull()
                        ?.takeIf { it >= 0.0 }
                }
                prevTicks = ticks
            } else {
                lastCpuLoad = mxBeanCpuLoad()
            }
        }

        // ---------- 网络 / 磁盘吞吐 ----------
        if (freshSample) {
            runCatching {
                val net = oshi?.hardware?.networkIFs
                    ?.filter { it.name.isNotBlank() && it.bytesRecv > 0 }
                    ?.fold(0L to 0L) { acc, n -> acc.first + n.bytesRecv to acc.second + n.bytesSent }
                if (net != null) {
                    val p = prevNet
                    if (p != null && net.first >= p.first && net.second >= p.second) {
                        lastRxPerSec = ((net.first - p.first) / elapsedSec).roundToLong()
                        lastTxPerSec = ((net.second - p.second) / elapsedSec).roundToLong()
                    }
                    prevNet = net
                }
            }.onFailure { notes += "网络吞吐不可用" }

            runCatching {
                val io = oshi?.hardware?.diskStores
                    ?.fold(0L to 0L) { acc, d -> acc.first + d.readBytes to acc.second + d.writeBytes }
                if (io != null) {
                    val p = prevDisk
                    if (p != null && io.first >= p.first && io.second >= p.second) {
                        lastDiskReadPerSec = ((io.first - p.first) / elapsedSec).roundToLong()
                        lastDiskWritePerSec = ((io.second - p.second) / elapsedSec).roundToLong()
                    }
                    prevDisk = io
                }
            }.onFailure { notes += "磁盘吞吐不可用" }
            prevSampleAt = now
        }

        // ---------- 内存（OSHI 优先，JVM MXBean 兜底） ----------
        val mem = runCatching { oshi?.hardware?.memory }.getOrNull()
        val totalRam = runCatching { mem?.total ?: 0L }.getOrDefault(0L).takeIf { it > 0 }
            ?: jvmTotalRam()
        val freeRam = runCatching { mem?.available ?: 0L }.getOrDefault(0L).takeIf { it > 0 && totalRam > 0 }
            ?: jvmFreeRam()
        val swapTotal = runCatching { mem?.virtualMemory?.swapTotal ?: 0L }.getOrDefault(0L)
        val swapUsed = runCatching { mem?.virtualMemory?.swapUsed ?: 0L }.getOrDefault(0L)
        if (totalRam <= 0) notes += "物理内存读取失败"

        // ---------- 自身堆 ----------
        val heap = runCatching {
            java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage
        }.getOrNull()
        if (heap == null) notes += "java.management 模块缺失（JVM 堆不可用）"

        // ---------- 后端进程 ----------
        val backend = runCatching { findBackendProcess() }.getOrNull()
        var backendMemBytes: Long? = null
        var backendCpuSec: Double? = null
        if (backend != null) {
            val proc = runCatching { os?.getProcess(backend.pid().toInt()) }.getOrNull()
            backendMemBytes = runCatching { proc?.residentMemory }.getOrNull()?.takeIf { it > 0 }
            backendCpuSec = runCatching {
                proc?.let { (it.userTime + it.kernelTime).toDouble() / 1_000_000_000.0 }
            }.getOrNull()?.takeIf { it > 0 }
        }

        // ---------- 磁盘 ----------
        val driveRoot = runCatching { currentDriveRoot() }.getOrDefault("/")
        val drive = File(driveRoot)
        val diskTotal = runCatching { drive.totalSpace }.getOrDefault(0L)
        val diskFree = runCatching { drive.freeSpace }.getOrDefault(0L)

        // ---------- 其余 ----------
        val cores = runCatching { cpu?.logicalProcessorCount }.getOrNull()
            ?: Runtime.getRuntime().availableProcessors()
        val physicalCores = runCatching { cpu?.physicalProcessorCount }.getOrNull() ?: cores
        val cpuModel = runCatching { cpu?.processorIdentifier?.name?.trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: System.getenv("PROCESSOR_IDENTIFIER")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("os.arch", "—")
        if (cpuModel == "—") notes += "CPU 型号不可用"

        val osName = runCatching { os?.family }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("os.name", "Unknown OS")
        val osVersion = runCatching { os?.versionInfo?.version }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("os.version", "")
        val osUptime = runCatching { os?.systemUptime }.getOrNull()?.takeIf { it > 0 }
            ?: runCatching { osLongUptimeFallback() }.getOrNull()
        val loadAvg = runCatching { cpu?.getSystemLoadAverage(1)?.firstOrNull() }.getOrNull()
            ?.takeIf { it >= 0.0 }

        val source = if (oshi != null && cpu != null) "oshi" else "jvm-fallback"
        if (source == "jvm-fallback") notes += "OSHI 不可用，已降级为 JVM 指标"

        return ServerRuntimeSnapshot(
            hostname = runCatching {
                (System.getenv("COMPUTERNAME")
                    ?: java.net.InetAddress.getLocalHost().hostName).ifBlank { "server" }
            }.getOrDefault("server"),
            osName = osName,
            osVersion = osVersion,
            cpuModel = cpuModel,
            cpuCores = cores,
            cpuLoad = lastCpuLoad,
            adminProcessCpuLoad = mxBeanCpuLoad(),
            totalRamBytes = totalRam,
            freeRamBytes = freeRam,
            diskTotalBytes = diskTotal,
            diskFreeBytes = diskFree,
            adminHeapUsedBytes = heap?.used ?: 0L,
            adminHeapMaxBytes = heap?.max ?: 0L,
            backendPid = backend?.pid(),
            backendStartedAtEpochMs = runCatching {
                backend?.info()?.startInstant()?.orElse(null)?.toEpochMilli()
            }.getOrNull(),
            backendUptimeSeconds = runCatching {
                backend?.info()?.startInstant()?.orElse(null)?.let { (now - it.toEpochMilli()).coerceAtLeast(0L) / 1000L }
            }.getOrNull(),
            backendWorkingSetBytes = backendMemBytes,
            backendCpuSeconds = backendCpuSec,
            osUptimeSeconds = osUptime,
            updatedAtEpochMs = now,
            cpuPhysicalCores = physicalCores,
            cpuLoad1Min = loadAvg,
            processCount = runCatching { os?.processCount }.getOrNull()
                ?: runCatching { ProcessHandle.allProcesses().count().toInt() }.getOrNull(),
            threadCount = runCatching { os?.threadCount }.getOrNull()
                ?: runCatching { java.lang.management.ManagementFactory.getThreadMXBean().threadCount }.getOrNull(),
            swapTotalBytes = swapTotal,
            swapUsedBytes = swapUsed,
            networkRxBytesPerSec = lastRxPerSec,
            networkTxBytesPerSec = lastTxPerSec,
            diskReadBytesPerSec = lastDiskReadPerSec,
            diskWriteBytesPerSec = lastDiskWritePerSec,
            monitorSource = source,
            degradeReason = notes.distinct().takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
    }

    /** 进程自身 CPU 占用（MXBean；模块缺失时返回 null，不影响其它指标）。 */
    private fun mxBeanCpuLoad(): Double? = runCatching {
        val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        if (bean is com.sun.management.OperatingSystemMXBean) {
            bean.processCpuLoad.takeIf { it >= 0.0 }
        } else null
    }.getOrNull()

    private fun jvmTotalRam(): Long = runCatching {
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize ?: 0L
    }.getOrDefault(0L)

    private fun jvmFreeRam(): Long = runCatching {
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.freeMemorySize ?: 0L
    }.getOrDefault(0L)

    /** 无 OSHI 时的系统运行时长兜底：Windows 用开机时间推算。 */
    private fun osLongUptimeFallback(): Long? = runCatching {
        if (System.getProperty("os.name", "").startsWith("Windows", true)) {
            val out = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToFileTimeUtc()",
            ).redirectErrorStream(true).start()
            // ★ 修复（2026-11-19）：waitFor() 无超时——powershell 被系统卡住时会永久阻塞采集线程池。
            //   3 秒超时后强杀进程，避免总控端刷新线程被挂死。
            val finished = out.waitFor(3, TimeUnit.SECONDS)
            if (!finished) { out.destroyForcibly(); return@runCatching null }
            val text = out.inputStream.bufferedReader().use { it.readText() }.trim()
            val fileTime = text.lines().firstOrNull()?.trim()?.toLongOrNull() ?: return@runCatching null
            // FILETIME(100ns since 1601) -> epoch ms（1601→1970 偏移 = 11,644,473,600,000 ms）
            val bootMs = fileTime / 10_000L - 11_644_473_600_000L
            (System.currentTimeMillis() - bootMs).coerceAtLeast(0L) / 1000L
        } else null
    }.getOrNull()

    private fun findBackendProcess(): ProcessHandle? =
        ProcessHandle.allProcesses().iterator().asSequence()
            .filter { it.pid() != ProcessHandle.current().pid() }
            .firstOrNull { process ->
                val command = runCatching { process.info().commandLine().orElse("") }.getOrDefault("") ?: ""
                command.contains("kewu-8080.jar", true) && command.contains("-jar", true)
            }

    private fun currentDriveRoot(): String {
        val cwd = File(".").absolutePath.replace('\\', '/')
        val drive = cwd.take(3).trimEnd('/')
        return if (drive.length == 2 && drive[1] == ':') "$drive/" else "/"
    }
}
