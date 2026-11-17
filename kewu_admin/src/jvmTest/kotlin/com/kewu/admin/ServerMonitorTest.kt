package com.kewu.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OSHI 采集的真实性测试（2026-11-17 §81）。
 * 直接调用 ServerMonitor.snapshot()，验证以前靠 PowerShell 常取不到的字段现在都有值：
 * CPU 型号、系统运行时长、物理内存、磁盘、进程数/线程数、后端/自身进程 CPU 等。
 * 该测试在本机（Windows/Linux/macOS）真实执行，不需要服务器。
 */
class ServerMonitorTest {

    @Test
    fun `snapshot collects real system info via oshi`() {
        val s = ServerMonitor.snapshot()
        println("=== OSHI snapshot ===")
        println("host=${s.hostname} os=${s.osName} ${s.osVersion}")
        println("cpu=${s.cpuModel} cores=${s.cpuPhysicalCores}/${s.cpuCores} load=${s.cpuLoad}")
        println("ram=${s.totalRamBytes}/${s.freeRamBytes} swap=${s.swapUsedBytes}/${s.swapTotalBytes}")
        println("disk=${s.diskFreeBytes}/${s.diskTotalBytes}")
        println("uptime=${s.osUptimeSeconds} proc=${s.processCount} thread=${s.threadCount}")
        println("load1m=${s.cpuLoad1Min} source=${s.monitorSource}")

        // 关键：以前 PowerShell 拿不到的，现在必须非空/有效
        assertTrue(s.cpuModel.isNotBlank() && s.cpuModel != "Unknown CPU", "CPU 型号应可获取，实际=${s.cpuModel}")
        assertNotNull(s.osUptimeSeconds, "系统运行时长应可获取")
        assertTrue(s.osUptimeSeconds!! > 0, "系统运行时长应大于 0")
        assertTrue(s.totalRamBytes > 0, "物理内存总量应大于 0")
        assertTrue(s.cpuCores > 0, "逻辑线程数应大于 0")
        assertTrue(s.cpuPhysicalCores > 0, "物理核数应大于 0")
        assertTrue(s.diskTotalBytes > 0, "磁盘总量应大于 0")
        assertNotNull(s.processCount, "进程数应可获取")
        assertTrue(s.processCount!! > 0, "进程数应大于 0")
        assertNotNull(s.threadCount, "线程数应可获取")
        assertTrue(s.osName.isNotBlank(), "操作系统名应可获取")
        assertEquals("oshi", s.monitorSource)
    }

    @Test
    fun `snapshot second call computes cpu and throughput deltas`() {
        ServerMonitor.snapshot() // 第一次建立基线
        Thread.sleep(1200)
        val s2 = ServerMonitor.snapshot()
        // CPU 负载（基于 tick 差值）应可算出 0..1 的值
        assertNotNull(s2.cpuLoad, "第二次采样应能算出 CPU 负载")
        assertTrue(s2.cpuLoad!! in 0.0..1.0, "CPU 负载应在 0..1，实际=${s2.cpuLoad}")
        // 网络/磁盘吞吐为速率值（可能为 0，但不应为负）
        s2.networkRxBytesPerSec?.let { assertTrue(it >= 0) }
        s2.diskReadBytesPerSec?.let { assertTrue(it >= 0) }
    }
}
