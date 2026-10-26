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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import io.github.suqi8.coui.kmp.icon.extended.Edit
import androidx.compose.foundation.layout.Arrangement
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
import kotlin.random.Random

/**
 * 作业题目 UI（2026-10-25 作业题目系统）。
 *
 * 同时服务两条链路，UI 全部 coui 原生件，外壳沿用课悟液态玻璃 [KewuGlassCard] / 白卡 [Card]
 * （与作业详情、批阅页同一套视觉语言）：
 *   - 教师端发布页：[QuestionEditorCard] 手动增删改题目 + [AiGenerateBlock] 调大模型出题；
 *   - 学生端提交弹窗：[QuestionRenderList] 按题型渲染作答控件，答案收集进 Map 后随提交拼进 content。
 *
 * 题目数据模型见 [Question] / [QuestionType]（AssignmentQuestion.kt）；后端未上线题目字段前，
 * 序列化进 description / content 的透传 JSON 块（QuestionStore）。
 */

/** 选项序号字母 A/B/C/D…（index 从 0 起）。 */
private fun optionLetter(i: Int): String = ('A' + i).toString()

/** 新题目 id（仅在创建时生成，保持稳定）。 */
private fun newQuestionId(): String = "q${System.nanoTime().toString(36)}${Random.nextInt(100, 999)}"

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
    Column(Modifier.fillMaxWidth()) {
        KewuSectionTitle("题目（可选 · 留空则发布纯文本作业）")

        ManualQuestionBlock { t ->
            val q = defaultQuestion(t)
            freshIds[q.id] = true
            follow.arm()
            onChange(questions + q)
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
                            onChange(questions.filter { it.id != q.id })
                        },
                    ) { requestRemove ->
                        QuestionEditItem(
                            q = q, index = i + 1, backdrop = backdrop,
                            onReplace = { nq -> onChange(questions.map { if (it.id == q.id) nq else it }) },
                            onRemove = requestRemove,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        AiGenerateBlock(questions = questions, onChange = { list ->
            list.drop(questions.size).forEach { freshIds[it.id] = true }
            follow.arm()
            onChange(list)
        }, onToast = onToast)
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 单题的进出场：高度从 0「长」出来 + 轻微上浮 + 内容淡入；删除时反向收起再真正移除。
 * ★ 题目卡是液态玻璃采样者，外层禁止 alpha / scale / graphicsLayer（coui 首帧 SIGSEGV 硬约束），
 *   所以这里只用 layout 高度 + placement 位移；淡入只作用在玻璃**内部**内容上（[LocalItemAlpha]）。
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
    val v = p.value.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .followGrowth { follow() && !removing }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val h = (placeable.height * v).toInt()
                layout(placeable.width, h) {
                    placeable.place(0, ((1f - v) * 24.dp.toPx()).toInt())
                }
            },
    ) {
        CompositionLocalProvider(LocalItemAlpha provides v) {
            Column {
                content { if (!removing) { haptic(HapticKind.Tick); removing = true } }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** 题目卡内部内容的淡入系数（由 [AnimatedQuestionItem] 提供，玻璃外壳本身不参与 alpha）。 */
private val LocalItemAlpha = compositionLocalOf { 1f }

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

@Composable
private fun QuestionEditItem(
    q: Question, index: Int, backdrop: LayerBackdrop,
    onReplace: (Question) -> Unit, onRemove: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(14.dp).alpha(LocalItemAlpha.current)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.width(26.dp).height(26.dp),
                    shape = RoundedCornerShape(8.dp), color = cs.tertiaryContainer,
                    contentColor = cs.onTertiaryContainer,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("$index", color = cs.onTertiaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("题目 $index · ${q.type.label}", color = cs.onSurface, fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(text = "删除", onClick = onRemove,
                    colors = ButtonDefaults.textButtonColorsPrimary(
                        color = cs.error, textColor = cs.onErrorContainer))
            }
            Spacer(Modifier.height(8.dp))

            // 题型切换
            QuestionTypeChips(selected = q.type, enabled = true) { onReplace(switchType(q, it)) }

            Spacer(Modifier.height(8.dp))
            // 题干
            FieldLabel("题干")
            TextField(q.stem, { onReplace(q.copy(stem = it)) }, Modifier.fillMaxWidth(),
                backgroundMode = TextFieldMode.Rectangle, label = stemHint(q.type), minLines = 2)

            Spacer(Modifier.height(8.dp))

            // 选项（选择/判断类）
            if (q.type != QuestionType.FILL_BLANK && q.type != QuestionType.SHORT_ANSWER) {
                FieldLabel("选项与答案")
                val opts = displayOptions(q)
                opts.forEachIndexed { oi, text ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(optionLetter(oi), color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(22.dp))
                        TextField(text, { nv ->
                            val newOpts = q.options.toMutableList().also { it[oi] = nv }
                            onReplace(q.copy(options = newOpts,
                                answer = normalizeAnswer(q.type, newOpts.size, q.answer)))
                        }, Modifier.weight(1f), backgroundMode = TextFieldMode.Rectangle,
                            label = "选项 ${optionLetter(oi)} 内容",
                            enabled = q.type != QuestionType.TRUE_FALSE)
                        // 选择/多选可删选项（保留至少 2 个）
                        if (q.type != QuestionType.TRUE_FALSE && q.options.size > 2) {
                            TextButton(text = "删", onClick = {
                                val newOpts = q.options.toMutableList().also { it.removeAt(oi) }
                                onReplace(q.copy(options = newOpts,
                                    answer = normalizeAnswer(q.type, newOpts.size, q.answer)))
                            })
                        }
                    }
                }
                // 选择/多选可加选项（上限 8）
                if (q.type != QuestionType.TRUE_FALSE && q.options.size < 8) {
                    TextButton(text = "＋ 添加选项", onClick = {
                        val newOpts = q.options + ""
                        onReplace(q.copy(options = newOpts,
                            answer = normalizeAnswer(q.type, newOpts.size, q.answer)))
                    })
                }
                Spacer(Modifier.height(6.dp))
                // 答案选择
                FieldLabel(if (q.type == QuestionType.MULTIPLE_CHOICE) "正确答案（可多选）" else "正确答案")
                when (q.type) {
                    QuestionType.SINGLE_CHOICE -> AnswerChips(
                        letters = opts.indices.map { optionLetter(it) }, selected = setOfNotNull(q.answer.takeIf { it.isNotBlank() }),
                        multi = false, onToggle = { letter ->
                            onReplace(q.copy(answer = normalizeAnswer(q.type, q.options.size, letter)))
                        })
                    QuestionType.MULTIPLE_CHOICE -> AnswerChips(
                        letters = opts.indices.map { optionLetter(it) },
                        selected = q.answer.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                        multi = true, onToggle = { letter ->
                            val cur = q.answer.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
                            if (!cur.add(letter)) cur.remove(letter)
                            onReplace(q.copy(answer = normalizeAnswer(q.type, q.options.size, cur.sorted().joinToString(","))))
                        })
                    QuestionType.TRUE_FALSE -> AnswerChips(
                        letters = listOf("正确", "错误"),
                        selected = setOfNotNull(q.answer.takeIf { it.isNotBlank() }), multi = false,
                        onToggle = { v -> onReplace(q.copy(answer = v)) })
                    else -> {}
                }
            } else {
                // 填空 / 简答：答案即参考答案文本
                FieldLabel("参考答案")
                TextField(q.answer, { onReplace(q.copy(answer = it)) }, Modifier.fillMaxWidth(),
                    backgroundMode = TextFieldMode.Rectangle, label = "参考答案（学生端不展示）", minLines = 2)
            }

            Spacer(Modifier.height(8.dp))
            // 分值
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("分值")
                TextField(q.score.toString(), { nv ->
                    onReplace(q.copy(score = nv.filter { it.isDigit() }.toIntOrNull() ?: 0))
                }, Modifier.width(90.dp), backgroundMode = TextFieldMode.Rectangle, label = "分值", singleLine = true)
            }
        }
    }
}

