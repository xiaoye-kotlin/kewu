package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.BackHandler
import com.kewu.app.ClassRoomInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.StudentContact
import com.kewu.app.haptic
import com.kewu.app.openUrl
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * 同学通讯录（教师端「我的」，2026-10-26）：选班级 → 学生名单（姓名 / 学号 / 手机）→ 学生个人主页
 * （学号、学校、手机、邮箱，可一键拨号 / 复制；可把学生移出班级）。
 * 手机 / 邮箱只有该班任课老师拿得到（后端 /contacts 校验 token），同学之间互相看不到。
 */
@Composable
internal fun StudentDirectoryScreen(onBack: () -> Unit) {
    var cls by remember { mutableStateOf<ClassRoomInfo?>(null) }
    var student by remember { mutableStateOf<StudentContact?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    val depth = if (student != null) 2 else if (cls != null) 1 else 0
    BackHandler(enabled = depth > 0) { if (student != null) student = null else cls = null }

    AnimatedContent(
        targetState = depth,
        transitionSpec = {
            if (targetState > initialState) {
                slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it } togetherWith
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 5 }
            } else {
                slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 5 } togetherWith
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { it }
            }
        },
        label = "directory",
    ) { d ->
        when (d) {
            0 -> ClassPickList(onBack) { cls = it }
            1 -> cls?.let { c -> ContactList(c, reloadTick, onBack = { cls = null }) { student = it } }
            else -> {
                val c = cls
                val s = student
                if (c != null && s != null) StudentProfile(c, s, onBack = { student = null }) {
                    student = null
                    reloadTick++
                }
            }
        }
    }
}

@Composable
private fun ClassPickList(onBack: () -> Unit, onPick: (ClassRoomInfo) -> Unit) {
    var classes by remember { mutableStateOf<List<ClassRoomInfo>?>(null) }
    LaunchedEffect(Unit) { classes = KewuApi.getTeacherClasses()?.classes ?: emptyList() }
    Column(Modifier.fillMaxSize().background(COUITheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(bottom = 128.dp)) {
        KewuTopBar("同学通讯录", onBack)
        KewuSectionTitle("选择班级")
        val list = classes
        when {
            list == null -> KewuLoading()
            list.isEmpty() -> KewuEmpty("还没有班级：先在「班级管理」开班，学生凭邀请码加入后就会出现在这里")
            else -> KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
                list.forEach { c ->
                    ArrowPreference(
                        title = c.name,
                        summary = "${c.courseName} · ${c.studentCount} 名学生 · ${c.term}",
                        onClick = { haptic(HapticKind.Tick); onPick(c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactList(cls: ClassRoomInfo, reloadTick: Int, onBack: () -> Unit, onOpen: (StudentContact) -> Unit) {
    var list by remember { mutableStateOf<List<StudentContact>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cls.id, reloadTick) {
        val (r, err) = KewuApi.getClassContacts(cls.id)
        list = r ?: emptyList()
        error = err
    }
    Column(Modifier.fillMaxSize().background(COUITheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(bottom = 128.dp)) {
        KewuTopBar(cls.name, onBack)
        val l = list
        when {
            l == null -> KewuLoading()
            error != null -> KewuEmpty(error.orEmpty())
            l.isEmpty() -> KewuEmpty("班里还没有学生，把邀请码 ${cls.inviteCode} 发给学生即可加入")
            else -> {
                KewuSectionTitle("共 ${l.size} 人 · 点击查看个人主页")
                KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
                    l.forEach { s ->
                        ArrowPreference(
                            title = s.name,
                            summary = "学号 ${s.studentNo}" + (s.phone?.let { " · $it" } ?: " · 未留手机号"),
                            startAction = { Avatar(s.name) },
                            onClick = { haptic(HapticKind.Tick); onOpen(s) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudentProfile(cls: ClassRoomInfo, s: StudentContact, onBack: () -> Unit, onRemoved: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    val clipboard = LocalClipboardManager.current
    val pageBackdrop = rememberGlassBackdrop()
    var confirmRemove by remember { mutableStateOf(false) }
    var removeConfirmed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 128.dp)) {
            KewuTopBar("个人主页", onBack)
            KewuGlassCard(
                backdrop = pageBackdrop, shape = RoundedCornerShape(24.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(KewuBlue.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Text(s.name.take(1), color = KewuBlue, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(s.name, color = cs.onSurface, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("学号 ${s.studentNo}", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                        Text("${cls.name} · ${cls.courseName}", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            KewuSectionTitle("资料与联系方式（仅任课老师可见）")
            KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(title = "学校", summary = s.school?.ifBlank { null } ?: "未填写")
                ArrowPreference(
                    title = "手机号", summary = s.phone ?: "未填写",
                    onClick = {
                        val p = s.phone ?: return@ArrowPreference
                        haptic(HapticKind.Tick)
                        openUrl("tel:$p")
                    },
                )
                ArrowPreference(
                    title = "邮箱", summary = s.email ?: "未填写",
                    onClick = {
                        val e = s.email ?: return@ArrowPreference
                        clipboard.setText(AnnotatedString(e))
                        scope.launch { snackbar.toast("邮箱已复制") }
                    },
                )
                ArrowPreference(
                    title = "复制学号", summary = s.studentNo,
                    onClick = {
                        clipboard.setText(AnnotatedString(s.studentNo))
                        scope.launch { snackbar.toast("学号已复制") }
                    },
                )
            }
            Spacer(Modifier.height(18.dp))
            KewuPrimaryButton(
                "移出班级", { confirmRemove = true }, danger = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                "学生不能自行退出班级。移出后他将看不到本课程的作业和签到，可用邀请码重新加入。",
                color = cs.onSurfaceVariantSummary, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
        KewuConfirmDialog(
            show = confirmRemove,
            title = "移出班级",
            summary = "确定把 ${s.name}（${s.studentNo}）移出「${cls.name}」吗？",
            confirmText = "移出",
            onConfirm = { removeConfirmed = true; confirmRemove = false },
            onDismiss = { confirmRemove = false },
            onDismissFinished = {
                if (removeConfirmed) {
                    removeConfirmed = false
                    scope.launch {
                        val err = KewuApi.removeStudentFromClass(cls.id, s.userId)
                        snackbar.toast(err ?: "已将 ${s.name} 移出班级")
                        if (err == null) onRemoved()
                    }
                }
            },
        )
    }
}

@Composable
private fun Avatar(name: String) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(KewuBlue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Text(name.take(1), color = KewuBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
