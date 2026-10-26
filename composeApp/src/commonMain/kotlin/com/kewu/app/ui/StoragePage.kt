package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AssistantHub
import com.kewu.app.BackHandler
import com.kewu.app.ChatHistory
import com.kewu.app.HapticKind
import com.kewu.app.KewuGreen
import com.kewu.app.KewuSky
import com.kewu.app.StorageManager
import com.kewu.app.StorageManager.Category
import com.kewu.app.haptic
import com.kewu.app.shareText
import com.kewu.app.todayCn
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.icon.extended.File
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.icon.extended.Layers
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Messages
import io.github.suqi8.coui.kmp.icon.extended.Recording
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

// ───────────────────────── 我的 → 内存管理（2026-10-26） ─────────────────────────
//
// 两层：总览（占用分布 + 按类清理 + 一键清理缓存）→ AI 聊天记录（按天浏览 / 搜索 / 多选删除 / 快捷保留）。
// 风格与「编辑资料」同源：本页自带「品牌极光」采样底，液态玻璃只用在头部概览卡与浮动操作条，
// 其余全是 coui 分组卡 / 按钮 / 复选框 / 输入框。玻璃与采样层平级，入场只做位移（采样者禁 alpha/scale）。
// 数据层见 [StorageManager]；聊天记录删除走 [ChatHistory.deleteWhere]（真删，不是 upsert）。

private val CatChat = KewuSky
private val CatImage = Color(0xFF12B076)
private val CatAttach = Color(0xFFFF8A3D)
private val CatRec = Color(0xFFE5484D)
private val CatOther = Color(0xFF8A63F2)
private val CatPending = Color(0xFF7A8699)

/** 一键清理覆盖的类别（聊天记录要按天管理，不进一键清理）。 */
private val QuickCats = listOf(Category.IMAGE, Category.ATTACH, Category.REC_TEMP, Category.OTHER)

private fun Category.color() = when (this) {
    Category.CHAT -> CatChat
    Category.IMAGE -> CatImage
    Category.ATTACH -> CatAttach
    Category.REC_TEMP -> CatRec
    Category.OTHER -> CatOther
}

private fun Category.title() = when (this) {
    Category.CHAT -> "AI 聊天记录"
    Category.IMAGE -> "图片缓存"
    Category.ATTACH -> "附件临时文件"
    Category.REC_TEMP -> "录音残留"
    Category.OTHER -> "其他缓存"
}

private fun Category.icon(): ImageVector = when (this) {
    Category.CHAT -> COUIIcons.Messages
    Category.IMAGE -> COUIIcons.Image
    Category.ATTACH -> COUIIcons.File
    Category.REC_TEMP -> COUIIcons.Recording
    Category.OTHER -> COUIIcons.Layers
}

/** 页面入口：总览 ⇄ 聊天记录（Telegram 式推入，聊天记录页可右滑返回）。 */
@Composable
internal fun StorageManagePage(onBack: () -> Unit) {
    var sub by remember { mutableStateOf(0) }
    BackHandler(enabled = sub != 0) { sub = 0 }
    KewuSlideHost(
        targetState = sub,
        modifier = Modifier.fillMaxSize(),
        isForward = { a, b -> b > a },
        swipeEnabled = sub == 1,
        swipeNeighbor = { from, d -> if (d < 0 && from == 1) 0 else null },
        onSwipeCommit = { sub = it },
    ) { s ->
        if (s == 0) StorageOverview(onBack = onBack, onOpenChat = { sub = 1 })
        else ChatHistoryManager(onBack = { sub = 0 })
    }
}

/** 本页采样层：coui 灰底 + 三团品牌柔光（静态绘制，玻璃不必每帧重模糊）。 */
@Composable
private fun AuroraLayer(backdrop: LayerBackdrop, modifier: Modifier) {
    val bg = COUITheme.colorScheme.surface
    Box(
        modifier
            .layerBackdrop(backdrop)
            .drawBehind {
                drawRect(bg)
                val w = size.width
                fun glow(color: Color, cx: Float, cy: Float, r: Float) {
                    val c = Offset(cx, cy)
                    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = c, radius = r), radius = r, center = c)
                }
                glow(CatChat.copy(alpha = 0.50f), w * 0.10f, 80.dp.toPx(), w * 0.62f)
                glow(CatImage.copy(alpha = 0.36f), w * 0.95f, 160.dp.toPx(), w * 0.55f)
                glow(CatOther.copy(alpha = 0.28f), w * 0.50f, 270.dp.toPx(), w * 0.45f)
            },
    )
}

