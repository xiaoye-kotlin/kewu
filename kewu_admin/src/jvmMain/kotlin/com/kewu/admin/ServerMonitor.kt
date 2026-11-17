package com.kewu.admin

import kotlinx.serialization.json.JsonObject
import oshi.SystemInfo
import oshi.hardware.CentralProcessor
import java.io.File
import kotlin.math.roundToLong

/**
 * 服务器本机实时状态。总控端应运行在服务器上；进程检测不到时仅隐藏本机后端进程指标。
 *
 * 2026-11-17（§81）改用 OSHI（GitHub oshi/oshi，M.I.T.）采集系统/硬件信息：
 * 取代原来慢且易失败的 PowerShell（CIM/Get-Process）方案——CPU 型号、系统运行时长、
 * 后端进程 CPU/内存以前常取不到，现在由 OSHI(JNA) 直接读系统 API，稳定且无需外部命令。
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
    // ---- §81 新增：OSHI 提供的更丰富指标 ----
    val cpuPhysicalCores: Int,          // 物理核数
    val cpuLoad1Min: Double?,           // 1 分钟系统负载
    val processCount: Int?,             // 系统进程数
    val threadCount: Int?,              // 系统线程数
    val swapTotalBytes: Long,           // 交换分区总量
    val swapUsedBytes: Long,            // 交换分区已用
    val networkRxBytesPerSec: Long?,    // 网卡下行 B/s（本次采样）
    val networkTxBytesPerSec: Long?,    // 网卡上行 B/s
    val diskReadBytesPerSec: Long?,     // 磁盘读 B/s
    val diskWriteBytesPerSec: Long?,    // 磁盘写 B/s
    val monitorSource: String,          // 采集来源：oshi / fallback
)

object ServerMonitor {
    // OSHI 系统信息对象构造较重，单例复用；JNA 内部线程安全。
    private val systemInfo: SystemInfo by lazy { runCatching { SystemInfo() }.getOrElse { throw it } }
    private val cpu: CentralProcessor by lazy { systemInfo.hardware.processor }
    private val os by lazy { systemInfo.operatingSystem }

    private var prevTicks: LongArray? = null
    private var prevNet: Pair<Long, Long>? = null
    private var prevDisk: Pair<Long, Long>? = null
    private var prevSampleAt: Long = 0L
    private var lastCpuLoad: Double? = null
    private var lastRxPerSec: Long? = null
    private var lastTxPerSec: Long? = null
    private var lastDiskReadPerSec: Long? = null
    private var lastDiskWritePerSec: Long? = null

    fun snapshot(): ServerRuntimeSnapshot {
        val now = System.currentTimeMillis()
        val elapsedSec = ((now - prevSampleAt).coerceAtLeast(1L)) / 1000.0
        val freshSample = prevSampleAt == 0L || elapsedSec >= 1.0

        // --- CPU 负载：用 OSHI tick 计数器算差值百分比（跨采样间隔），比 MXBean 可靠 ---
        if (freshSample) {
            val ticks = cpu.systemCpuLoadTicks
            val prev = prevTicks
            if (prev != null && prev.size == ticks.size) {
                lastCpuLoad = cpu.getSystemCpuLoadBetweenTicks(prev).takeIf { it >= 0.0 }
            }
            prevTicks = ticks
        }

        // --- 网络 / 磁盘 吞吐（每次采样取差值 / 秒） ---
        if (freshSample) {
            runCatching {
                val net = systemInfo.hardware.networkIFs
                    .filter { it.name.isNotBlank() && it.bytesRecv > 0 }
                    .fold(0L to 0L) { acc, n -> acc.first + n.bytesRecv to acc.second + n.bytesSent }
                val p = prevNet
                if (p != null && net.first >= p.first && net.second >= p.second) {
                    lastRxPerSec = ((net.first - p.first) / elapsedSec).roundToLong()
                    lastTxPerSec = ((net.second - p.second) / elapsedSec).roundToLong()
                }
                prevNet = net
            }
            runCatching {
                val io = systemInfo.hardware.diskStores
                    .filter { it.model?.isNotBlank() == true || it.name.isNotBlank() }
                    .fold(0L to 0L) { acc, d -> acc.first + d.readBytes to acc.second + d.writeBytes }
                val p = prevDisk
                if (p != null && io.first >= p.first && io.second >= p.second) {
                    lastDiskReadPerSec = ((io.first - p.first) / elapsedSec).roundToLong()
                    lastDiskWritePerSec = ((io.second - p.second) / elapsedSec).roundToLong()
                }
                prevDisk = io
            }
            prevSampleAt = now
        }

        val mem = systemInfo.hardware.memory
        val vmem = mem.virtualMemory
        val heap = java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val backend = findBackendProcess()

        // 后端进程 CPU/内存：OSHI 直接按 PID 取，替代 PowerShell Get-Process。
        val backendMemBytes: Long?
        val backendCpuSec: Double?
        if (backend != null) {
            val proc = os.getProcess(backend.pid().toInt())
            backendMemBytes = proc?.residentMemory?.takeIf { it > 0 }
            backendCpuSec = proc?.let { (it.userTime + it.kernelTime).toDouble() / 1_000_000_000.0 }?.takeIf { it > 0 }
        } else {
            backendMemBytes = null
            backendCpuSec = null
        }

        val driveRoot = currentDriveRoot()
        val drive = File(driveRoot)
        val loadAvg = runCatching { cpu.getSystemLoadAverage(1).firstOrNull() }.getOrNull()?.takeIf { it >= 0.0 }

        return ServerRuntimeSnapshot(
            hostname = (System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("server")).ifBlank { "server" },
            osName = os.family ?: System.getProperty("os.name", "Unknown OS"),
            osVersion = runCatching { os.versionInfo.version }.getOrNull() ?: System.getProperty("os.version", ""),
            cpuModel = cpu.processorIdentifier.name?.trim()?.ifBlank { "Unknown CPU" } ?: "Unknown CPU",
            cpuCores = cpu.logicalProcessorCount,
            cpuLoad = lastCpuLoad,
            adminProcessCpuLoad = runCatching { os.getProcess(os.processId)?.let { (it.userTime + it.kernelTime) / 1_000_000_000.0 } }.getOrNull(),
            totalRamBytes = mem.total,
            freeRamBytes = mem.available,
            diskTotalBytes = drive.totalSpace,
            diskFreeBytes = drive.freeSpace,
            adminHeapUsedBytes = heap.used,
            adminHeapMaxBytes = heap.max,
            backendPid = backend?.pid(),
            backendStartedAtEpochMs = backend?.info()?.startInstant()?.orElse(null)?.toEpochMilli(),
            backendUptimeSeconds = backend?.info()?.startInstant()?.orElse(null)?.let {
                (now - it.toEpochMilli()).coerceAtLeast(0L) / 1000L
            },
            backendWorkingSetBytes = backendMemBytes,
            backendCpuSeconds = backendCpuSec,
            osUptimeSeconds = runCatching { os.systemUptime }.getOrNull(),
            updatedAtEpochMs = now,
            cpuPhysicalCores = cpu.physicalProcessorCount,
            cpuLoad1Min = loadAvg,
            processCount = runCatching { os.processCount }.getOrNull(),
            threadCount = runCatching { os.threadCount }.getOrNull(),
            swapTotalBytes = runCatching { vmem.swapTotal }.getOrDefault(0L),
            swapUsedBytes = runCatching { vmem.swapUsed }.getOrDefault(0L),
            networkRxBytesPerSec = lastRxPerSec,
            networkTxBytesPerSec = lastTxPerSec,
            diskReadBytesPerSec = lastDiskReadPerSec,
            diskWriteBytesPerSec = lastDiskWritePerSec,
            monitorSource = "oshi",
        )
    }

    private fun findBackendProcess(): ProcessHandle? =
        ProcessHandle.allProcesses().iterator().asSequence()
            .filter { it.pid() != ProcessHandle.current().pid() }
            .mapNotNull { process ->
                val command = process.info().commandLine().orElse("") ?: ""
                if (command.contains("kewu-8080.jar", true) && command.contains("-jar", true)) process else null
            }
            .firstOrNull()

    private fun currentDriveRoot(): String {
        val cwd = File(".").absolutePath.replace('\\', '/')
        val drive = cwd.take(3).trimEnd('/')
        return if (drive.length == 2 && drive[1] == ':') "$drive/" else "/"
    }
}
