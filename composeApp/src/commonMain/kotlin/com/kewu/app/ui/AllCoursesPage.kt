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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kewu.app.BackHandler
import com.kewu.app.CourseInfo
import com.kewu.app.CourseRepo
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.PullToRefresh
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * 「我的课程」全部列表（2026-10-26，首页左侧大状态卡点进来）。
 *
 * 纯 coui：[KewuTopBar] 顶栏 + coui [PullToRefresh] 下拉刷新 + [KewuCardGroup] 分组卡里一门课一行
 * coui [ArrowPreference]（课程名 / 任课教师 · 简介）。数据走 [CourseRepo]（与首页、我的页同一份缓存）。
 * 点某门课 → 课程详情 [CourseDetailScreen]（纪要 / 本课作业）→ 作业行再下钻到 [AssignmentDetailScreen]。
 * 子路由与首页下钻同款 slide-only 转场（不加 fade，避免玻璃采样者进离屏层）。
 */
@Composable
fun AllCoursesPage(onBack: () -> Unit, onNavHide: (Boolean) -> Unit) {
    var openCourse by remember { mutableStateOf<String?>(null) }
    var openAssignment by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onNavHide(false) }

    val depth = when {
        openAssignment != null -> 2
        openCourse != null -> 1
        else -> 0
    }
    AnimatedContent(
        targetState = depth,
        transitionSpec = {
            if (targetState > initialState) {
                slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> w } togetherWith
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -w / 5 }
            } else {
                slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -w / 5 } togetherWith
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> w }
            }
        },
        label = "allCoursesDrill",
    ) { d ->
        when (d) {
            2 -> {
                val aid = openAssignment ?: return@AnimatedContent
                BackHandler { openAssignment = null }
                AssignmentDetailScreen(assignmentId = aid, onBack = { openAssignment = null })
            }
            1 -> {
                val cid = openCourse ?: return@AnimatedContent
                BackHandler { openCourse = null }
                CourseDetailScreen(
                    courseId = cid,
                    onBack = { openCourse = null },
                    onOpenAssignment = { openAssignment = it },
                )
            }
            else -> CourseListContent(onBack = onBack, onOpen = { openCourse = it.id })
        }
    }
}

@Composable
private fun CourseListContent(onBack: () -> Unit, onOpen: (CourseInfo) -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val courses by CourseRepo.courses.collectAsState()
    val loaded by CourseRepo.loaded.collectAsState()
    var refreshing by remember { mutableStateOf(false) }

    // 进页静默刷新一次（有缓存先显示缓存，不回 loading 态）
    LaunchedEffect(Unit) { CourseRepo.refresh(force = true) }

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    CourseRepo.refresh(force = true)
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 96.dp),
            ) {
                val list = courses
                KewuTopBar(
                    title = "我的课程",
                    onBack = onBack,
                    subtitle = if (list != null) "共 ${list.size} 门" else "",
                )
                Spacer(Modifier.height(4.dp))
                when {
                    list == null && !loaded -> KewuLoading(text = "正在同步课程…")
                    list == null -> {
                        KewuEmpty("课程加载失败，请检查网络")
                        KewuSecondaryButton(
                            text = "重新加载",
                            onClick = { scope.launch { CourseRepo.refresh(force = true) } },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    list.isEmpty() -> KewuEmpty("还没有课程，可在「我的 → 绑定课程」加入")
                    else -> {
                        KewuSectionTitle("全部课程")
                        KewuCardGroup {
                            list.forEach { c ->
                                ArrowPreference(
                                    title = c.name.ifBlank { c.id },
                                    summary = listOf(c.teacher, c.description)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                        .ifBlank { null },
                                    onClick = { haptic(HapticKind.Tick); onOpen(c) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
