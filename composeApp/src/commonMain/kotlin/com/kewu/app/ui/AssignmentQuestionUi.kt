package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import io.github.suqi8.coui.kmp.icon.extended.Edit
import androidx.compose.foundation.layout.Arrangement
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import com.kewu.app.QuestionResult
import com.kewu.app.KewuGreen
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import com.kewu.app.AiClient
import com.kewu.app.Question
import com.kewu.app.QuestionType
import com.kewu.app.QuestionStore
import com.kewu.app.KewuBlue
import com.kewu.app.haptic
import com.kewu.app.HapticKind
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.ChipDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlin.random.Random

/**
 * 作业题目 UI（2026-10-25 作业题目系统）。
 *
 * 同时服务两条链路，UI 全部 coui 原生件，外壳沿用课悟液态玻璃 [KewuGlassCard] / 白卡 [Card]
 * （与作业详情、批阅页同一套视觉语言）：
 *   - 教师端发布页：[QuestionEditorCard] 手动增删改题目 + [AiGenerateBlock] 调大模型出题；
 *   - 学生端作业详情：[QuestionAnswerCard] 逐题内嵌作答（选项为液态玻璃条），提交走原生 answers + 可读 content。
 *
 * 题目数据模型见 [Question] / [QuestionType]（AssignmentQuestion.kt）；后端未上线题目字段前，
 * 序列化进 description / content 的透传 JSON 块（QuestionStore）。
 */

/** 选项序号字母 A/B/C/D…（index 从 0 起）。 */
private fun optionLetter(i: Int): String = ('A' + i).toString()

/** 新题目 id（仅在创建时生成，保持稳定）。 */
// commonMain 不能用 System.nanoTime（JVM 专有，iOS 编不过）
private fun newQuestionId(): String =
    "q${kotlin.time.Clock.System.now().toEpochMilliseconds().toString(36)}${Random.nextInt(1000, 9999)}"

/** 默认 N 个空选项（教师编辑时再填文本）。 */
private fun defaultOptions(n: Int): List<String> = List(n) { "" }

/**
 * 题型模板（2026-10-26「手动出题」）：选完题型直接给出该题型的完整骨架，老师只需逐项填内容——
 * 单选 / 多选 = 4 个空选项（A–D）+ 默认答案；判断 = 固定「正确 / 错误」；填空 = 题干里预置空位「____」；
 * 简答 = 题干 + 参考答案。分值给常用默认值（客观题 10、简答 20），可改。
 */
private fun defaultQuestion(type: QuestionType): Question = when (type) {
    QuestionType.SINGLE_CHOICE -> Question(newQuestionId(), type, "", defaultOptions(4), "A", score = 10)
    QuestionType.MULTIPLE_CHOICE -> Question(newQuestionId(), type, "", defaultOptions(4), "", score = 10)
    QuestionType.TRUE_FALSE -> Question(newQuestionId(), type, "", listOf("正确", "错误"), "正确", score = 10)
    QuestionType.FILL_BLANK -> Question(newQuestionId(), type, "____", emptyList(), "", score = 10)
    QuestionType.SHORT_ANSWER -> Question(newQuestionId(), type, "", emptyList(), "", score = 20)
}

/** 各题型题干输入框的提示语（模板里的「怎么填」说明，不占用实际内容）。 */
private fun stemHint(type: QuestionType): String = when (type) {
    QuestionType.SINGLE_CHOICE -> "题干，如：下列关于 Kotlin 空安全的说法正确的是"
    QuestionType.MULTIPLE_CHOICE -> "题干，如：以下哪些属于不可变集合（多选）"
    QuestionType.TRUE_FALSE -> "题干，如：val 声明的变量可以重新赋值"
    QuestionType.FILL_BLANK -> "题干，用 ____ 标出需要填写的位置"
    QuestionType.SHORT_ANSWER -> "题目要求，如：简述 ?. 与 !! 的区别"
}

/**
 * 发布前校验题目（两处发布页共用）：返回第一条问题描述，全部合格返回 null。
 * 只拦「明显没填完」的情况，避免把空题发给学生。
 */