// ───────────────────────── 总览 ─────────────────────────

@Composable
private fun StorageOverview(onBack: () -> Unit, onOpenChat: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    val backdrop = rememberGlassBackdrop()
    var report by remember { mutableStateOf<StorageManager.StorageReport?>(null) }
    var busy by remember { mutableStateOf(setOf<Category>()) }
    var confirm by remember { mutableStateOf<Category?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { report = StorageManager.scan() }

    fun clean(cats: List<Category>) {
        busy = busy + cats
        scope.launch {
            var freed = 0L
            cats.forEach { freed += StorageManager.clean(it) }
            report = StorageManager.scan()
            busy = busy - cats.toSet()
            haptic(HapticKind.Light)
            snackbar.toast(if (freed > 0) "已释放 ${fmtBytes(freed)}" else "已清理完成")
        }
    }

    Box(Modifier.fillMaxSize()) {
        AuroraLayer(backdrop, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            KewuTopBar("内存管理", onBack = onBack)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 128.dp)) {
                Spacer(Modifier.height(6.dp))
                val r = report
                // ── 液态玻璃概览：总占用（数字滚动）+ 分段占用条 + 图例 ──
                val heroP = rememberEnterProgress(0, baseDelayMs = 80L)
                KewuGlassCard(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(26.dp),
                    blurRadius = 24.dp,
                    tintAlpha = 0.34f * heroP.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 14.dp)
                        .glassEnter(heroP, 40f),
                ) {
                    StorageHero(r)
                }

                GroupLabel("可清理", Modifier.riseEnter(rememberEnterProgress(1, baseDelayMs = 80L)))
                KewuCardGroup(Modifier.riseEnter(rememberEnterProgress(2, baseDelayMs = 80L))) {
                    val chat = r?.get(Category.CHAT)
                    StorageRow(
                        category = Category.CHAT,
                        summary = if (chat == null) "正在统计…" else "与小悟的对话 · ${chat.count} 条 · 可按天删除",
                        bytes = chat?.bytes,
                        approx = true,
                        onClick = { haptic(HapticKind.Tick); onOpenChat() },
                    )
                    QuickCats.forEach { c ->
                        StoreDivider()
                        val item = r?.get(c)
                        val desc = when (c) {
                            Category.IMAGE -> "查看过的作业图片与附件缩略图 · ${item?.count ?: 0} 张"
                            Category.ATTACH -> "选择的文件、作业语音的本机副本 · ${item?.count ?: 0} 个"
                            Category.REC_TEMP -> if (com.kewu.app.RecordingManager.status.value.let {
                                    it is com.kewu.app.RecordingStatus.Recording || it is com.kewu.app.RecordingStatus.Uploading
                                }) "正在录音 / 上传，结束后可清理" else "中断或已上传的录音临时文件 · ${item?.count ?: 0} 个"
                            else -> "系统临时文件与诊断日志 · ${item?.count ?: 0} 个"
                        }
                        StorageRow(
                            category = c,
                            summary = if (item == null) "正在统计…" else desc,
                            bytes = item?.bytes,
                            busy = c in busy,
                            onClean = if ((item?.bytes ?: 0L) > 0L) ({ confirm = c }) else null,
                        )
                    }
                }

                GroupLabel("受保护", Modifier.riseEnter(rememberEnterProgress(3, baseDelayMs = 80L)))
                KewuCardGroup(Modifier.riseEnter(rememberEnterProgress(4, baseDelayMs = 80L))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StoreBadge(COUIIcons.Lock, CatPending)
                        Column(Modifier.weight(1f)) {
                            Text("待重传录音", color = cs.onSurface, fontSize = 15.sp)
                            Text(
                                "还没上传成功的课堂录音（${r?.pendingRecCount ?: 0} 份），不会被清理；请到「录音管理」重传",
                                color = cs.onSurfaceVariantSummary, fontSize = 12.sp, lineHeight = 16.sp,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(r?.let { fmtBytes(it.pendingRecBytes) } ?: "—", color = cs.onSurfaceSecondary,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                val quickBytes = r?.let { rep -> QuickCats.sumOf { rep[it].bytes } } ?: 0L
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .riseEnter(rememberEnterProgress(5, baseDelayMs = 80L)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    KewuPrimaryButton(
                        text = if (QuickCats.any { it in busy }) "正在清理…"
                        else if (quickBytes > 0) "一键清理缓存 · ${fmtBytes(quickBytes)}" else "缓存已是最干净的状态",
                        onClick = { confirmAll = true },
                        enabled = quickBytes > 0 && QuickCats.none { it in busy },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "一键清理不包含聊天记录，聊天记录请进入「AI 聊天记录」按天管理",
                        color = cs.onSurfaceVariantSummary, fontSize = 11.5.sp,
                    )
                }
            }
        }
    }

    val target = confirm
    KewuConfirmDialog(
        show = target != null,
        title = "清理${target?.title() ?: ""}？",
        summary = when (target) {
            Category.IMAGE -> "下次查看图片会重新下载"
            Category.ATTACH -> "正在填写的作业里已经选好的附件需要重新选择"
            Category.REC_TEMP -> "只删除中断或已上传的录音临时文件，待重传的录音不受影响"
            else -> "清理后不影响账号数据与已上传内容"
        },
        confirmText = "清理",
        onConfirm = { target?.let { clean(listOf(it)) }; confirm = null },
        onDismiss = { confirm = null },
    )
    KewuConfirmDialog(
        show = confirmAll,
        title = "一键清理缓存？",
        summary = "将清理图片缓存、附件临时文件、录音残留和其他缓存。聊天记录与待重传录音不受影响",
        confirmText = "清理",
        onConfirm = { clean(QuickCats); confirmAll = false },
        onDismiss = { confirmAll = false },
    )
}

@Composable
private fun StorageHero(r: StorageManager.StorageReport?) {
    val cs = COUITheme.colorScheme
    val total = r?.total ?: 0L
    val shown by animateFloatAsState(total.toFloat(), spring(dampingRatio = 0.9f, stiffness = 120f), label = "storageTotal")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
        Text("课悟在本机占用", color = cs.onSurfaceSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        val (num, unit) = splitBytes(shown.toLong())
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (r == null) "—" else num, color = cs.onSurface, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = cs.onSurfaceSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        val clean = r?.let { rep -> QuickCats.sumOf { rep[it].bytes } } ?: 0L
        Text(
            if (r == null) "正在统计本机数据…" else "可一键清理 ${fmtBytes(clean)} · 待重传录音 ${fmtBytes(r.pendingRecBytes)} 受保护",
            color = cs.onSurfaceVariantSummary, fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        val segs = Category.entries.map { it.color() to (r?.get(it)?.bytes ?: 0L) } + (CatPending to (r?.pendingRecBytes ?: 0L))
        StorageBar(segs, Modifier.fillMaxWidth().height(14.dp))
        Spacer(Modifier.height(14.dp))
        // 图例：两列
        val legend = Category.entries.map { Triple(it.color(), it.title(), r?.get(it)?.bytes) } +
            Triple(CatPending, "待重传录音", r?.pendingRecBytes)
        legend.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                pair.forEach { (c, name, b) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(6.dp))
                        Text(name, color = cs.onSurfaceSecondary, fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        Text(b?.let { fmtBytes(it) } ?: "—", color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 分段占用条：每段宽度弹簧过渡（清理后各段平滑收缩），段间留细缝，整体胶囊裁切。 */
@Composable
private fun StorageBar(segments: List<Pair<Color, Long>>, modifier: Modifier) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1L)
    // 非零段至少 2%，否则几 KB 的类别在条上看不见
    val raw = segments.map { (_, b) -> if (b <= 0L) 0f else (b.toFloat() / total).coerceAtLeast(0.02f) }
    val norm = raw.sum().coerceAtLeast(1f)
    val fr = raw.mapIndexed { i, v ->
        animateFloatAsState(v / norm, spring(dampingRatio = 0.78f, stiffness = 220f), label = "seg$i").value
    }
    val track = COUITheme.colorScheme.onSurface.copy(alpha = 0.07f)
    Canvas(modifier) {
        val h = size.height
        val path = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, h, CornerRadius(h / 2f)))
        }
        clipPath(path) {
            drawRect(track)
            var x = 0f
            val gap = 2.dp.toPx()
            segments.forEachIndexed { i, (c, _) ->
                val w = fr[i].coerceAtLeast(0f) * size.width
                if (w > 0.5f) {
                    drawRect(c, topLeft = Offset(x, 0f), size = Size((w - gap).coerceAtLeast(1f), h))
                    x += w
                }
            }
        }
    }
}

@Composable
private fun StorageRow(
    category: Category,
    summary: String,
    bytes: Long?,
    approx: Boolean = false,
    busy: Boolean = false,
    onClean: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = COUITheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoreBadge(category.icon(), category.color())
        Column(Modifier.weight(1f)) {
            Text(category.title(), color = cs.onSurface, fontSize = 15.sp)
            Text(summary, color = cs.onSurfaceVariantSummary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            bytes?.let { (if (approx) "约 " else "") + fmtBytes(it) } ?: "—",
            color = cs.onSurfaceSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        when {
            onClick != null -> Icon(COUIIcons.ChevronForward, contentDescription = null,
                tint = cs.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
            else -> StoreSmallButton(if (busy) "清理中" else "清理", enabled = onClean != null && !busy) { onClean?.invoke() }
        }
    }
}

// ───────────────────────── AI 聊天记录 ─────────────────────────

private data class DayGroup(val day: Long, val entries: List<ChatHistory.Entry>)

/** 待确认的删除操作。 */
private sealed class ChatDelete {
    data class Days(val days: Set<Long>) : ChatDelete()
    data class KeepRecent(val n: Int) : ChatDelete()
    data object BeforeToday : ChatDelete()
    data object All : ChatDelete()
    data class One(val entry: ChatHistory.Entry) : ChatDelete()
}

@Composable
private fun ChatHistoryManager(onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val snackbar = LocalKewuSnackbar.current
    val scope = rememberCoroutineScope()
    val backdrop = rememberGlassBackdrop()
    val key = remember { StorageManager.chatKey() }
    var entries by remember { mutableStateOf(ChatHistory.loadAll(key)) }
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var pending by remember { mutableStateOf<ChatDelete?>(null) }
    BackHandler(enabled = selecting) { selecting = false; selected = emptySet() }

    val today = ChatHistory.dayStart(nowMs())
    val days = remember(entries) {
        entries.groupBy { ChatHistory.dayStart(it.ts) }.map { (d, l) -> DayGroup(d, l.sortedBy { it.ts }) }
            .sortedByDescending { it.day }
    }
    val q = query.trim()
    val shown = if (q.isEmpty()) days else days.filter { g -> g.entries.any { it.text.contains(q, ignoreCase = true) } }

    fun predicateOf(op: ChatDelete): (ChatHistory.Entry) -> Boolean = when (op) {
        is ChatDelete.Days -> { e -> ChatHistory.dayStart(e.ts) in op.days }
        is ChatDelete.KeepRecent -> { e -> ChatHistory.dayStart(e.ts) <= today - op.n * 86_400_000L }
        ChatDelete.BeforeToday -> { e -> ChatHistory.dayStart(e.ts) < today }
        ChatDelete.All -> { _ -> true }
        is ChatDelete.One -> { e -> e.id == op.entry.id && e.ts == op.entry.ts && e.text == op.entry.text }
    }

    fun perform(op: ChatDelete) {
        val n = ChatHistory.deleteWhere(key, predicateOf(op))
        entries = ChatHistory.loadAll(key)
        AssistantHub.refreshUnread(key)
        selected = emptySet()
        if (op is ChatDelete.Days || op is ChatDelete.All) selecting = false
        haptic(HapticKind.Light)
        scope.launch { snackbar.toast(if (n > 0) "已删除 $n 条聊天记录" else "没有符合条件的记录") }
    }

    Box(Modifier.fillMaxSize()) {
        AuroraLayer(backdrop, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            KewuTopBar("AI 聊天记录", onBack = onBack, actions = {
                if (days.isNotEmpty()) {
                    StoreSmallButton(if (selecting) "完成" else "选择", primary = !selecting,
                        modifier = Modifier.padding(end = 12.dp)) {
                        selecting = !selecting
                        if (!selecting) selected = emptySet()
                    }
                }
            })
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(bottom = if (selecting) 210.dp else 128.dp),
            ) {
                Spacer(Modifier.height(6.dp))
                // ── 液态玻璃概览：条数 / 天数 / 占用 / 最早日期 + 近 14 天活跃柱 ──
                val heroP = rememberEnterProgress(0, baseDelayMs = 80L)
                KewuGlassCard(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(26.dp),
                    blurRadius = 24.dp,
                    tintAlpha = 0.34f * heroP.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        .glassEnter(heroP, 40f),
                ) {
                    ChatHero(entries, days, today, heroP)
                }

                if (days.isEmpty()) {
                    KewuEmpty("还没有和小悟的聊天记录\n在「助教」页向小悟提问后，记录会按天出现在这里")
                } else {
                    // ── 快捷清理 ──
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp).padding(bottom = 10.dp)
                            .riseEnter(rememberEnterProgress(1, baseDelayMs = 80L)),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Chip(selected = false, onClick = { haptic(HapticKind.Tick); pending = ChatDelete.KeepRecent(7) }, label = "只保留近 7 天")
                        Chip(selected = false, onClick = { haptic(HapticKind.Tick); pending = ChatDelete.KeepRecent(30) }, label = "只保留近 30 天")
                        Chip(selected = false, onClick = { haptic(HapticKind.Tick); pending = ChatDelete.BeforeToday }, label = "删除今天以前")
                        Chip(selected = false, onClick = { haptic(HapticKind.Tick); pending = ChatDelete.All }, label = "清空全部")
                    }
                    // ── 搜索 ──
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp)
                            .riseEnter(rememberEnterProgress(2, baseDelayMs = 80L)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = query,
                            onValueChange = { query = it.take(40) },
                            modifier = Modifier.weight(1f),
                            backgroundMode = TextFieldMode.Rectangle,
                            label = "搜索聊天内容",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            showClearButton = true,
                            leadingIcon = {
                                Icon(COUIIcons.Search, contentDescription = null, tint = cs.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp).size(18.dp))
                            },
                        )
                    }
                    if (q.isNotEmpty()) {
                        val hits = shown.sumOf { g -> g.entries.count { it.text.contains(q, ignoreCase = true) } }
                        Text(
                            if (shown.isEmpty()) "没有找到包含「$q」的消息" else "在 ${shown.size} 天里找到 $hits 条相关消息",
                            color = cs.onSurfaceVariantSummary, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                    // ── 按月分组的日卡片 ──
                    var lastMonth = ""
                    shown.forEachIndexed { i, g ->
                        val date = todayCn(g.day + 1)
                        val month = date.substring(0, 7)
                        if (month != lastMonth) {
                            lastMonth = month
                            GroupLabel("${month.substring(0, 4)} 年 ${month.substring(5).trimStart('0')} 月")
                        }
                        DayCard(
                            group = g,
                            today = today,
                            query = q,
                            selecting = selecting,
                            checked = g.day in selected,
                            expanded = g.day in expanded || (q.isNotEmpty() && shown.size <= 3),
                            modifier = Modifier.riseEnter(rememberEnterProgress(3 + i.coerceAtMost(6), baseDelayMs = 80L)),
                            onToggleCheck = {
                                haptic(HapticKind.Tick)
                                selected = if (g.day in selected) selected - g.day else selected + g.day
                            },
                            onToggleExpand = {
                                expanded = if (g.day in expanded) expanded - g.day else expanded + g.day
                            },
                            onLongPress = {
                                haptic(HapticKind.Light)
                                selecting = true
                                selected = selected + g.day
                            },
                            onDeleteDay = { pending = ChatDelete.Days(setOf(g.day)) },
                            onDeleteOne = { pending = ChatDelete.One(it) },
                            onExport = {
                                haptic(HapticKind.Tick)
                                shareText(transcript(g))
                            },
                        )
                    }
                }
            }
        }

        // ── 多选浮动条（液态玻璃，采样极光层；只做位移进出场）──
        val barP by animateFloatAsState(
            if (selecting) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 420f), label = "selectBar",
        )
        if (barP > 0.001f) {
            val selCount = days.filter { it.day in selected }.sumOf { it.entries.size }
            KewuGlassCard(
                backdrop = backdrop,
                shape = RoundedCornerShape(24.dp),
                blurRadius = 22.dp,
                tintAlpha = 0.42f,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 118.dp)
                    .fillMaxWidth()
                    .offset { IntOffset(0, ((1f - barP) * 220f).dp.roundToPx()) },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("已选 ${selected.size} 天", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("共 $selCount 条消息", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                    }
                    val all = shown.isNotEmpty() && shown.all { it.day in selected }
                    StoreSmallButton(if (all) "取消全选" else "全选") {
                        selected = if (all) selected - shown.map { it.day }.toSet() else selected + shown.map { it.day }
                    }
                    Spacer(Modifier.width(8.dp))
                    StoreSmallButton("删除", danger = true, enabled = selected.isNotEmpty()) {
                        pending = ChatDelete.Days(selected)
                    }
                }
            }
        }
    }

    val op = pending
    val affected = op?.let { o -> entries.count(predicateOf(o)) } ?: 0
    KewuConfirmDialog(
        show = op != null,
        title = when (op) {
            is ChatDelete.Days -> if (op.days.size == 1) "删除 ${dayTitle(op.days.first(), today)} 的记录？" else "删除所选 ${op.days.size} 天的记录？"
            is ChatDelete.KeepRecent -> "只保留最近 ${op.n} 天？"
            ChatDelete.BeforeToday -> "删除今天以前的记录？"
            ChatDelete.All -> "清空全部聊天记录？"
            is ChatDelete.One -> "删除这条消息？"
            null -> ""
        },
        summary = if (op is ChatDelete.One) "「${op.entry.text.take(40)}${if (op.entry.text.length > 40) "…" else ""}」"
        else "将删除 $affected 条消息，删除后无法恢复（只删本机记录，不影响小悟的回答能力）",
        confirmText = "删除",
        onConfirm = { op?.let { perform(it) }; pending = null },
        onDismiss = { pending = null },
    )
}

