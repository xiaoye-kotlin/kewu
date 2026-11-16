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
    fun `slot cached value never expires during session`() {
        val slot = AdminPrefetch.Slot<Int>()
        slot.set(42)
        // 会话内缓存不按时间过期：即使传 0 时效也返回缓存（切 tab 不重载的关键）。
        assertTrue(slot.get() == 42)
        assertTrue(slot.fresh(0) == 42, "会话缓存不应因时效被清空")
        assertTrue(slot.peek() == 42)
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
