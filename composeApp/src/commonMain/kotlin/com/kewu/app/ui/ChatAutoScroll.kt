package com.kewu.app.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.yield

/**
 * 聊天列表「贴底跟随」（2026-10-26，修助教页「点输入框打字后收起输入法，界面突然跳到上面的对话」）。
 *
 * 旧逻辑：`LaunchedEffect(messages.size, imeVisible) { animateScrollToItem(lastIndex) }`——
 * `animateScrollToItem` 是把最后一条的**顶部**对齐视口顶部，最后一条稍长就只露出上半截 / 更早的对话；
 * 而收起输入法时人格区重新展开、输入框下移，视口在动画里逐帧变高，列表也没有保持贴底。
 *
 * 新逻辑：
 *   - [stick]：用户是否「停在底部」。只在**用户手指拖动结束**时更新（程序滚动 / 视口变化不改它）；
 *   - 视口高度每变一次（键盘弹起 / 收起、人格区展开、输入框位移的每一帧），若 stick 就立刻把最后一条底边对齐视口底；
 *   - 有新消息时（[messageKey] 变化）平滑滚到最底，并重新贴底。
 */
@Composable
fun ChatAutoScroll(listState: LazyListState, messageKey: Any) {
    var stick by remember { mutableStateOf(true) }

    // 用户拖动结束：在底部 → 继续贴底；往上翻了 → 不再自动拉回（看历史时不打扰）
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Stop || i is DragInteraction.Cancel) {
                yield()
                stick = !listState.canScrollForward
            }
        }
    }
    // 视口尺寸变化（输入法 / 人格区 / 输入框动画逐帧）→ 贴底
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect {
            if (stick) listState.scrollToBottom(animated = false)
        }
    }
    // 新消息 → 先让一帧给气泡完成布局，再平滑滚到底（不是瞬间跳）
    LaunchedEffect(messageKey) {
        yield()
        stick = true
        listState.scrollToBottom(animated = true)
    }
}

/** 把最后一项的**底边**对齐视口底（最后一项比视口高时也是看到它的结尾）。 */
suspend fun LazyListState.scrollToBottom(animated: Boolean) {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == last }) {
        if (animated) animateScrollToItem(last) else scrollToItem(last)
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.lastOrNull { it.index == last } ?: return
    val overflow = item.offset + item.size - (info.viewportEndOffset - info.afterContentPadding)
    if (overflow > 0) {
        if (animated) animateScrollBy(overflow.toFloat()) else scrollBy(overflow.toFloat())
    }
}
