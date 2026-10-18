package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.kewu.app.hapticClickable
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlinx.coroutines.launch

// ───────────────────────── 课程详情 / 课堂纪要 Hub ─────────────────────────

/**
 * 课程中心 · 课程详情页（含课堂纪要）。
 *
 * 接入的 API（全部来自 [KewuApi]）：
 * - `getCourse` 课程元信息
 * - `getNotes` 本课程课堂纪要列表
 * - `getNote` 单条纪要详情（点击展开时懒加载）
 * - `getAssignments(courseId)` 本课程作业
 *
 * 交互：
 * - 顶部返回 → [onBack]
 * - 作业行 → [onOpenAssignment]（由调用方决定打开作业详情 Hub）
 * - 纪要行 → 就地展开正文（首次展开懒加载 [getNote]）
 *
 * 配色走 [MaterialTheme] 语义色；头部用 coui 液态玻璃，列表项用 squircle。
 */
@Composable
fun CourseDetailScreen(
    courseId: String,
    onBack: () -> Unit,
    onOpenAssignment: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    var course by remember { mutableStateOf<CourseInfo?>(null) }
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
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.background),
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
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
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
                                Text(c.description, color = cs.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                } ?: EmptyHintRow("未找到该课程")

                // 课堂纪要
                HubSectionLabel("课堂纪要")
                notes?.let { list ->
                    if (list.isEmpty()) {
                        EmptyHintRow("暂无课堂纪要")
                    } else {
                        list.forEach { note ->
                            NoteRow(
                                backdrop = pageBackdrop, note = note,
                                expanded = expandedNoteId == note.id,
                                expandedContent = if (expandedNoteId == note.id) expandedContent else null,
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
                            AssignmentMiniRow(backdrop = pageBackdrop, assignment = a, index = i) {
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
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyHintRow(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .squircleCard(16.dp, cs.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(text, color = cs.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun NoteRow(
    backdrop: LayerBackdrop,
    note: ClassNoteInfo,
    expanded: Boolean,
    expandedContent: ClassNoteInfo?,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .squircleCard(16.dp, cs.surfaceVariant)
            .hapticClickable { haptic(HapticKind.Tick); onToggle() }
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(note.title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Text("${note.author} · ${note.createdAt.ifBlank { "—" }}", color = cs.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
                }
                Text(if (expanded) "收起" else "展开", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    val body = expandedContent?.content ?: note.content
                    Text(
                        body.ifBlank { "（暂无正文）" },
                        color = cs.onSurfaceVariant, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssignmentMiniRow(
    backdrop: LayerBackdrop,
    assignment: AssignmentInfo,
    index: Int,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .squircleCard(16.dp, cs.surfaceVariant)
            .hapticClickable { onClick() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(assignment.title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Text("满分 ${assignment.totalScore} · 截止 ${assignment.deadline.ifBlank { "未设置" }}", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            StatusTag("查看", KewuGreen.copy(alpha = 0.18f), KewuGreen)
        }
    }
}
