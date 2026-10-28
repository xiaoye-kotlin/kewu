package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AssignmentInfo
import com.kewu.app.ClassNoteInfo
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ExpandLess
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

// ───────────────────────── 课程详情 / 课堂纪要 Hub ─────────────────────────

/**
 * 课程中心 · 课程详情页（含课堂纪要）。
 *
 * 接入的 API（全部来自 [KewuApi]）：
 * - `getCourse` 课程元信息
 * - `getNoteCache(courseId)` 课堂速记实时缓存（8081 Redis，24h 有效，老师在教师工具「课堂笔记」发布；空则不显示）
 * - `getNotes` 本课程课堂纪要列表
 * - `getNote` 单条纪要详情（点击展开时懒加载）
 * - `getAssignments(courseId)` 本课程作业
 *
 * 交互：
 * - 顶部返回 → [onBack]
 * - 作业行 → [onOpenAssignment]（由调用方决定打开作业详情 Hub）
 * - 纪要行 → 就地展开正文（首次展开懒加载 [getNote]）
 *
 * 配色走 [COUITheme] 语义色；头部保留课悟液态玻璃，列表项用 coui [Card]（2026-10-23 全局 coui 化）。
 */
@Composable
fun CourseDetailScreen(
    courseId: String,
    onBack: () -> Unit,
    onOpenAssignment: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    var course by remember { mutableStateOf<CourseInfo?>(null) }
    var noteCache by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<ClassNoteInfo>?>(null) }
    var assignments by remember { mutableStateOf<List<AssignmentInfo>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var expandedNoteId by remember { mutableStateOf<String?>(null) }
    var expandedContent by remember { mutableStateOf<ClassNoteInfo?>(null) }

    LaunchedEffect(courseId) {
        loading = true
        scope.launch {
            course = KewuApi.getCourse(courseId)
            notes = KewuApi.getNotes(courseId)
            assignments = KewuApi.getAssignments(courseId)
            loading = false
        }
        // 速记缓存走 8081（另一台服务），单独拉、不阻塞主体加载；404 / 失败 → null → 不显示
        scope.launch { noteCache = KewuApi.getNoteCache(courseId)?.takeIf { it.isNotBlank() } }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.surface),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 96.dp),
        ) {
            HubTopBar(course?.name ?: "课程详情", onBack)
            Spacer(Modifier.height(4.dp))

            if (loading) {
                KewuLoading()
            } else {
                course?.let { c ->
                    KewuGlassCard(
                        backdrop = pageBackdrop, shape = RoundedCornerShape(20.dp),
                        blurRadius = 22.dp, tintAlpha = 0.28f,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(c.name, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            MetaLine("授课教师", c.teacher.ifBlank { "—" })
                            if (c.description.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(c.description, color = cs.onSurfaceSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                } ?: EmptyHintRow("未找到该课程")

                // 课堂速记（实时缓存）：老师课上随手发布的要点，24h 内有效；没有就整段不出现
                noteCache?.let { text ->
                    HubSectionLabel("课堂速记（实时缓存）")
                    NoteCacheCard(text)
                    Spacer(Modifier.height(14.dp))
                }

                // 课堂纪要
                HubSectionLabel("课堂纪要")
                notes?.let { list ->
                    if (list.isEmpty()) {
                        EmptyHintRow("暂无课堂纪要")
                    } else {
                        list.forEach { note ->
                            NoteRow(
                                note = note,
                                expanded = expandedNoteId == note.id,
                                // 只认 id 对得上的内容：快速先展开 A 再展开 B 时，A 晚到的结果不会显示在 B 下面
                                expandedContent = if (expandedNoteId == note.id) expandedContent?.takeIf { it.id == note.id } else null,
                                onToggle = {
                                    if (expandedNoteId == note.id) {
                                        expandedNoteId = null
                                        expandedContent = null
                                    } else {
                                        expandedNoteId = note.id
                                        if (expandedContent?.id != note.id) {
                                            scope.launch { expandedContent = KewuApi.getNote(courseId, note.id) }
                                        }
                                    }
                                },
                            )
                        }
                    }
                } ?: EmptyHintRow("课堂纪要加载失败，请稍后重试")
                Spacer(Modifier.height(14.dp))

                // 本课程作业
                HubSectionLabel("本课程作业")
                assignments?.let { list ->
                    if (list.isEmpty()) {
                        EmptyHintRow("本课程暂无作业")
                    } else {
                        list.forEachIndexed { i, a ->
                            AssignmentMiniRow(assignment = a, index = i) {
                                onOpenAssignment(a.id)
                            }
                        }
                    }
                } ?: EmptyHintRow("作业加载失败，请稍后重试")
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 段内空态（coui 白卡 + 次级文字，经 [KewuEmpty]）。 */
@Composable
private fun EmptyHintRow(text: String) {
    KewuEmpty(text)
}

/** 列表行白卡：coui [Card]（可点击 → 按压 Tint），左右 12dp、行间 10dp。 */
@Composable
private fun RowCard(onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(16.dp),
        colors = kewuCardColors(),
        onClick = onClick,
    ) { content() }
}

/** 课堂速记卡：8081 实时缓存正文（老师端「课堂笔记」发布，24h 过期）。 */
@Composable
private fun NoteCacheCard(text: String) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(16.dp),
        colors = kewuCardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(COUIIcons.Notes, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("老师刚刚发布", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            KewuTag("24h 内有效", cs.tertiaryContainer, cs.onTertiaryContainer)
        }
        Spacer(Modifier.height(10.dp))
        Text(text, color = cs.onSurfaceSecondary, fontSize = 13.5.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun NoteRow(
    note: ClassNoteInfo,
    expanded: Boolean,
    expandedContent: ClassNoteInfo?,
    onToggle: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    RowCard(onClick = { haptic(HapticKind.Tick); onToggle() }) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(note.title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Text("${note.author} · ${note.createdAt.ifBlank { "—" }}", color = cs.onSurfaceSecondary, fontSize = 12.5.sp, maxLines = 1)
                }
                Icon(
                    if (expanded) COUIIcons.ExpandLess else COUIIcons.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = cs.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    val body = expandedContent?.content ?: note.content
                    Text(
                        body.ifBlank { "（暂无正文）" },
                        color = cs.onSurfaceSecondary, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssignmentMiniRow(
    assignment: AssignmentInfo,
    index: Int,
    onClick: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    RowCard(onClick = { haptic(HapticKind.Tick); onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(assignment.title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Text("满分 ${assignment.totalScore} · 截止 ${assignment.deadline.ifBlank { "未设置" }}", color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            KewuTag("查看", KewuGreen.copy(alpha = 0.18f), KewuGreen)
        }
    }
}