/** 答案选择 chip 行：单选题 multi=false；多选题 multi=true；判断题传入 正确/错误 文案。 */
@Composable
private fun AnswerChips(letters: List<String>, selected: Set<String>, multi: Boolean, onToggle: (String) -> Unit) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        letters.forEach { l ->
            val isSel = l in selected
            Chip(selected = isSel, onClick = { haptic(HapticKind.Tick); onToggle(l) }, label = l,
                colors = if (isSel) ChipDefaults.chipColors(
                    containerColor = KewuBlue.copy(alpha = 0.16f))
                else ChipDefaults.chipColors())
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    val cs = COUITheme.colorScheme
    Text(text, color = cs.onSurfaceSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 4.dp))
}

/**
 * AI 生成题目：老师用自然语言描述「知识点范围 + 题型 + 数量」，调大模型出题后追加进列表。
 * 失败（网络/解析）给出提示，不影响已编辑内容。
 */
@Composable
private fun AiGenerateBlock(
    questions: List<Question>,
    onChange: (List<Question>) -> Unit,
    onToast: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var spec by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

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
            Text(if (expanded) "收起" else "展开", color = cs.onSurfaceSecondary, fontSize = 13.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
        ) { Column {
            Spacer(Modifier.height(10.dp))
            Text("告诉 AI 出哪方面的题、什么题型、几道，例如：「出 3 道关于二次函数的小题，2 道单选 1 道简答」",
                color = cs.onSurfaceVariantSummary, fontSize = 12.5.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(8.dp))
            TextField(spec, { spec = it }, Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle,
                label = "知识点与题型要求…", minLines = 2)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) InfiniteProgressIndicator(size = 18.dp)
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = if (loading) "AI 出题中…" else "让 AI 出题",
                    onClick = {
                        if (loading || spec.isBlank()) return@TextButton
                        loading = true
                        scope.launch {
                            val result = AiClient.generateQuestions(spec.trim())
                            loading = false
                            if (result.isNullOrEmpty()) {
                                onToast("AI 出题失败，请检查网络或换种说法")
                            } else {
                                val cleaned = result.map { it.copy(options = stripOptionPrefixes(it.options)) }
                                onChange(questions + cleaned)
                                onToast("已生成 ${cleaned.size} 道题")
                                spec = ""
                            }
                        }
                    },
                    enabled = !loading && spec.isNotBlank(),
                )
            }
        } }
    }
}