@Composable
private fun ChatHero(entries: List<ChatHistory.Entry>, days: List<DayGroup>, today: Long, p: Float) {
    val cs = COUITheme.colorScheme
    val bytes = remember(entries) { entries.sumOf { 64L + it.text.encodeToByteArray().size + (it.quote?.encodeToByteArray()?.size ?: 0) } }
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${entries.size}", color = cs.onSurface, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text("条对话", color = cs.onSurfaceSecondary, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("约 ${fmtBytes(bytes)}", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${days.size} 天", color = cs.onSurfaceSecondary, fontSize = 12.sp)
            }
        }
        val mine = entries.count { it.role == "user" }
        Text(
            if (entries.isEmpty()) "暂无记录" else
                "我提问 $mine 次 · 小悟回复 ${entries.size - mine} 次 · 最早 ${dayTitle(days.last().day, today)}",
            color = cs.onSurfaceVariantSummary, fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))
        // 近 14 天活跃：柱高 = 当天消息数，蓝 = 我的提问，绿 = 小悟回复（堆叠）
        val counts = (13 downTo 0).map { back ->
            val d = today - back * 86_400_000L
            val list = days.firstOrNull { it.day == d }?.entries.orEmpty()
            list.count { it.role == "user" } to list.count { it.role != "user" }
        }
        val maxC = counts.maxOf { it.first + it.second }.coerceAtLeast(1)
        val grow = p.coerceIn(0f, 1.2f)
        val track = cs.onSurface.copy(alpha = 0.06f)
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val n = counts.size
            val gap = 5.dp.toPx()
            val bw = (size.width - gap * (n - 1)) / n
            val r = CornerRadius(bw / 2.5f)
            counts.forEachIndexed { i, (u, a) ->
                val x = i * (bw + gap)
                drawRoundRect(track, topLeft = Offset(x, 0f), size = Size(bw, size.height), cornerRadius = r)
                val total = u + a
                if (total > 0) {
                    val h = size.height * (total.toFloat() / maxC) * grow
                    val hu = h * u / total
                    drawRoundRect(KewuGreen, topLeft = Offset(x, size.height - h), size = Size(bw, h), cornerRadius = r)
                    if (hu > 0f) drawRoundRect(CatChat, topLeft = Offset(x, size.height - hu), size = Size(bw, hu), cornerRadius = r)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("14 天前", color = cs.onSurfaceVariantSummary, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(7.dp).clip(CircleShape).background(CatChat).align(Alignment.CenterVertically))
            Text(" 我  ", color = cs.onSurfaceVariantSummary, fontSize = 11.sp)
            Box(Modifier.size(7.dp).clip(CircleShape).background(KewuGreen).align(Alignment.CenterVertically))
            Text(" 小悟", color = cs.onSurfaceVariantSummary, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("今天", color = cs.onSurfaceVariantSummary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DayCard(
    group: DayGroup,
    today: Long,
    query: String,
    selecting: Boolean,
    checked: Boolean,
    expanded: Boolean,
    modifier: Modifier,
    onToggleCheck: () -> Unit,
    onToggleExpand: () -> Unit,
    onLongPress: () -> Unit,
    onDeleteDay: () -> Unit,
    onDeleteOne: (ChatHistory.Entry) -> Unit,
    onExport: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val date = todayCn(group.day + 1) // yyyy-MM-dd
    val mine = group.entries.count { it.role == "user" }
    val bytes = group.entries.sumOf { 64L + it.text.encodeToByteArray().size }
    val preview = (group.entries.firstOrNull { it.role == "user" } ?: group.entries.first()).text
        .replace('\n', ' ').trim()
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "dayArrow")
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(0.dp),
        colors = kewuCardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .pointerInput(selecting) {
                    detectTapGestures(
                        onTap = { if (selecting) onToggleCheck() else { haptic(HapticKind.Tick); onToggleExpand() } },
                        onLongPress = { if (!selecting) onLongPress() },
                    )
                }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(selecting, enter = fadeIn() + expandHorizontallyCompat(), exit = fadeOut() + shrinkHorizontallyCompat()) {
                Checkbox(
                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = onToggleCheck,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            // 日期块：大号日 + 星期
            Column(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(CatChat.copy(alpha = 0.10f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(date.substring(8).trimStart('0'), color = CatChat, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                Text(weekday(group.day), color = CatChat.copy(alpha = 0.8f), fontSize = 10.sp, lineHeight = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dayTitle(group.day, today), color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    relativeTag(group.day, today)?.let {
                        Spacer(Modifier.width(6.dp))
                        KewuTag(it, CatChat.copy(alpha = 0.12f), CatChat)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(highlight(preview, query, cs.primary), color = cs.onSurfaceSecondary, fontSize = 12.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text("我 $mine · 小悟 ${group.entries.size - mine} · 约 ${fmtBytes(bytes)}",
                    color = cs.onSurfaceVariantSummary, fontSize = 11.5.sp)
            }
            if (!selecting) {
                Icon(COUIIcons.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 6.dp).size(20.dp).rotate(arrow))
            }
        }
        AnimatedVisibility(
            visible = expanded && !selecting,
            enter = expandVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) + fadeIn(),
            exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                val list = if (query.isEmpty()) group.entries else group.entries.filter { it.text.contains(query, true) }
                val cap = 40
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.take(cap).forEach { e -> MiniBubble(e, query) { onDeleteOne(e) } }
                    if (list.size > cap) {
                        Text("仅预览前 $cap 条，完整对话请在「助教」页查看", color = cs.onSurfaceVariantSummary,
                            fontSize = 11.5.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                    Text("长按某条消息可单独删除", color = cs.onSurfaceVariantSummary, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    StoreSmallButton("导出这一天") { onExport() }
                    StoreSmallButton("删除这一天", danger = true) { onDeleteDay() }
                }
            }
        }
    }
}

/** 预览气泡：我（右、品牌蓝浅底）/ 小悟（左、灰底）；长按删除单条。 */
@Composable
private fun MiniBubble(e: ChatHistory.Entry, query: String, onLongPress: () -> Unit) {
    val cs = COUITheme.colorScheme
    val mine = e.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (mine) 14.dp else 4.dp, bottomEnd = if (mine) 4.dp else 14.dp,
                ))
                .background(if (mine) CatChat.copy(alpha = 0.12f) else cs.onSurface.copy(alpha = 0.05f))
                .pointerInput(e.id) { detectTapGestures(onLongPress = { haptic(HapticKind.Light); onLongPress() }) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            e.quote?.takeIf { it.isNotBlank() }?.let {
                Text("「${it.take(30)}」", color = cs.onSurfaceVariantSummary, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Text(highlight(e.text.trim(), query, cs.primary), color = cs.onSurface, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text((if (mine) "我 · " else "小悟 · ") + clock(e.ts), color = cs.onSurfaceVariantSummary, fontSize = 10.sp)
        }
    }
}

// ───────────────────────── 小组件 ─────────────────────────

/** 行首彩色图标底（ColorOS 设置页同款，与编辑资料页一致）。 */
@Composable
private fun StoreBadge(icon: ImageVector, tint: Color) {
    Box(
        Modifier.padding(end = 12.dp).size(34.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun StoreDivider() {
    HorizontalDivider(Modifier.padding(start = 62.dp, end = 16.dp))
}

@Composable
private fun StoreSmallButton(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    TextButton(
        text = text,
        onClick = { haptic(HapticKind.Light); onClick() },
        modifier = modifier,
        enabled = enabled,
        minWidth = ButtonDefaults.MinWidthSmall,
        minHeight = ButtonDefaults.MinHeightSmall,
        cornerRadius = ButtonDefaults.CornerRadiusSmall,
        insideMargin = ButtonDefaults.InsideMarginSmall,
        colors = when {
            danger -> ButtonDefaults.textButtonColorsPrimary(color = cs.error, disabledColor = cs.error.copy(alpha = 0.3f))
            primary -> ButtonDefaults.textButtonColorsPrimary()
            else -> ButtonDefaults.textButtonColors()
        },
        textStyle = COUITheme.textStyles.button.copy(fontSize = 13.sp),
    )
}

private fun expandHorizontallyCompat() = androidx.compose.animation.expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 500f))
private fun shrinkHorizontallyCompat() = androidx.compose.animation.shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 500f))

// ───────────────────────── 格式化（commonMain 无 String.format，手写；东八区） ─────────────────────────

private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private fun one(v: Double): String {
    val t = kotlin.math.round(v * 10).toLong()
    return "${t / 10}.${t % 10}"
}

internal fun fmtBytes(b: Long): String = when {
    b < 1024L -> "$b B"
    b < 1_048_576L -> "${one(b / 1024.0)} KB"
    b < 1_073_741_824L -> "${one(b / 1_048_576.0)} MB"
    else -> "${one(b / 1_073_741_824.0)} GB"
}

/** 拆成「数字 / 单位」两段，概览大字用。 */
private fun splitBytes(b: Long): Pair<String, String> = fmtBytes(b).split(' ').let { it[0] to it.getOrElse(1) { "" } }

private fun two(v: Long) = if (v < 10) "0$v" else "$v"

private fun clock(ts: Long): String {
    val m = ((ts + 8 * 3_600_000L).mod(86_400_000L)) / 60_000L
    return "${two(m / 60)}:${two(m % 60)}"
}

/** 「10 月 26 日」。 */
private fun dayTitle(day: Long, today: Long): String {
    val d = todayCn(day + 1)
    val md = "${d.substring(5, 7).trimStart('0')} 月 ${d.substring(8).trimStart('0')} 日"
    return if (d.substring(0, 4) == todayCn(today + 1).substring(0, 4)) md else "${d.substring(0, 4)} 年 $md"
}

private fun relativeTag(day: Long, today: Long): String? = when ((today - day) / 86_400_000L) {
    0L -> "今天"
    1L -> "昨天"
    2L -> "前天"
    else -> null
}

/** 东八区星期（1970-01-01 是周四）。 */
private fun weekday(day: Long): String {
    val d = (day + 8 * 3_600_000L) / 86_400_000L
    return listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[((d + 3).mod(7L)).toInt()]
}

private fun highlight(text: String, q: String, color: Color): AnnotatedString {
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(q, i, ignoreCase = true)
            if (hit < 0) { append(text.substring(i)); break }
            append(text.substring(i, hit))
            pushStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold, background = color.copy(alpha = 0.12f)))
            append(text.substring(hit, hit + q.length))
            pop()
            i = hit + q.length
        }
    }
}

/** 导出一天的对话为纯文本（系统分享）。 */
private fun transcript(g: DayGroup): String = buildString {
    appendLine("课悟 · 与小悟的对话（${todayCn(g.day + 1)}）")
    appendLine()
    g.entries.forEach { e ->
        appendLine("${if (e.role == "user") "我" else "小悟"} ${clock(e.ts)}")
        e.quote?.takeIf { it.isNotBlank() }?.let { appendLine("> $it") }
        appendLine(e.text.trim())
        appendLine()
    }
}
