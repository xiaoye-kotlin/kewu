package com.kewu.admin.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.admin.AdminApi
import com.kewu.admin.AdminPrefetch
import com.kewu.admin.PageResult
import com.kewu.admin.str
import com.kewu.admin.long
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * 课堂纪要（§83）：集中查看「正在生成」与「已生成完毕」的所有课堂纪要。
 * 数据源 /api/admin/minutes（class_recordings + Redis 实时状态）。
 */
@Composable
fun MinutesPage(api: AdminApi, notify: (String) -> Unit) {
    var result by remember { mutableStateOf(AdminPrefetch.minutes.peek() ?: PageResult(emptyList(), 0, 1, 50)) }
    var loading by remember { mutableStateOf(AdminPrefetch.minutes.peek() == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyword by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var detail by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    /** §87：待确认删除的纪要（null = 无）。 */
    var deleting by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload(silent: Boolean = false) {
        if (!silent) loading = true
        if (!silent) error = null
        try {
            val fresh = api.page(
                "/api/admin/minutes",
                mapOf(
                    "page" to page.toString(),
                    "size" to "50",
                    "keyword" to keyword.ifBlank { null },
                    "status" to status.ifBlank { null },
                ),
            )
            result = fresh
            if (keyword.isBlank() && status.isBlank() && page == 1) AdminPrefetch.minutes.set(fresh)
            // 详情弹窗开着时按 sessionId 换成新对象，生成中的进度会跟着走
            detail = detail?.let { old -> fresh.items.firstOrNull { it.str("sessionId") == old.str("sessionId") } ?: old }
        } catch (e: Exception) {
            if (!silent) error = e.message ?: "课堂纪要加载失败"
        } finally {
            if (!silent) loading = false
        }
    }

    LaunchedEffect(Unit) {
        AdminPrefetch.awaitReady()
        val cached = AdminPrefetch.minutes.get()
        if (cached != null) {
            result = cached
            loading = false
        } else {
            reload()
        }
    }
    LaunchedEffect(status) { page = 1; reload() }

    // §87：生成中的纪要坚持每 15 秒静默刷新，进度条与状态会自己走；空闲时也不停止，及时发现新纪要
    PollEffect(15_000, key = Triple(page, status, keyword)) { reload(silent = true) }

    val generating = result.items.count { it.str("displayStatus", "PENDING") == "GENERATING" }
    val done = result.items.count { it.str("displayStatus", "PENDING") == "DONE" }

    Column(Modifier.fillMaxSize()) {
        PageHeader("课堂纪要", "查看正在生成与已生成完毕的课堂纪要（含 AI 生成进度与正文）") {
            StatusPill("生成中 $generating", if (generating > 0) StatusTone.INFO else StatusTone.NEUTRAL)
            StatusPill("已完成 $done", StatusTone.SUCCESS)
            OutlinedButton(onClick = { scope.launch { reload() } }) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("刷新")
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(keyword, { keyword = it }, "搜索课程 / 教师 / 纪要内容", Modifier.weight(1f))
            OptionSelector(
                status,
                listOf("" to "全部状态", "GENERATING" to "生成中", "DONE" to "已完成", "FAILED" to "失败", "PENDING" to "待开始"),
            ) { status = it }
            Button(onClick = { page = 1; scope.launch { reload() } }, modifier = Modifier.height(48.dp)) { Text("查询") }
        }

        when {
            loading && result.items.isEmpty() -> LoadingBlock("正在读取课堂纪要…")
            error != null && result.items.isEmpty() -> ErrorBlock(error ?: "加载失败") { scope.launch { reload() } }
            result.items.isEmpty() -> EmptyBlock("还没有课堂纪要。上完课后系统会自动转写并生成纪要。")
            else -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                result.items.forEach { item ->
                    MinuteRow(item, onClick = { detail = item }, onDelete = { deleting = item })
                }
            }
        }
        PaginationBar(result.page, result.size, result.total) { page = it }
    }

    detail?.let { item ->
        MinuteDetailDialog(item, onClose = { detail = null })
    }

    // §87 纪要删除：二次确认，删除后立即静默刷新列表（不动筛选与分页）
    deleting?.let { target ->
        val name = target.str("courseName").ifBlank { target.str("sessionId") }
        ConfirmDialog(
            "删除课堂纪要",
            "确定删除「$name」的课堂纪要吗？删除后该节次的纪要内容与生成进度都不可恢复（原始录音文件保留）。",
            "确认删除",
            true,
            onConfirm = {
                if (working) return@ConfirmDialog
                working = true
                scope.launch {
                    try {
                        api.remove("/api/admin/minutes/${target.str("sessionId")}")
                        notify("课堂纪要已删除")
                        deleting = null
                        detail = null
                        reload(silent = true)
                    } catch (e: Exception) {
                        notify(e.message ?: "删除失败")
                    } finally {
                        working = false
                    }
                }
            },
            onDismiss = { if (!working) deleting = null },
        )
    }
}

@Composable
private fun MinuteRow(
    item: kotlinx.serialization.json.JsonObject,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val display = item.str("displayStatus", "PENDING")
    val (label, tone) = when (display) {
        "GENERATING" -> "生成中" to StatusTone.INFO
        "DONE" -> "已完成" to StatusTone.SUCCESS
        "FAILED" -> "失败" to StatusTone.ERROR
        else -> "待开始" to StatusTone.NEUTRAL
    }
    val progress = item.long("progress", -1).toInt().takeIf { it in 0..100 }
    val summary = item.str("summary")
    Surface(
        color = cs.surfaceContainer,
        contentColor = cs.onSurface,
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).background(KewuBlue.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Article, null, tint = KewuBlue, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.str("courseName").ifBlank { "未命名课程" },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        buildString {
                            item.str("teacher").takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                            item.str("sessionId").takeIf { it.isNotBlank() }?.let { append("课时 ").append(it) }
                            item.str("decidedAt").takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        }.ifBlank { "—" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                if (display == "GENERATING") {
                    StatusPill(progress?.let { "生成中 $it%" } ?: "生成中", StatusTone.INFO)
                } else {
                    StatusPill(label, tone)
                }
                // §87 纪要管理：逐条删除（二次确认后调 DELETE /api/admin/minutes/{sessionId}）
                Spacer(Modifier.width(8.dp))
                DeleteMinuteButton(onDelete)
            }
            val stage = item.str("stage")
            if (stage.isNotBlank() || progress != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stage.ifBlank { "进度" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    if (progress != null) {
                        Box(
                            Modifier.weight(1f).height(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f), RoundedCornerShape(50)),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(progress / 100f).height(6.dp).background(KewuBlue, RoundedCornerShape(50)),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("$progress%", color = KewuBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    summary.lineSequence().firstOrNull()?.take(120) ?: "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 2,
                )
            }
            val asrError = item.str("asrError")
            if (asrError.isNotBlank() && display == "FAILED") {
                Spacer(Modifier.height(8.dp))
                Text(asrError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 2)
            }
        }
    }
}

/** 纪要行内的「删除」按钮：小尺寸 coui 文本按钮，点按不做整卡跳转。 */
@Composable
private fun DeleteMinuteButton(onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    Surface(
        color = AdminRed.copy(alpha = 0.10f),
        contentColor = AdminRed,
        shape = RoundedCornerShape(50),
        onClick = onClick,
    ) {
        Text(
            "删除",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MinuteDetailDialog(item: kotlinx.serialization.json.JsonObject, onClose: () -> Unit) {
    val cs = COUITheme.colorScheme
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column {
                Text(item.str("courseName").ifBlank { "课堂纪要" }, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        item.str("teacher").takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                        append("课时 ").append(item.str("sessionId"))
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val display = item.str("displayStatus", "PENDING")
                    StatusPill(
                        when (display) {
                            "GENERATING" -> "生成中"
                            "DONE" -> "已完成"
                            "FAILED" -> "失败"
                            else -> "待开始"
                        },
                        when (display) {
                            "GENERATING" -> StatusTone.INFO
                            "DONE" -> StatusTone.SUCCESS
                            "FAILED" -> StatusTone.ERROR
                            else -> StatusTone.NEUTRAL
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill("转写 ${item.str("transcriptStatus").ifBlank { "—" }}", StatusTone.NEUTRAL)
                    Spacer(Modifier.width(8.dp))
                    item.long("durationSec", 0).toInt().takeIf { it > 0 }?.let {
                        StatusPill("时长 ${it / 60} 分 ${it % 60} 秒", StatusTone.NEUTRAL)
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
                Spacer(Modifier.height(14.dp))
                val summary = item.str("summary")
                if (summary.isBlank()) {
                    Text("纪要尚未生成，当前阶段：${item.str("stage").ifBlank { "等待处理" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(summary, fontSize = 13.sp)
                }
                item.str("asrError").takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("转写错误：$it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose) { Text("关闭") }
            }
        },
    )
}