fun validateQuestions(questions: List<Question>): String? {
    questions.forEachIndexed { i, q ->
        val no = i + 1
        if (q.stem.isBlank() || q.stem.trim() == "____") return "第 $no 题的题干还没填"
        when (q.type) {
            QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE -> {
                val blank = q.options.indexOfFirst { it.isBlank() }
                if (blank >= 0) return "第 $no 题的选项 ${optionLetter(blank)} 还没填"
                if (q.answer.isBlank()) return "第 $no 题还没选正确答案"
            }
            QuestionType.FILL_BLANK, QuestionType.SHORT_ANSWER ->
                if (q.answer.isBlank()) return "第 $no 题还没填参考答案"
            else -> {}
        }
    }
    return null
}

/**
 * 「跟随展开」：被修饰的块尺寸变化时（展开动画的每一帧 / 新题长出来），让外层滚动容器把它整块
 * 带进可视区——页面随着卡片一起滑到底部，而不是展开到屏幕外还要手动往下翻（2026-10-26 用户反馈）。
 * [follow] 返回 true 时才跟随（只在用户刚操作后的那段动画里跟，平时打字改变高度不乱滚）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.followGrowth(follow: () -> Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged { if (follow()) scope.launch { runCatching { requester.bringIntoView() } } }
}

/** 记录「最近一次用户触发展开」的时刻，供 [followGrowth] 只在随后 [windowMs] 内跟随。 */
internal class FollowWindow(private val windowMs: Long = 900L) {
    private var since = 0L
    fun arm() { since = nowMillis() }
    fun active(): Boolean = nowMillis() - since < windowMs
    private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

/** 显示用选项：去掉 AI 生成的「A. 」前缀，仅保留纯文本；判断题固定 正确/错误。 */
private fun displayOptions(q: Question): List<String> =
    if (q.type == QuestionType.TRUE_FALSE) listOf("正确", "错误")
    else q.options.map { it.replace(Regex("^[A-Za-z]\\.\\s*"), "") }

/** 选项增减/题型切换后，把答案纠回合法范围（单选题默认 A；多选题剔除越界字母）。 */
private fun normalizeAnswer(type: QuestionType, optionCount: Int, answer: String): String {
    val letters = (0 until optionCount).map { optionLetter(it) }
    return when (type) {
        QuestionType.SINGLE_CHOICE -> if (answer in letters) answer else letters.firstOrNull() ?: ""
        QuestionType.MULTIPLE_CHOICE -> answer.split(",").map { it.trim() }.filter { it in letters }.sorted().joinToString(",")
        else -> answer
    }
}

/** 题型切换：补齐/清空选项与答案，保持数据自洽。 */
private fun switchType(q: Question, newType: QuestionType): Question = when (newType) {
    QuestionType.SINGLE_CHOICE -> q.copy(
        type = newType,
        options = if (q.options.isEmpty()) defaultOptions(4) else q.options,
        answer = normalizeAnswer(newType, if (q.options.isEmpty()) 4 else q.options.size, "A"),
    )
    QuestionType.MULTIPLE_CHOICE -> q.copy(
        type = newType,
        options = if (q.options.isEmpty()) defaultOptions(4) else q.options,
        answer = "",
    )
    QuestionType.TRUE_FALSE -> q.copy(type = newType, options = listOf("正确", "错误"), answer = "正确")
    QuestionType.FILL_BLANK, QuestionType.SHORT_ANSWER -> q.copy(type = newType, options = emptyList())
}

/** 去掉选项文本里可能混入的「A. 」前缀（AI 生成结果入编辑态前先清洗）。 */
private fun stripOptionPrefixes(opts: List<String>): List<String> =
    opts.map { it.replace(Regex("^[A-Za-z]\\.\\s*"), "") }

// ───────────────────────── 通用：题型选择 chip 行 ─────────────────────────

@Composable
private fun QuestionTypeChips(selected: QuestionType, enabled: Boolean, onSelect: (QuestionType) -> Unit) {
    val cs = COUITheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuestionType.entries.forEach { t ->
            val isSel = t == selected
            Chip(
                selected = isSel,
                enabled = enabled,
                onClick = { haptic(HapticKind.Tick); onSelect(t) },
                label = t.label,
                colors = if (isSel) ChipDefaults.chipColors(
                    containerColor = KewuBlue.copy(alpha = 0.16f),
                ) else ChipDefaults.chipColors(),
            )
        }
    }
}

// ───────────────────────── 教师端：题目编辑器 ─────────────────────────

