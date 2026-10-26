package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiClient
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.ScheduleEntry
import com.kewu.app.haptic
import com.kewu.app.pickFiles
import com.kewu.app.platformReadFile
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 导入课表（2026-10-26）：老师建班后进入，也可从班级详情再次进入更新。三种方式：
 *   ① 文件导入：CSV（Excel「另存为 CSV UTF-8」）/ TXT，页面上直接展示模板格式与示例，可一键复制模板；
 *   ② AI 识别：用大白话描述「周一上午 8 点到 9 点 40 在 A101 上高数，张伟老师…」，小悟整理成课表；
 *   ③ 手动添加：一节一节填。
 * 三种方式都先进入「预览」，可逐条修改 / 删除，确认后才导入。每节课必须有：课程、任课老师、星期、起止时间；地点、周次可选。
 * 任课老师按「姓名 + 学校」自动绑定已注册的老师；没注册的老师之后用同名同校注册时自动认领。
 */
@Composable
internal fun ScheduleImportScreen(
    classId: String?,
    className: String,
    defaultCourse: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    val clipboard = LocalClipboardManager.current
    val pageBackdrop = rememberGlassBackdrop()

    var mode by remember { mutableStateOf(0) } // 0 文件 1 AI 2 手动
    val entries = remember { mutableStateListOf<ScheduleEntry>() }
    var aiText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val teacherName = KewuApi.displayName("")

    fun addParsed(list: List<ScheduleEntry>, source: String) {
        if (list.isEmpty()) {
            error = "没有识别出课程，请检查格式后重试"
            return
        }
        entries.addAll(list)
        error = null
        scope.launch { snackbar.toast("$source 识别出 ${list.size} 节课，请在下方核对") }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
            KewuTopBar("导入课表", onBack = { if (!busy) onBack() })
            Spacer(Modifier.height(4.dp))
            KewuGlassCard(
                backdrop = pageBackdrop, shape = RoundedCornerShape(22.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(className.ifBlank { "我的课表" }, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "导入后学生在「课表」里直接看到每节课的时间、地点和任课老师；任课老师按姓名 + 学校自动绑定到他的账号，" +
                            "还没注册的老师以后用同名同校注册会自动认领。",
                        color = cs.onSurfaceSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("文件导入", "AI 识别", "手动添加").forEachIndexed { i, t ->
                    Chip(selected = mode == i, onClick = { haptic(HapticKind.Tick); mode = i; error = null }, label = t)
                }
            }
            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(90)))
                        .using(SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.86f, stiffness = 380f) })
                },
                label = "importMode",
            ) { m ->
                Column(Modifier.fillMaxWidth()) {
                    when (m) {
                        0 -> {
                            KewuSectionTitle("文件格式（CSV，第一行为表头）")
                            KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Box(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                            .background(cs.onSurface.copy(alpha = 0.05f))
                                            .horizontalScroll(rememberScrollState()).padding(12.dp),
                                    ) {
                                        Text(CSV_TEMPLATE, color = cs.onSurface, fontSize = 12.5.sp,
                                            fontFamily = FontFamily.Monospace, lineHeight = 19.sp)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "· 星期：周一…周日 或 1–7；时间：08:00；周次可写 1-16、1-8,10-16，留空表示每周\n" +
                                            "· Excel 里按上面的列填好后「另存为 → CSV UTF-8」，传到手机再选择即可",
                                        color = cs.onSurfaceSecondary, fontSize = 12.5.sp, lineHeight = 18.sp,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        KewuSecondaryButton("复制模板", {
                                            clipboard.setText(AnnotatedString(CSV_TEMPLATE))
                                            scope.launch { snackbar.toast("模板已复制") }
                                        }, Modifier.weight(1f))
                                        KewuPrimaryButton("选择文件", {
                                            pickFiles(images = false) { files ->
                                                val f = files.firstOrNull() ?: return@pickFiles
                                                val bytes = platformReadFile(f.path)
                                                if (bytes == null) { error = "读取文件失败"; return@pickFiles }
                                                val text = bytes.decodeToString().removePrefix("﻿")
                                                if (text.contains('�')) {
                                                    error = "文件不是 UTF-8 编码：请在 Excel 里选「CSV UTF-8」重新保存"
                                                    return@pickFiles
                                                }
                                                addParsed(parseScheduleCsv(text, teacherName), "文件")
                                            }
                                        }, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        1 -> {
                            KewuSectionTitle("用一句话描述课表，小悟帮你整理")
                            KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    TextField(
                                        aiText, { aiText = it }, Modifier.fillMaxWidth(),
                                        backgroundMode = TextFieldMode.Rectangle, minLines = 4, enabled = !busy,
                                        label = "例：周一 8:00-9:40 在教学楼 A101 上高等数学，张伟老师；周三下午 2 点到 3 点 40 在实验楼 305 上 Kotlin 程序设计，是我上；1 到 16 周",
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    KewuPrimaryButton(
                                        if (busy) "小悟正在整理…" else "让 AI 识别成课表",
                                        {
                                            if (aiText.isBlank() || busy) return@KewuPrimaryButton
                                            busy = true
                                            error = null
                                            scope.launch {
                                                val raw = AiClient.chat(
                                                    system = AI_SCHEDULE_PROMPT + "\n默认任课老师（描述里说「我」时用它）：" +
                                                        teacherName.ifBlank { "当前老师" } + "\n默认课程名：" + defaultCourse,
                                                    user = aiText.trim(),
                                                    maxTokens = 4096,
                                                )
                                                busy = false
                                                if (raw == null) error = "AI 暂时连不上，请稍后重试或改用文件 / 手动方式"
                                                else addParsed(parseScheduleJson(raw, teacherName), "AI")
                                            }
                                        },
                                        Modifier.fillMaxWidth(),
                                        enabled = aiText.isNotBlank() && !busy,
                                    )
                                }
                            }
                        }
                        else -> {
                            KewuSectionTitle("手动添加")
                            KewuSecondaryButton("＋ 添加一节课", {
                                entries.add(ScheduleEntry(courseName = defaultCourse, teacherName = teacherName, weekday = 1, startTime = "08:00", endTime = "09:40"))
                            }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp))
            }

            Spacer(Modifier.height(14.dp))
            KewuSectionTitle("预览（${entries.size} 节，可直接修改）")
            if (entries.isEmpty()) {
                KewuEmpty("还没有课程，用上面任一方式添加")
            } else {
                entries.forEachIndexed { i, e ->
                    ScheduleEditCard(e, onChange = { entries[i] = it }, onDelete = { entries.removeAt(i) })
                }
            }

            Spacer(Modifier.height(16.dp))
            KewuPrimaryButton(
                text = if (busy) "正在导入…" else "导入 ${entries.size} 节课",
                onClick = {
                    if (busy || entries.isEmpty()) return@KewuPrimaryButton
                    val bad = entries.indexOfFirst { it.courseName.isBlank() || it.startTime.isBlank() || it.endTime.isBlank() }
                    if (bad >= 0) { error = "第 ${bad + 1} 节缺少课程名或时间"; return@KewuPrimaryButton }
                    busy = true
                    scope.launch {
                        val (res, err) = KewuApi.importSchedule(classId, entries.toList())
                        busy = false
                        if (res != null) {
                            haptic(HapticKind.Light)
                            snackbar.toast(res)
                            onDone()
                        } else error = err ?: "导入失败"
                    }
                },
                enabled = entries.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            KewuSecondaryButton("稍后再导入", { if (!busy) onBack() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun ScheduleEditCard(e: ScheduleEntry, onChange: (ScheduleEntry) -> Unit, onDelete: () -> Unit) {
    val cs = COUITheme.colorScheme
    KewuCardGroup(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${WEEK_NAMES[e.weekday - 1]} ${e.startTime}–${e.endTime}", color = KewuBlue, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(e.location.ifBlank { "未填地点" }, color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                (1..7).forEach { d ->
                    Chip(selected = e.weekday == d, onClick = { onChange(e.copy(weekday = d)) }, label = WEEK_NAMES[d - 1])
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(e.courseName, { onChange(e.copy(courseName = it)) }, Modifier.weight(1f),
                    backgroundMode = TextFieldMode.Rectangle, label = "课程", singleLine = true)
                TextField(e.teacherName, { onChange(e.copy(teacherName = it)) }, Modifier.weight(1f),
                    backgroundMode = TextFieldMode.Rectangle, label = "任课老师", singleLine = true)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(e.startTime, { onChange(e.copy(startTime = it)) }, Modifier.weight(1f),
                    backgroundMode = TextFieldMode.Rectangle, label = "开始 08:00", singleLine = true)
                TextField(e.endTime, { onChange(e.copy(endTime = it)) }, Modifier.weight(1f),
                    backgroundMode = TextFieldMode.Rectangle, label = "结束 09:40", singleLine = true)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(e.location, { onChange(e.copy(location = it)) }, Modifier.weight(1.4f),
                    backgroundMode = TextFieldMode.Rectangle, label = "上课地点", singleLine = true)
                TextField(e.weeks, { onChange(e.copy(weeks = it)) }, Modifier.weight(1f),
                    backgroundMode = TextFieldMode.Rectangle, label = "周次 1-16", singleLine = true)
            }
            Spacer(Modifier.height(10.dp))
            KewuSecondaryButton("删除这节课", onDelete, Modifier.fillMaxWidth())
        }
    }
}

internal val WEEK_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

internal const val CSV_TEMPLATE = """课程名称,任课老师,星期,开始时间,结束时间,上课地点,周次
高等数学,张伟,周一,08:00,09:40,教学楼A101,1-16
Kotlin程序设计,李娜,周三,14:00,15:40,实验楼305,1-16
大学英语,王芳,周五,10:00,11:40,外语楼203,1-8,10-16"""

private const val AI_SCHEDULE_PROMPT = "你是课表整理助手。把用户描述的上课安排整理成 JSON 数组，只输出 JSON，不要任何解释或代码块标记。" +
    "每个元素：{\"courseName\":课程名,\"teacherName\":任课老师姓名,\"weekday\":1到7(周一=1),\"startTime\":\"HH:mm\",\"endTime\":\"HH:mm\",\"location\":上课地点,\"weeks\":周次如\"1-16\"或空串}。" +
    "上午 8 点写 08:00，下午 2 点写 14:00；没说结束时间的按一节课 45 分钟、两节连上 100 分钟推断；没说地点留空串。"

private fun weekdayOf(s: String): Int? {
    val t = s.trim()
    t.toIntOrNull()?.let { if (it in 1..7) return it }
    val names = listOf("一", "二", "三", "四", "五", "六", "日")
    names.forEachIndexed { i, n -> if (t.endsWith(n) || t.endsWith(if (n == "日") "天" else n)) return i + 1 }
    return null
}

private fun normTime(s: String): String {
    val t = s.trim().replace("：", ":")
    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t) ?: return t
    return m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2]
}

/** CSV：课程名称,任课老师,星期,开始时间,结束时间,上课地点,周次（周次里可以再带逗号，算作最后一列）。 */
internal fun parseScheduleCsv(text: String, defaultTeacher: String): List<ScheduleEntry> =
    text.lines().map { it.trim() }.filter { it.isNotBlank() }
        .filterNot { it.startsWith("课程") }
        .mapNotNull { line ->
            val c = line.split(',', '，').map { it.trim() }
            if (c.size < 5) return@mapNotNull null
            val wd = weekdayOf(c[2]) ?: return@mapNotNull null
            ScheduleEntry(
                courseName = c[0], teacherName = c[1].ifBlank { defaultTeacher }, weekday = wd,
                startTime = normTime(c[3]), endTime = normTime(c[4]),
                location = c.getOrElse(5) { "" }, weeks = c.drop(6).joinToString(","),
            )
        }

/** AI 输出的 JSON 数组（容忍前后夹杂文字 / 代码块标记）。 */
internal fun parseScheduleJson(raw: String, defaultTeacher: String): List<ScheduleEntry> {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val arr = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonArray }.getOrNull() ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.content }.getOrNull().orEmpty().let { if (it == "null") "" else it }
        val wd = weekdayOf(s("weekday")) ?: return@mapNotNull null
        ScheduleEntry(
            courseName = s("courseName"), teacherName = s("teacherName").ifBlank { defaultTeacher }, weekday = wd,
            startTime = normTime(s("startTime")), endTime = normTime(s("endTime")),
            location = s("location"), weeks = s("weeks"),
        )
    }.filter { it.courseName.isNotBlank() }
}
