package com.kewu.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.AddCircle
import io.github.suqi8.coui.kmp.icon.extended.Alarm
import io.github.suqi8.coui.kmp.icon.extended.Community
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.icon.extended.Link
import io.github.suqi8.coui.kmp.icon.extended.Recording
import io.github.suqi8.coui.kmp.icon.extended.Undo

// ───────────────────────── 个人资料数据模型（mock） ─────────────────────────

/**
 * 学生个人资料。
 *
 * ★ 学号（[studentId]）是「我的」页的必显字段，也是课悟绑定课表 / 校园功能的核心标识，
 * 所以放在模型第一位并贯穿主卡展示与编辑页。真实接入后由登录接口返回、写入本地。
 */
data class UserProfile(
    val name: String,
    val studentId: String,
    val school: String,
    val className: String,
    val phone: String,
    val email: String,
)

/** 演示默认资料：学号、学校、班级齐全，符合文档「我的页顶部：头像 + 名字 + 学校/班级」。 */
internal val defaultProfile = UserProfile(
    name = "刘宇豪",
    studentId = "20260551073",
    school = "重庆对外经贸学院",
    className = "计算机科学与技术 5 班",
    phone = "178****2370",
    email = "1640432@qq.com",
)

// ───────────────────────── 通用图标字形（coui-icons） ─────────────────────────

/**
 * 「我的」页 / 教师「我的」页功能列表的小图标（2026-10-23 全局 coui 化：原 Canvas 自绘描边
 * 改为 coui-icons 原生字形，与 coui 设置行同一套线条语言）。签名不变，多处按 kind 调用。
 *
 * kind 语义（按全项目调用点核对）：
 * - 0 = 课程绑定 → Link（绑定 = 链接；学生/教师「课程绑定」与 MyPage 绑定卡都用它）
 * - 1 = 录音管理（学生）/ 发起签到（教师）→ Recording
 * - 2 = 通知设置 / 发布笔记（教师）→ Alarm
 * - 3 = 关于我们 → Info
 * - 4 = 退出登录 → Undo（coui 无「登出」字形，取「返回/撤回」箭头语义，红色由调用方给）
 * - 5 = 加入班级 / 班级管理 → Community（多人）
 * - 6 = 新建课程 → AddCircle
 */
@Composable
internal fun ProfileGlyph(kind: Int, color: Color, sizeDp: Dp = 22.dp) {
    Icon(profileGlyphVector(kind), contentDescription = null, tint = color, modifier = Modifier.size(sizeDp))
}

/** kind → coui 图标映射；未知 kind 回退 Info，避免空白。 */
private fun profileGlyphVector(kind: Int): ImageVector = when (kind) {
    0 -> COUIIcons.Link
    1 -> COUIIcons.Recording
    2 -> COUIIcons.Alarm
    3 -> COUIIcons.Info
    4 -> COUIIcons.Undo
    5 -> COUIIcons.Community
    6 -> COUIIcons.AddCircle
    else -> COUIIcons.Info
}
