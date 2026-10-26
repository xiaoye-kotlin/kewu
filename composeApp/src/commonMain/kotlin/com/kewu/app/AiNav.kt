package com.kewu.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * AI 助教代操作导航总线（2026-10-24）。
 *
 * 小悟理解用户意图后输出结构化指令（见 AiAgent），页面把指令交给 [AiAgent.execute]，
 * 导航类动作通过本总线发给主界面执行（切 tab / 打开「我的」子页）。
 * 用 StateFlow 而不是回调：发送方（助教页）与接收方（MainScreen/MyPage）互不持有引用。
 *
 * 约定：值为 -1 / 0 表示「无请求」；消费方处理完要复位，避免重进页面重复触发。
 */
object AiNav {

    /** 学生端：请求切换底部 tab（0课堂 1助教 2任务 3我的；-1 无）。 */
    val studentTab = MutableStateFlow(-1)

    /** 学生端：请求打开「我的」子页（编号见 MyPage：2录音管理 3课程绑定 4加入班级 6通知设置 7关于我们）。 */
    val studentMyScreen = MutableStateFlow(0)

    /** 教师端：请求切换底部 tab（0控制台 1助教 2作业 3我的；-1 无）。 */
    val teacherTab = MutableStateFlow(-1)

    /** 教师端：请求打开「我的」子页（1课程绑定 2发起签到 3发布笔记 4新建课程 5班级管理 6通知设置 7关于我们）。 */
    val teacherMyScreen = MutableStateFlow(0)

    fun goStudentTab(idx: Int) { studentTab.value = idx }

    fun openStudentMy(screen: Int) {
        studentMyScreen.value = screen
        studentTab.value = 3
    }

    fun goTeacherTab(idx: Int) { teacherTab.value = idx }

    fun openTeacherMy(screen: Int) {
        teacherMyScreen.value = screen
        teacherTab.value = 3
    }
}

/**
 * 横滑切 tab 锁（2026-10-26）：任一页面处在下钻子页（作答 / 批阅 / 发布 / 课程详情 / 设置子页…）时上锁，
 * 主界面暂停「内容区左右滑切 tab」。旧版下钻时一次斜滑就切走 tab，页面状态（作答草稿、批阅进度）全部丢失。
 */
object TabSwipeLock {
    val count = kotlinx.coroutines.flow.MutableStateFlow(0)
}

/** 在 [active] 为 true 期间给主界面上横滑锁（离开组合或 active 变 false 自动解锁）。 */
@androidx.compose.runtime.Composable
fun LockTabSwipe(active: Boolean) {
    androidx.compose.runtime.DisposableEffect(active) {
        if (active) TabSwipeLock.count.value += 1
        onDispose { if (active) TabSwipeLock.count.value -= 1 }
    }
}
