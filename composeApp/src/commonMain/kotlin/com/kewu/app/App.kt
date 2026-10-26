package com.kewu.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.kewu.app.ui.toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.kewu.app.ui.KewuTheme
import com.kewu.app.ui.LoginScreen
import com.kewu.app.ui.MainScreen
import com.kewu.app.ui.TeacherMainScreen

// 课悟品牌色，后续界面可复用
val KewuBlue = Color(0xFF1450C8)
val KewuDeepBlue = Color(0xFF0B1E4B)
val KewuSky = Color(0xFF2F7BF0)
// 课堂主页「绿色勾勾」主题色（参考 KernelSU 主页：已验证/已完成态用绿）
// 用户要求更「亮眼」：旧 0xFF2BD17A 偏暗沉，换更鲜亮通透的荧光绿
val KewuGreen = Color(0xFF19E68C)

// ───────────────────────── 角色（单应用多角色架构，2026-10-19 教师端） ─────────────────────────
//
// 后端暂无角色字段：登录页由用户自选「我是学生 / 我是老师」，选择持久化到 kv（kewu_role），
// 登录成功后这里按角色分流到两套 UI：学生端 MainScreen（4 tab）/ 教师端 TeacherMainScreen（3 tab）。
// 底层网络、数据模型、玻璃组件完全共用；退出登录后回到登录页可重新选角色。

const val ROLE_STUDENT = "student"
const val ROLE_TEACHER = "teacher"

fun currentRole(): String = kvGet("kewu_role") ?: ROLE_STUDENT

@Composable
fun App() {
    // 根主题 = COUITheme + 对齐的 M3 色板 + 根 coui Scaffold（覆盖层弹窗 / 底部弹层 / Snackbar 宿主），
    // 见 ui/KewuCoui.kt。绿色只作为品牌强调色用在「勾勾」徽章上。
    KewuTheme {
        // 启动首屏 = 登录/注册（液态玻璃 + coui）。mock 登录态：登录成功后进入主界面，
        // 在「我的」页点「退出登录」会回到这里（App 级持有 loggedIn，保证跨 tab 不丢）。
        // ★ 自动登录：首次登录后 kv 写入 kewu_logged="1"，此处据此跳过登录页；
        //   只有「我的→退出登录」会清空该标记（见 onLogout），下次启动才回到登录页。
        var loggedIn by remember { mutableStateOf(kvGet("kewu_logged") == "1") }
        // 角色状态提升到这里：登录成功时从 kv 重读（登录页的角色选择已写入），
        // 决定进学生端还是教师端。
        var role by remember { mutableStateOf(currentRole()) }
        val snackbar = com.kewu.app.ui.LocalKewuSnackbar.current
        fun clearSession(keepAccount: Boolean) {
            // 退出登录：清自动登录标记 + 密码（主动退出时连账号一起清）+ token + 各账号相关的内存缓存。
            // 角色 kewu_role 保留：登录页角色选择会以上次角色为默认值，方便同账号快速重登。
            kvSet("kewu_logged", "")
            if (!keepAccount) kvSet("kewu_account", "")
            kvSet("kewu_pwd", "")
            RecordingManager.cancelIfRecording()
            AudioPlayback.stop()
            KewuApi.logout()
            CourseRepo.clear()        // 课程缓存属于上一个账号
            AssignmentRepo.clear()
            com.kewu.app.ui.PublishDraft.clear()
            ChatHistory.clearCache()  // 聊天记录按账号存，换账号要重新读
            loggedIn = false
        }
        val doLogout = { clearSession(keepAccount = false) }
        // 任一接口返回 401（token 过期 / 被后端吊销）→ 提示并回登录页，保留账号便于重登（2026-10-26）
        val expired by KewuApi.sessionExpired.collectAsState()
        LaunchedEffect(expired) {
            if (expired > 0 && loggedIn) {
                clearSession(keepAccount = true)
                snackbar.toast("登录已过期，请重新登录")
            }
        }
        if (loggedIn) {
            if (role == ROLE_TEACHER) {
                TeacherMainScreen(onLogout = doLogout)
            } else {
                MainScreen(onLogout = doLogout)
            }
        } else {
            LoginScreen(onLogin = {
                role = currentRole()
                loggedIn = true
            })
        }
    }
}
