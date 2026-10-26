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