/**
 * 题目编辑区（发布页用），自上而下：
 *   1. 「手动出题」卡：点题型 → 追加一道该题型的完整模板（选项 / 答案 / 分值骨架都已就位）；
 *   2. 已添加的题目列表（逐题编辑，新增从 0 高度「长」出来、删除收起后再移除）；
 *   3. 「AI 生成题目」卡。
 * 两张卡展开 / 新题出现时页面跟随滚动（[followGrowth]）。[questions] 由宿主页面持有（受控），经 [onChange] 回写。
 */
@Composable
fun QuestionEditorCard(
    questions: List<Question>,
    onChange: (List<Question>) -> Unit,
    backdrop: LayerBackdrop,
    onToast: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    // 刚由用户新增的题 id：只有它们播入场动画（已有题目重组时不重复播）
    val freshIds = remember { mutableStateMapOf<String, Boolean>() }
    val follow = remember { FollowWindow() }
    // ★ 2026-10-26：所有回调都基于「最新」题目列表，而不是闭包创建时的快照——AI 出题要等 ~30s，
    //   旧代码出题回来用点击时的旧列表 + 新题回写，期间手动加的题被整批覆盖（用户反馈「AI 生成把我出的题清空了」）
    val latest by rememberUpdatedState(questions)
    val latestOnChange by rememberUpdatedState(onChange)
    Column(Modifier.fillMaxWidth()) {
        KewuSectionTitle("题目（可选 · 留空则发布纯文本作业）")

        ManualQuestionBlock { t ->
            val q = defaultQuestion(t)
            freshIds[q.id] = true
            follow.arm()
            latestOnChange(latest + q)
        }
        Spacer(Modifier.height(10.dp))

        if (questions.isEmpty()) {
            Text("还没有题目：在上方选题型生成模板，或在下方让 AI 按知识点出题。",
                color = cs.onSurfaceVariantSummary, fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Spacer(Modifier.height(6.dp))
        } else {
            questions.forEachIndexed { i, q ->
                key(q.id) {
                    AnimatedQuestionItem(
                        fresh = freshIds[q.id] == true,
                        follow = { follow.active() },
                        onRemoved = {
                            freshIds.remove(q.id)
                            latestOnChange(latest.filter { it.id != q.id })
                        },
                    ) { requestRemove ->
                        QuestionEditItem(
                            q = q, index = i + 1,
                            onReplace = { nq -> latestOnChange(latest.map { if (it.id == q.id) nq else it }) },
                            onRemove = requestRemove,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        AiGenerateBlock(onGenerated = { generated ->
            // 追加到当前最新列表末尾，已有（手动 / 之前生成）的题一道不动
            generated.forEach { freshIds[it.id] = true }
            follow.arm()
            latestOnChange(latest + generated)
        }, onToast = onToast)
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 单题的进出场：高度从 0「长」出来 + 轻微上浮 + 内容淡入；删除时反向收起再真正移除。
 * ★ 题目卡里的选项是液态玻璃采样者，祖先禁止 alpha / scale / graphicsLayer（coui 首帧 SIGSEGV 硬约束），
 *   所以这里只用 layout 高度 + placement 位移（「长」出来 / 收起），不做淡入淡出。
 */
@Composable
private fun AnimatedQuestionItem(
    fresh: Boolean,
    follow: () -> Boolean,
    onRemoved: () -> Unit,
    content: @Composable (requestRemove: () -> Unit) -> Unit,
) {
    val p = remember { Animatable(if (fresh) 0f else 1f) }
    var removing by remember { mutableStateOf(false) }
    LaunchedEffect(removing) {
        if (removing) {
            p.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            onRemoved()
        } else if (p.value < 1f) {
            p.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 320f))
        }
    }
    // 动画值只在 layout lambda 里读（旧版组合阶段读 p.value，进出场期间整张题卡逐帧重组）
    Box(
        Modifier
            .fillMaxWidth()
            .followGrowth { follow() && !removing }
            .layout { measurable, constraints ->
                val v = p.value.coerceIn(0f, 1f)
                val placeable = measurable.measure(constraints)
                val h = (placeable.height * v).toInt()
                layout(placeable.width, h) {
                    placeable.place(0, ((1f - v) * 24.dp.toPx()).toInt())
                }
            },
    ) {
        Column {
            content { if (!removing) { haptic(HapticKind.Tick); removing = true } }
            Spacer(Modifier.height(10.dp))
        }
    }
}


/** 「手动出题」卡：coui 白卡，点头部展开 / 收起（默认展开）；内含 5 个题型按钮，点一下生成该题型模板。 */
@Composable
private fun ManualQuestionBlock(onAdd: (QuestionType) -> Unit) {
    val cs = COUITheme.colorScheme
    var expanded by remember { mutableStateOf(true) }
    val follow = remember { FollowWindow() }
    Card(
        onClick = {
            haptic(HapticKind.Tick)
            expanded = !expanded
            if (expanded) follow.arm()
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(16.dp))
            .followGrowth { follow.active() },
        cornerRadius = 16.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = kewuCardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(COUIIcons.Edit, contentDescription = null, tint = KewuBlue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("手动出题", color = cs.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", color = cs.onSurfaceSecondary, fontSize = 13.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Text("选择题型，自动生成该题型的模板，再逐项填写题干、选项和答案",
                    color = cs.onSurfaceVariantSummary, fontSize = 12.5.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuestionType.entries.forEach { t ->
                        Chip(
                            selected = false,
                            onClick = { haptic(HapticKind.Light); onAdd(t) },
                            label = "＋ ${t.label}",
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单题编辑卡（2026-10-26 重做，用户要求「多融合液态玻璃和 coui：选项用液态玻璃，其他用 coui」）。
 *
 * 外壳 [GlassQuestionCard] = coui 白卡，卡内自带一层淡品牌色渐变的采样层；选项 / 判断项是液态玻璃条
 * [OptionGlassRow]；题型 chip、题干、分值、参考答案、解析都是 coui 原生件。
 * 正确答案直接点选项左侧的字母标记（单选 / 判断互斥，多选可多个），不再另起一行答案 chip。
 */
@Composable
private fun QuestionEditItem(
    q: Question, index: Int,
    onReplace: (Question) -> Unit, onRemove: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    GlassQuestionCard { bd ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            IndexBadge(index)
            Spacer(Modifier.width(8.dp))
            Text("第 $index 题", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            KewuTag(q.type.label, cs.tertiaryContainer, cs.onTertiaryContainer)
            Spacer(Modifier.weight(1f))
            TextField(
                q.score.takeIf { it > 0 }?.toString() ?: "", { nv ->
                    onReplace(q.copy(score = nv.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0))
                },
                Modifier.width(72.dp), backgroundMode = TextFieldMode.Rectangle, label = "分值", singleLine = true,
            )
            TextButton(
                text = "删除", onClick = onRemove,
                colors = ButtonDefaults.textButtonColorsPrimary(color = cs.error, textColor = cs.onErrorContainer),
            )
        }
        Spacer(Modifier.height(10.dp))
        QuestionTypeChips(selected = q.type, enabled = true) { onReplace(switchType(q, it)) }
        Spacer(Modifier.height(12.dp))

        FieldLabel("题干")
        TextField(q.stem, { onReplace(q.copy(stem = it)) }, Modifier.fillMaxWidth(),
            backgroundMode = TextFieldMode.Rectangle, label = stemHint(q.type), minLines = 2)
        Spacer(Modifier.height(12.dp))

        when (q.type) {
            QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE -> {
                val multi = q.type == QuestionType.MULTIPLE_CHOICE
                FieldLabel(if (multi) "选项 · 点字母标记正确答案（可多选）" else "选项 · 点字母标记正确答案")
                val chosen = answerSet(q.answer)
                displayOptions(q).forEachIndexed { oi, text ->
                    val letter = optionLetter(oi)
                    OptionGlassRow(
                        backdrop = bd, badge = letter, selected = letter in chosen, square = multi,
                        onBadge = {
                            haptic(HapticKind.Tick)
                            val next = if (multi) {
                                chosen.toMutableSet().also { if (!it.add(letter)) it.remove(letter) }.sorted().joinToString(",")
                            } else letter
                            onReplace(q.copy(answer = normalizeAnswer(q.type, q.options.size, next)))
                        },
                    ) {
                        TextField(text, { nv ->
                            val newOpts = q.options.toMutableList().also { it[oi] = nv }
                            onReplace(q.copy(options = newOpts))
                        }, Modifier.weight(1f), backgroundMode = TextFieldMode.None, label = "选项 $letter 内容")
                        if (q.options.size > 2) {
                            TextButton(text = "移除", onClick = {
                                haptic(HapticKind.Tick)
                                val newOpts = q.options.toMutableList().also { it.removeAt(oi) }
                                onReplace(q.copy(options = newOpts,
                                    answer = normalizeAnswer(q.type, newOpts.size, shiftAnswerAfterRemove(q.answer, oi))))
                            })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (q.options.size < 8) {
                    TextButton(text = "＋ 添加选项 ${optionLetter(q.options.size)}", onClick = {
                        haptic(HapticKind.Tick)
                        onReplace(q.copy(options = q.options + ""))
                    })
                }
            }
            QuestionType.TRUE_FALSE -> {
                FieldLabel("正确答案 · 点选")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("正确" to "✓", "错误" to "✗").forEach { (v, mark) ->
                        val pick = { haptic(HapticKind.Tick); onReplace(q.copy(answer = v)) }
                        OptionGlassRow(
                            backdrop = bd, badge = mark, selected = q.answer == v, square = false,
                            onBadge = pick, onClick = pick, modifier = Modifier.weight(1f),
                        ) {
                            Text(v, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            QuestionType.FILL_BLANK, QuestionType.SHORT_ANSWER -> {
                FieldLabel("参考答案")
                TextField(q.answer, { onReplace(q.copy(answer = it)) }, Modifier.fillMaxWidth(),
                    backgroundMode = TextFieldMode.Rectangle,
                    label = if (q.type == QuestionType.FILL_BLANK) "填空的标准答案" else "参考答案要点（学生端不展示）",
                    minLines = if (q.type == QuestionType.FILL_BLANK) 1 else 3)
            }
        }
        Spacer(Modifier.height(12.dp))
        FieldLabel("答案解析（选填）")
        TextField(q.analysis, { onReplace(q.copy(analysis = it)) }, Modifier.fillMaxWidth(),
            backgroundMode = TextFieldMode.Rectangle, label = "学生提交后可见的解析", minLines = 1)
    }
}

/**
 * 删掉第 [removed] 个选项后，把答案字母跟着前移（2026-10-26）：删掉的就是答案 → 去掉；排在它后面的字母减一。
 * 旧版不重排：答案 C、删掉 B 后，C 实际指向原来的 D。
 */
private fun shiftAnswerAfterRemove(answer: String, removed: Int): String =
    answerSet(answer).mapNotNull { l ->
        val idx = l[0] - 'A'
        when {
            idx == removed -> null
            idx > removed -> optionLetter(idx - 1)
            else -> l
        }
    }.sorted().joinToString(",")

/** 答案串 → 字母集合（"A,C" / "A，C" / "AC" 均可）。 */
private fun answerSet(answer: String): Set<String> =
    answer.uppercase().filter { it in 'A'..'H' }.map { it.toString() }.toSet()

/**
 * 题目卡外壳：coui 白卡 + 卡内采样层（淡蓝→白→淡绿渐变，给液态玻璃选项条一点可折射的底色）。
 * ★ 采样层是内容的**前置兄弟**（matchParentSize），采样者（选项玻璃条）在它之外——coui 硬约束。
 */
@Composable
internal fun GlassQuestionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(LayerBackdrop) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val bd = rememberGlassBackdrop()
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        cornerRadius = 18.dp, insideMargin = PaddingValues(0.dp), colors = kewuCardColors(),
    ) {
        Box {
            Box(
                Modifier.matchParentSize().layerBackdrop(bd).background(
                    Brush.linearGradient(
                        listOf(KewuBlue.copy(alpha = 0.10f), cs.surfaceContainer, KewuGreen.copy(alpha = 0.08f)),
                    ),
                ),
            )
            Column(Modifier.fillMaxWidth().padding(16.dp)) { content(bd) }
        }
    }
}

/**
 * 液态玻璃选项条：左侧字母 / 符号标记（单选圆、多选圆角方，选中填品牌蓝），右侧内容。
 * [onBadge] 点标记；[onClick] 非空时整条可点（学生作答 / 判断题）。选中态加品牌蓝描边与淡蓝底。
 */
@Composable
internal fun OptionGlassRow(
    backdrop: LayerBackdrop,
    badge: String,
    selected: Boolean,
    square: Boolean,
    onBadge: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = COUITheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    KewuGlassCard(
        backdrop = backdrop, shape = shape, blurRadius = 12.dp,
        tintAlpha = if (selected) 0.55f else 0.40f,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().border(
            if (selected) 1.5.dp else 1.dp,
            if (selected) KewuBlue.copy(alpha = 0.75f) else cs.dividerLine, shape,
        ),
    ) {
        if (selected) Box(Modifier.matchParentSize().background(KewuBlue.copy(alpha = 0.08f)))
        Row(
            // 标记热区 40dp（视觉 28dp）：左内边距相应收到 4dp，视觉位置与旧版一致
            Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OptionBadge(badge, selected, square, onBadge)
            Spacer(Modifier.width(4.dp))
            content()
        }
    }
}

@Composable
private fun OptionBadge(text: String, selected: Boolean, square: Boolean, onClick: (() -> Unit)?) {
    val cs = COUITheme.colorScheme
    val shape = if (square) RoundedCornerShape(8.dp) else CircleShape
    // 外层 40dp 热区（旧版只有 28dp 的字母本身可点，太难点中），内层 28dp 视觉标记
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(shape)
                .background(if (selected) KewuBlue else Color.Transparent)
                .border(1.5.dp, if (selected) KewuBlue else cs.onSurfaceVariantSummary.copy(alpha = 0.55f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = if (selected) Color.White else cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 题号徽标（coui Surface）。 */
@Composable
private fun IndexBadge(index: Int) {
    val cs = COUITheme.colorScheme
    Surface(
        modifier = Modifier.size(26.dp), shape = RoundedCornerShape(8.dp),
        color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("$index", color = cs.onTertiaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    val cs = COUITheme.colorScheme
    Text(text, color = cs.onSurfaceSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 6.dp))
}

/**
 * AI 生成题目（2026-10-26 第二版）：老师填「出题范围」+ 每种题型要几道，点按钮后**按单题拆开、最多 3 路并行**
 * 调 [AiClient.generateOneQuestion]，每出完一道就经 [onGenerated] 追加到题目末尾（已有题一道不动），
 * 卡片上实时显示「已完成 x / N」。
 *
 * 为什么拆单题：一次出 5 道约 3000 token / 40s+，手机走 8081 中转时服务端 max_tokens=768 直接截断（实测返回 `[]`），
 * 表现为「出一道可以，出 5 道转很久然后失败」；单题经中转实测 20–37s 稳定成功。生成的题统一换新 id。
 */
@Composable
private fun AiGenerateBlock(
    onGenerated: (List<Question>) -> Unit,
    onToast: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf("") }
    val counts = remember { mutableStateMapOf(QuestionType.SINGLE_CHOICE to 2) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    val latestOnGenerated by rememberUpdatedState(onGenerated)
    val planned = QuestionType.entries.sumOf { counts[it] ?: 0 }

    val follow = remember { FollowWindow() }
    Card(
        onClick = {
            haptic(HapticKind.Tick)
            expanded = !expanded
            if (expanded) follow.arm()
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(16.dp))
            .followGrowth { follow.active() },
        cornerRadius = 16.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = kewuCardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(COUIIcons.Add, contentDescription = null, tint = KewuBlue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("AI 生成题目", color = cs.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (running) {
                InfiniteProgressIndicator(size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("$done / $total", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (expanded) "收起" else "展开", color = cs.onSurfaceSecondary, fontSize = 13.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
        ) { Column {
            Spacer(Modifier.height(10.dp))
            TextField(topic, { topic = it }, Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle,
                label = "出题范围 / 知识点，如：Kotlin 集合与空安全", minLines = 2, enabled = !running)
            Spacer(Modifier.height(10.dp))
            FieldLabel("各题型数量（共 $planned 道，最多 10 道）")
            QuestionType.entries.forEach { t ->
                TypeCounterRow(
                    label = t.label, count = counts[t] ?: 0, enabled = !running,
                    onMinus = { counts[t] = ((counts[t] ?: 0) - 1).coerceAtLeast(0) },
                    onPlus = { if (planned < 10) counts[t] = (counts[t] ?: 0) + 1 },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (running) "正在并行生成，每道约 20–40 秒，出好一道就追加一道到题目末尾，可继续编辑其它题…"
                else "生成的题会追加在已有题目后面，不会覆盖你出的题。",
                color = cs.onSurfaceVariantSummary, fontSize = 12.5.sp, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            KewuPrimaryButton(
                text = if (running) "AI 出题中…（已完成 $done / $total）" else "让 AI 出 $planned 道题",
                onClick = {
                    if (running || planned == 0 || topic.isBlank()) return@KewuPrimaryButton
                    val plan = QuestionType.entries.flatMap { t -> List(counts[t] ?: 0) { t } }
                    running = true; done = 0; failed = 0; total = plan.size
                    val gate = Semaphore(3)
                    val spec = topic.trim()
                    scope.launch {
                        plan.mapIndexed { i, t ->
                            launch {
                                val q = gate.withPermit { AiClient.generateOneQuestion(spec, t, i + 1, plan.size) }
                                if (q != null) {
                                    latestOnGenerated(listOf(q.copy(id = newQuestionId(), options = stripOptionPrefixes(q.options))))
                                } else failed++
                                done++
                            }
                        }.joinAll()
                        running = false
                        onToast(
                            when (failed) {
                                0 -> "已生成 ${plan.size} 道题，已追加到题目末尾"
                                plan.size -> "AI 出题失败，请检查网络后重试"
                                else -> "已生成 ${plan.size - failed} 道，${failed} 道失败，可再试一次"
                            },
                        )
                    }
                },
                enabled = !running && planned > 0 && topic.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        } }
    }
}

/**
 * 题型数量行：题型名 + 胶囊步进器（2026-10-26 重做：旧版是两个文字「−」「＋」TextButton，
 * 字形粗细不一、彼此挨得太近，看着违和）。现在是浅色胶囊底 + 两个 32dp 圆形按钮，
 * 加减号用 Canvas 画等粗线条，按钮与数字之间留足间距；不可点时整体变淡。
 */
@Composable
private fun TypeCounterRow(label: String, count: Int, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    val cs = COUITheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = cs.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(cs.onSurface.copy(alpha = 0.05f))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton(plus = false, enabled = enabled && count > 0) { haptic(HapticKind.Tick); onMinus() }
            Text(
                "$count",
                color = if (count > 0) KewuBlue else cs.onSurfaceVariantSummary,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(40.dp), textAlign = TextAlign.Center,
            )
            StepperButton(plus = true, enabled = enabled) { haptic(HapticKind.Tick); onPlus() }
        }
    }
}

/** 步进器圆形按钮：白底圆 + 等粗线条加 / 减号（不用文字字形，避免不同字体下粗细、基线不一）。 */
@Composable
private fun StepperButton(plus: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    val tint = if (enabled) KewuBlue else cs.onSurfaceVariantSummary.copy(alpha = 0.45f)
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(cs.surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
            val w = 2.dp.toPx()
            val c = size.height / 2f
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, c), androidx.compose.ui.geometry.Offset(size.width, c), w,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
            if (plus) {
                val cx = size.width / 2f
                drawLine(tint, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, size.height), w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

// ───────────────────────── 学生端：题目作答渲染 ─────────────────────────

/**
 * 学生端单题作答卡（2026-10-26 重做）：[GlassQuestionCard]（coui 白卡）+ 液态玻璃选项条。
 *
 * 题头「第 N 题 · 题型 · 分值」+ 题干；单选 / 判断整条选项可点（互斥），多选可多选；填空 / 简答为 coui 输入框。
 * 值约定：单选 / 多选存字母（多选 "A,C"），判断存「正确 / 错误」，填空 / 简答存文本（与后端判分口径一致）。
 * [readOnly] = 只读（已提交、未在重新作答）；[result] 非空时在题下展示判定、得分、参考答案与解析。
 */
@Composable
fun QuestionAnswerCard(
    q: Question,
    index: Int,
    value: String,
    onValue: (String) -> Unit,
    readOnly: Boolean,
    result: QuestionResult?,
    modifier: Modifier = Modifier,
) {
    val cs = COUITheme.colorScheme
    GlassQuestionCard(modifier) { bd ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            IndexBadge(index)
            Spacer(Modifier.width(8.dp))
            KewuTag(q.type.label, cs.tertiaryContainer, cs.onTertiaryContainer)
            Spacer(Modifier.weight(1f))
            if (q.score > 0) Text("${q.score} 分", color = cs.onSurfaceSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(q.stem.ifBlank { "（题干为空）" }, color = cs.onSurface, fontSize = 15.5.sp, lineHeight = 23.sp,
            fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))

        when (q.type) {
            QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE -> {
                val multi = q.type == QuestionType.MULTIPLE_CHOICE
                val chosen = answerSet(value)
                if (multi) {
                    Text("多选题，可选择多个选项", color = cs.onSurfaceVariantSummary, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                displayOptions(q).forEachIndexed { oi, text ->
                    val letter = optionLetter(oi)
                    val pick: (() -> Unit)? = if (readOnly) null else {
                        {
                            haptic(HapticKind.Tick)
                            onValue(
                                if (multi) chosen.toMutableSet().also { if (!it.add(letter)) it.remove(letter) }
                                    .sorted().joinToString(",")
                                else letter,
                            )
                        }
                    }
                    OptionGlassRow(
                        backdrop = bd, badge = letter, selected = letter in chosen, square = multi,
                        onBadge = pick, onClick = pick,
                    ) {
                        Text(text.ifBlank { "（空选项）" }, color = cs.onSurface, fontSize = 14.5.sp, lineHeight = 20.sp,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            QuestionType.TRUE_FALSE -> {
                val cur = normalizeTfValue(value)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("正确" to "✓", "错误" to "✗").forEach { (v, mark) ->
                        val pick: (() -> Unit)? = if (readOnly) null else { { haptic(HapticKind.Tick); onValue(v) } }
                        OptionGlassRow(
                            backdrop = bd, badge = mark, selected = cur == v, square = false,
                            onBadge = pick, onClick = pick, modifier = Modifier.weight(1f),
                        ) {
                            Text(v, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            QuestionType.FILL_BLANK -> {
                TextField(value, onValue, Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle,
                    label = "在此填写答案", singleLine = true, enabled = !readOnly)
            }
            QuestionType.SHORT_ANSWER -> {
                TextField(value, onValue, Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle,
                    label = "在此作答…", minLines = 4, enabled = !readOnly)
            }
        }

        if (result != null) {
            Spacer(Modifier.height(12.dp))
            QuestionResultBlock(q, result)
        }
    }
}

/** 判断题值归一：true/对/T → 正确，false/错/F → 错误（后端回显的 studentValue 可能是 true/false）。 */
private fun normalizeTfValue(v: String): String = when (v.trim().lowercase()) {
    "true", "正确", "对", "t", "yes", "√", "✓" -> "正确"
    "false", "错误", "错", "f", "no", "×", "✗" -> "错误"
    else -> v
}

/** 逐题判定（后端 questionResults）：✓ / ✗ / ⏳ + 判定方 + 得分 + 参考答案 + 解析。 */
@Composable
private fun QuestionResultBlock(q: Question, r: QuestionResult) {
    val cs = COUITheme.colorScheme
    val (label, color) = when (r.correct) {
        true -> "✓ 回答正确" to KewuGreen
        false -> "✗ 回答错误" to cs.error
        null -> "⏳ 待老师批阅" to cs.onSurfaceSecondary
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = cs.secondaryContainer) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(
                    when (r.judgedBy) { "AUTO" -> "系统判分"; "TEACHER" -> "老师判分"; else -> "" },
                    color = cs.onSurfaceVariantSummary, fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                val got = r.finalScore ?: r.autoScore
                Text("得分 ${fmtNum(got)} / ${fmtNum(r.fullScore)}", color = cs.onSurface, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium)
            }
            if (r.correct != null) {
                if (r.standardAnswer.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("参考答案：${r.standardAnswer}", color = cs.onSurfaceSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                }
                if (q.analysis.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("解析：${q.analysis}", color = cs.onSurfaceSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

private fun fmtNum(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else ((v * 10).toInt() / 10.0).toString()

/**
 * 提交用的可读 content（接口文档 (19) 3.12 ②：**content 仍然必填**，要把各题答案拼成可读文本，
 * 查重与 AI 预批阅都消费它）。格式：「1. B」逐行 + 补充说明；全空但带了附件时写「（见附件）」。
 * 2026-10-26 修「题目化作业点提交没反应」：旧版 content 只放补充说明（常为空）→ 后端校验失败，前端又没提示。
 */
fun buildReadableSubmitContent(
    questions: List<Question>,
    answers: Map<String, String>,
    note: String,
    hasAttachments: Boolean,
): String {
    val lines = questions.mapIndexedNotNull { i, q ->
        answers[q.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { "${i + 1}. $it" }
    }
    val text = buildString {
        if (lines.isNotEmpty()) append(lines.joinToString("\n"))
        if (note.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("补充说明：").append(note.trim())
        }
    }
    return text.ifBlank { if (hasAttachments) "（见附件）" else "" }
}
