package com.kewu.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 预加载缓存 Slot 的纯逻辑测试（无需 GUI / 网络）。
 */
class PrefetchCacheTest {

    @Test
    fun `slot fresh returns value just set`() {
        val slot = AdminPrefetch.Slot<String>()
        assertNull(slot.fresh())
        assertNull(slot.peek())
        slot.set("hello")
        assertEquals("hello", slot.fresh())
        assertEquals("hello", slot.peek())
        assertTrue(slot.at > 0)
    }

    @Test
    fun `slot fresh respects max age`() {
        val slot = AdminPrefetch.Slot<Int>()
        slot.set(42)
        // 设置一个极小的时间窗：at 是 now，fresh(0) 应立即认为过期（now - at >= 0）。
        assertNull(slot.fresh(0), "0ms 时效窗口应视为过期")
        assertTrue(slot.fresh(1000) == 42)
    }

    @Test
    fun `slot clear resets value and timestamp`() {
        val slot = AdminPrefetch.Slot<String>()
        slot.set("x")
        slot.clear()
        assertNull(slot.peek())
        assertEquals(0L, slot.at)
    }
}
