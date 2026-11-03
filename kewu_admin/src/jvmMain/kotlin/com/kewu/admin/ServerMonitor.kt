package com.kewu.admin

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** 服务器本机实时状态。总控端应运行在服务器上；进程检测不到时仅隐藏本机后端进程指标。 */
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
)

object ServerMonitor {
    private var cachedCpuModel: String? = null
    private var cachedCpuCores: Int? = null
    private var cachedOsUptimeSeconds: Long? = null
    private var cachedBackendMemoryAt: Long = 0L
    private var cachedBackendWorkingSet: Long? = null
    private var cachedBackendCpuSeconds: Double? = null

    fun snapshot(): ServerRuntimeSnapshot {
        refreshStaticSystemInfo()
        val osBean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val backend = findBackendProcess()
        refreshBackendProcessStats(backend?.pid())

        val driveRoot = currentDriveRoot()
        val drive = File(driveRoot)
        return ServerRuntimeSnapshot(
            hostname = (System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("server")).ifBlank { "server" },
            osName = System.getProperty("os.name", "Unknown OS"),
            osVersion = System.getProperty("os.version", ""),
            cpuModel = cachedCpuModel ?: "Unknown CPU",
            cpuCores = cachedCpuCores ?: ManagementFactory.getOperatingSystemMXBean().availableProcessors,
            cpuLoad = osBean?.cpuLoad?.takeIf { it >= 0.0 },
            adminProcessCpuLoad = osBean?.processCpuLoad?.takeIf { it >= 0.0 },
            totalRamBytes = osBean?.totalMemorySize ?: 0L,
            freeRamBytes = osBean?.freeMemorySize ?: 0L,
            diskTotalBytes = drive.totalSpace,
            diskFreeBytes = drive.freeSpace,
            adminHeapUsedBytes = heap.used,
            adminHeapMaxBytes = heap.max,
            backendPid = backend?.pid(),
            backendStartedAtEpochMs = backend?.info()?.startInstant()?.orElse(null)?.toEpochMilli(),
            backendUptimeSeconds = backend?.info()?.startInstant()?.orElse(null)?.let {
                (System.currentTimeMillis() - it.toEpochMilli()).coerceAtLeast(0L) / 1000L
            },
            backendWorkingSetBytes = cachedBackendWorkingSet,
            backendCpuSeconds = cachedBackendCpuSeconds,
            osUptimeSeconds = cachedOsUptimeSeconds,
            updatedAtEpochMs = System.currentTimeMillis(),
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

    private fun refreshBackendProcessStats(pid: Long?) {
        if (pid == null) {
            cachedBackendWorkingSet = null
            cachedBackendCpuSeconds = null
            return
        }
        val now = System.currentTimeMillis()
        if (now - cachedBackendMemoryAt < 5_000L) return
        cachedBackendMemoryAt = now
        val json = queryJson(
            "Get-Process -Id $pid -ErrorAction SilentlyContinue | Select-Object CPU,WorkingSet64 | ConvertTo-Json"
        )
        if (json == null) {
            cachedBackendWorkingSet = null
            cachedBackendCpuSeconds = null
        } else {
            cachedBackendWorkingSet = json.long("WorkingSet64").takeIf { it > 0L }
            cachedBackendCpuSeconds = json.double("CPU").takeIf { it > 0.0 }
        }
    }

    private fun refreshStaticSystemInfo() {
        if (cachedCpuModel != null && cachedOsUptimeSeconds != null) return
        val json = queryJson(
            """
            ${'$'}cpu = Get-CimInstance Win32_Processor | Select-Object -First 1 Name,NumberOfLogicalProcessors
            ${'$'}os = Get-CimInstance Win32_OperatingSystem
            [pscustomobject]@{ CpuName = ${'$'}cpu.Name; Cores = ${'$'}cpu.NumberOfLogicalProcessors; OsUptimeSeconds = ([DateTime]::Now - ${'$'}os.LastBootUpTime).TotalSeconds } | ConvertTo-Json
            """.trimIndent()
        )
        cachedCpuModel = json?.str("CpuName") ?: "Unknown CPU"
        cachedCpuCores = json?.long("Cores")?.toInt() ?: ManagementFactory.getOperatingSystemMXBean().availableProcessors
        cachedOsUptimeSeconds = json?.double("OsUptimeSeconds")?.roundToLong()
    }

    private fun currentDriveRoot(): String {
        val cwd = File(".").absolutePath.replace('\\', '/')
        val drive = cwd.take(3).trimEnd('/')
        return if (drive.length == 2 && drive[1] == ':') "$drive/" else "/"
    }

    private fun queryJson(command: String): JsonObject? {
        val text = runPowerShell(command) ?: return null
        return runCatching { AdminJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    private fun runPowerShell(command: String): String? {
        if (!System.getProperty("os.name", "").startsWith("Windows", true)) return null
        return runCatching {
            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command,
            ).redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            String(process.inputStream.readAllBytes(), Charsets.UTF_8).trim()
        }.getOrNull()
    }
}