// ───────────────────────── 学生端：题目作答渲染 ─────────────────────────

/**
 * 学生作答渲染：按题型渲染控件，答案实时收集进 [answers]（questionId -> 值）。
 * 选择题存选项字母（判断题存 正确/错误），填空/简答存文本。
 */
@Composable
fun QuestionRenderList(questions: List<Question>, answers: SnapshotStateMap<String, String>) {
    questions.forEachIndexed { i, q -> QuestionRenderItem(q, i + 1, answers) }
}

@Composable
private fun QuestionRenderItem(q: Question, index: Int, answers: SnapshotStateMap<String, String>) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
        cornerRadius = 16.dp, insideMargin = PaddingValues(16.dp), colors = kewuCardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.width(26.dp).height(26.dp), shape = RoundedCornerShape(8.dp),
                color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("$index", color = cs.onTertiaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(q.type.label, color = cs.onSurfaceVariantSummary, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            if (q.score > 0) Text("${q.score} 分", color = cs.onSurfaceSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(q.stem.ifBlank { "（题干为空）" }, color = cs.onSurface, fontSize = 14.5.sp, lineHeight = 21.sp)

        Spacer(Modifier.height(10.dp))
        when (q.type) {
            QuestionType.SINGLE_CHOICE, QuestionType.TRUE_FALSE -> {
                val opts = displayOptions(q)
                val cur = answers[q.id].orEmpty()
                opts.forEachIndexed { oi, text ->
                    val letter = if (q.type == QuestionType.TRUE_FALSE) text else optionLetter(oi)
                    val sel = cur == letter
                    Chip(selected = sel, onClick = { haptic(HapticKind.Tick); answers[q.id] = letter },
                        label = if (q.type == QuestionType.TRUE_FALSE) text else "${optionLetter(oi)}. $text",
                        colors = if (sel) ChipDefaults.chipColors(
                            containerColor = KewuBlue.copy(alpha = 0.16f))
                        else ChipDefaults.chipColors())
                    Spacer(Modifier.height(6.dp))
                }
            }
            QuestionType.MULTIPLE_CHOICE -> {
                val opts = displayOptions(q)
                val cur = answers[q.id].orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                opts.forEachIndexed { oi, text ->
                    val letter = optionLetter(oi)
                    val sel = letter in cur
                    Chip(selected = sel, onClick = {
                        haptic(HapticKind.Tick)
                        val next = cur.toMutableSet().also { if (!it.add(letter)) it.remove(letter) }
                        answers[q.id] = next.sorted().joinToString(",")
                    }, label = "$letter. $text",
                        colors = if (sel) ChipDefaults.chipColors(
                            containerColor = KewuBlue.copy(alpha = 0.16f))
                        else ChipDefaults.chipColors())
                    Spacer(Modifier.height(6.dp))
                }
            }
            QuestionType.FILL_BLANK -> {
                TextField(answers[q.id].orEmpty(), { answers[q.id] = it }, Modifier.fillMaxWidth(),
                    backgroundMode = TextFieldMode.Rectangle, label = "在此填写答案", singleLine = true)
            }
            QuestionType.SHORT_ANSWER -> {
                TextField(answers[q.id].orEmpty(), { answers[q.id] = it }, Modifier.fillMaxWidth(),
                    backgroundMode = TextFieldMode.Rectangle, label = "在此作答…", minLines = 4)
            }
        }
    }
}

/** 拼提交内容：补充说明（纯文本）+ 答案透传块；无答案只发补充说明。 */
fun buildSubmitContent(note: String, answers: Map<String, String>): String {
    val base = note.trim()
    return if (answers.isNotEmpty()) base + "\n\n" + QuestionStore.answersToContent(answers) else base
}

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
