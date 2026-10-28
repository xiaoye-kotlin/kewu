package com.kewu.app.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AttachmentInfo
import com.kewu.app.AttendanceSession
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.LocalAttachment
import com.kewu.app.haptic
import com.kewu.app.todayCn
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * 课前准备（2026-10-26）：老师点「开始上课」选好课程后、**发起签到之前**，先问要不要上传本节课的
 * PPT / 讲义 / 资料。资料存到后端 `course_materials`，后续交给 AI 结合课堂录音分析、课后指导学生。
 *
 * 版式沿用 coui + 液态玻璃混合：顶部说明是液态玻璃卡（采样本页灰底），上传区 / 说明输入 / 按钮全 coui。
 * 两条出口：「上传资料并开始签到」「跳过，直接签到」；签到成功后回调 [onStarted]（与旧一键开课同一套后续）。
 */
@Composable
fun PreClassScreen(
    course: CourseInfo,
    durationMinutes: Int,
    onBack: () -> Unit,
    onStarted: (AttendanceSession) -> Unit,
    onToast: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()
    val attachments = remember { mutableStateListOf<LocalAttachment>() }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    /** 发起签到（两条出口共用）。 */
    suspend fun startAttendance(): Boolean {
        stage = "正在发起签到…"
        val s = KewuApi.createAttendance(
            courseId = course.id,
            title = "${course.name} 课堂签到",
            durationMinutes = durationMinutes,
            publisher = KewuApi.nameForServer(),
        )
        if (s == null) {
            error = "签到发起失败，请检查网络后重试"
            return false
        }
        haptic(HapticKind.Light)
        onStarted(s)
        return true
    }

    fun uploadThenStart() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val uploaded = mutableListOf<AttachmentInfo>()
            for ((i, att) in attachments.toList().withIndex()) {
                stage = "正在上传资料 ${i + 1} / ${attachments.size}…"
                val (info, err) = KewuApi.uploadAttachment(att)
                if (info == null) {
                    error = "「${att.name}」上传失败：${err ?: "未知错误"}"
                    busy = false
                    return@launch
                }
                uploaded += info
            }
            stage = "正在保存资料…"
            val err = KewuApi.createCourseMaterial(
                course.id, "${course.name} · ${todayCn()} 课前资料", note.trim(), uploaded,
            )
            if (err != null) {
                error = "资料保存失败：$err"
                busy = false
                return@launch
            }
            onToast("课前资料已上传（${uploaded.size} 个文件）")
            if (!startAttendance()) busy = false
        }
    }

    fun skipThenStart() {
        if (busy) return
        busy = true
        error = null
        scope.launch { if (!startAttendance()) busy = false }
    }

    Box(Modifier.fillMaxSize()) {
        // 采样层：coui 灰底，与玻璃卡平级 ✓（玻璃卡祖先无 alpha / scale / graphicsLayer）
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
            KewuTopBar("课前准备", onBack = { if (!busy) onBack() })
            Spacer(Modifier.height(4.dp))

            KewuGlassCard(
                backdrop = pageBackdrop, shape = RoundedCornerShape(22.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape).background(KewuGreen),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(course.name, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "签到时长 $durationMinutes 分钟 · ${todayCn()}",
                        color = cs.onSurfaceSecondary, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "上课前要上传本节课的 PPT / 讲义吗？",
                        color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "小悟会把资料和课堂录音放在一起分析：哪些知识点讲透了、哪些学生可能没跟上，课后据此给学生个性化指导。可跳过，之后不影响签到。",
                        color = cs.onSurfaceSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            KewuSectionTitle("本节资料")
            KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    AttachmentPicker(attachments = attachments, enabled = !busy, onToast = onToast)
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        note, { note = it }, Modifier.fillMaxWidth(),
                        backgroundMode = TextFieldMode.Rectangle,
                        label = "本节重点 / 想让 AI 重点关注的内容（选填）",
                        minLines = 2, enabled = !busy,
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp))
            }
            if (busy && stage.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(stage, color = KewuBlue, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp))
            }

            Spacer(Modifier.height(18.dp))
            val hasMaterial = attachments.isNotEmpty() || note.isNotBlank()
            KewuPrimaryButton(
                text = if (hasMaterial) "上传资料并开始签到" else "先选择资料，或点下方跳过",
                onClick = { uploadThenStart() },
                enabled = hasMaterial && !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            KewuSecondaryButton(
                text = "跳过，直接开始签到",
                onClick = { skipThenStart() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}
