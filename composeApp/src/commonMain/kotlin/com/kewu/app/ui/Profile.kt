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

/**
 * 空资料（2026-10-26：旧版默认是一份真实感很强的演示资料「刘宇豪 / 手机 / 邮箱」，
 * 后端没有的字段会一直把这份假资料展示给所有用户）。
 */
internal val defaultProfile = UserProfile(
    name = "", studentId = "", school = "", className = "", phone = "", email = "",
)

/**
 * 本地资料存储（按账号）：后端 /user/me 只有 id / username / nickname，学校 / 班级 / 手机 / 邮箱
 * 由用户在「编辑资料」里填、注册时选的学校也存这里。旧版编辑资料只存在内存里，切个 tab 就没了。
 */
internal object ProfileStore {
    private const val SEP = "\u0001"
    private fun key(user: String) = "kewu_profile_$user"

    fun load(user: String): UserProfile? {
        if (user.isBlank()) return null
        val raw = com.kewu.app.kvGet(key(user)) ?: return null
        val f = raw.split(SEP)
        if (f.size < 6) return null
        return UserProfile(f[0], f[1], f[2], f[3], f[4], f[5])
    }

    fun save(user: String, p: UserProfile) {
        if (user.isBlank()) return
        com.kewu.app.kvSet(key(user), listOf(p.name, p.studentId, p.school, p.className, p.phone, p.email)
            .joinToString(SEP) { it.replace(SEP, "") })
    }

    /** 注册成功时写入所选学校（其它字段留空）。 */
    fun setSchool(user: String, school: String) {
        if (school.isBlank()) return
        val cur = load(user) ?: defaultProfile.copy(studentId = user)
        save(user, cur.copy(school = school))
    }
}

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
