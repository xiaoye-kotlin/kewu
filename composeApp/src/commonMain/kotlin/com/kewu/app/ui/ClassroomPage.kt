package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kewu.app.AttendanceSession
import com.kewu.app.AudioRecorder
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.RecordingUploadResult
import com.kewu.app.haptic
import com.kewu.app.hapticClickable
import com.kewu.app.platformActivity
import com.kewu.app.platformAppContext
import com.kewu.app.platformCacheDir
import com.kewu.app.platformReadFile
import com.kewu.app.requestAudioPermission
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// ───────────────────────── 数据模型（mock） ─────────────────────────

/** 课程卡片状态：待上课 / 正在上课 / 已总结 / 总结生成中 / 上传或总结失败 */
private enum class CourseStatus { PENDING, LIVE, DONE, SUMMARIZING, FAILED }

private data class Course(
    val name: String,
    val room: String = "",
    val teacher: String = "",
    val time: String = "",
    val status: CourseStatus,
    val summary: String = "",
    val homework: Boolean = false,
    val devices: Int = 0,
    val aiReady: Boolean = false,
    val highlights: Int = 0,
    val estWait: String = "",
)

/** 今日课程（倒序：最新的在最上）+ 一批历史课程，用于「查看全部」展开。 */
private fun mockToday(): List<Course> = listOf(
    Course("高等数学", room = "主楼 305", teacher = "王老师", time = "14:00", status = CourseStatus.LIVE),
    Course(
        "大学英语", room = "外语楼 202", teacher = "李老师", time = "10:00",
        status = CourseStatus.DONE, summary = "定语从句与虚拟语气的嵌套用法、长难句拆解",
        homework = true, devices = 3, aiReady = true, highlights = 2,
    ),
    Course(
        "线性代数", room = "主楼 401", teacher = "张老师", time = "08:00",
        status = CourseStatus.DONE, summary = "特征值与特征向量的几何意义、相似对角化条件",
        devices = 3, aiReady = true, highlights = 3,
    ),
    Course("大学物理", room = "实验楼 110", teacher = "陈老师", time = "明天", status = CourseStatus.PENDING),
    Course("程序设计", room = "机房 B205", teacher = "刘老师", time = "昨天",
        status = CourseStatus.SUMMARIZING, estWait = "约 2 分钟"),
    Course("马克思主义原理", room = "主楼 210", teacher = "赵老师", time = "前天",
        status = CourseStatus.FAILED),
)

private fun mockHistory(): List<Course> = listOf(
    Course("高等数学", room = "主楼 305", teacher = "王老师", time = "上周一",
        status = CourseStatus.DONE, summary = "多元函数偏导数的计算与链式法则",
        devices = 2, aiReady = true, highlights = 2),
    Course("大学英语", room = "外语楼 202", teacher = "李老师", time = "上周一",
        status = CourseStatus.DONE, summary = "英语议论文结构与论点展开", devices = 3, aiReady = true, highlights = 1),
)

// ───────────────────────── 形状：MIUI squircle ─────────────────────────

/**
 * 生成 MIUI / miuix 风格的「squircle」圆角路径（超椭圆角）。
 * 四个角各走一段 n=4 超椭圆（`x = cos(t)^0.5, y = sin(t)^0.5`）再直线相连，
 * 比 `RoundedCornerShape` 的圆弧更「方中带圆」，是 MIUI 观感的关键。
 *
 * ★★ 必须 `coerceAtLeast(0f)` 再开方（实测踩过）：Float 精度下 `cos(π/2) = -4.371e-08`
 * 是**负数**，`(-4.4e-8).pow(0.5)` = `NaN`，一个 NaN 点就能让整条 Path 作废 ——
 * `drawPath` 什么都不画、也不抛异常，表现成「卡片整块空白但文字/图标正常」。
 */
internal fun squirclePath(w: Float, h: Float, r: Float): Path {
    val path = Path()
    val steps = 16
    val halfPi = 3.1415927f / 2f
    // 超椭圆参数式：|x|^4 + |y|^4 = 1 → x = cos(t)^0.5, y = sin(t)^0.5
    fun x(t: Float) = sqrt(cos(t).coerceAtLeast(0f))
    fun y(t: Float) = sqrt(sin(t).coerceAtLeast(0f))

    // 左上：从左边 (0, r) 走到顶边 (r, 0)
    path.moveTo(r * (1f - x(0f)), r * (1f - y(0f)))
    for (i in 1..steps) {
        val t = halfPi * i / steps
        path.lineTo(r * (1f - x(t)), r * (1f - y(t)))
    }
    // 右上：从顶边 (w-r, 0) 走到右边 (w, r)
    for (i in steps downTo 0) {
        val t = halfPi * i / steps
        path.lineTo(w - r + r * x(t), r * (1f - y(t)))
    }
    // 右下：从右边 (w, h-r) 走到底边 (w-r, h)
    for (i in 0..steps) {
        val t = halfPi * i / steps
        path.lineTo(w - r + r * x(t), h - r + r * y(t))
    }
    // 左下：从底边 (r, h) 走到左边 (0, h-r)
    for (i in steps downTo 0) {
        val t = halfPi * i / steps
        path.lineTo(r * (1f - x(t)), h - r + r * y(t))
    }
    path.close()
    return path
}

/**
 * squircle 卡片底 + 按卡片矩形裁剪内容。
 *
 * 为什么不用 `Modifier.clip(shape)` / `Modifier.background(color, shape)`：
 * 自定义 Shape 产出的是 `Outline.Generic`，交给 RenderNode（hardware outline）时
 * 在本工程 Compose 版本下**完全不绘制**（"布局占位正确、整块空白、零报错"）。
 * 所以这里走 `drawBehind` + `DrawScope.drawPath`（纯 Canvas 绘制链路，稳定），
 * 越界内容（超大水印）交给 `clipToBounds()`（矩形 outline，RenderNode 支持）。
 *
 * @param clip false 时不挂 `clipToBounds()`——留给外层自己包一层裁剪节点用
 *   （配 [squircleShadow] 时：阴影要画出卡片边界，节点本身不能裁；
 *   水印裁剪改由卡片内一个 `fillMaxSize().clipToBounds()` 的中间层完成）。
 */
internal fun Modifier.squircleCard(radius: Dp, color: Color, clip: Boolean = true): Modifier =
    this
        .drawBehind {
            val half = if (size.width < size.height) size.width / 2f else size.height / 2f
            drawPath(squirclePath(size.width, size.height, radius.toPx().coerceAtMost(half)), color)
        }
        .then(if (clip) Modifier.clipToBounds() else Modifier)

/**
 * squircle 柔和投影（伪软阴影，2026-10-19 首页三卡改白后加）。
 *
 * 原理：不依赖平台 blur API（跨平台稳定），把同一条 squircle 路径逐层
 * 「轻微外扩 + 逐层下移」叠画 6 次、透明度递减，模拟高斯投影；
 * 卡片本体由 [squircleCard] 画在上一层盖住投影中心，只留四周与下方一圈软边。
 *
 * ★ 必须与 [squircleCard] 拆成**兄弟节点**、且本修饰符所在节点**不能带 clipToBounds**，
 *   否则投影画不出卡片边界就被裁掉。标准用法：
 * ```
 * Box(modifier) {
 *     Box(Modifier.fillMaxSize().squircleShadow(16.dp))              // 底层：投影
 *     Box(Modifier.fillMaxSize().squircleCard(16.dp, color)) { … }   // 上层：卡片
 * }
 * ```
 */
internal fun Modifier.squircleShadow(radius: Dp, spread: Dp = 5.dp, alpha: Float = 0.075f): Modifier =
    this.drawBehind {
        val r = radius.toPx().coerceAtLeast(0f).coerceAtMost(minOf(size.width, size.height) / 2f)
        val path = squirclePath(size.width, size.height, r)
        val spreadPx = spread.toPx()
        val layers = 6
        for (i in 0 until layers) {
            val f = i / (layers - 1).toFloat() // 0..1：越靠外的层越下移、越淡
            scale(1f + 0.012f * i, pivot = Offset(size.width / 2f, size.height / 2f)) {
                translate(0f, spreadPx * f) {
                    drawPath(path, Color.Black.copy(alpha = alpha * (1f - f)))
                }
            }
        }
    }

// ───────────────────────── ★ 标志性组件：大半圆 + 勾／叉 ─────────────────────────

/**
 * 课悟的标志性图形：**大半圆包着中间的勾／叉**。
 *
 * `startAngle = 135f, sweepAngle = 270f`（Compose 里 0° = 3 点钟、角度顺时针增大），
 * 即从「左下」顺时针绕一整圈到「右下」—— 缺口落在**正下方**（45°..135°）。
 *
 * 同一个外壳、同一套笔画，只换中间符号与颜色，就是全 app 的「状态语言」：
 * - 绿勾 = 已完成／就绪（品牌色 [KewuGreen]）
 * - 红叉 = 失败（`colorScheme.error`），**不用感叹号**
 *
 * 三处用法：① 状态大卡里当作 170dp 的**超大水印**（Mishka 的手法：图标远大于卡片、
 * 塞到右下角、被卡片裁掉大半；缺口正好落在卡片外，所以看起来是一整圈）；
 * ② 课程卡「已总结」徽章；③ 课程卡「失败」徽章（`cross = true`）。
 * 另：任务页每条任务前的徽章、课表页「已上过」徽章也复用同一组件。
 *
 * ★ 出场动效：默认 `animateEntry = true`，先扫出大半圆（420ms）再逐段写出勾／叉（260ms），
 * 观感是「当场画出来」——这是本软件的标志性动效，无特殊理由不要关掉。
 */
@Composable
internal fun ThreeQuarterCheck(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    cross: Boolean = false,
    /** 是否播放「画出来」的出场动效（默认开 —— 这是本软件的标志性动效） */
    animateEntry: Boolean = true,
) {
    // ★★ 软件标志性出场动效（2026-10-19）：**先扫出大半圆，再逐段"写"出中间的勾／叉**。
    // 两段动画串起来，看起来像有人用笔当场把这个符号画出来 —— 首页大卡水印、任务条目徽章、
    // 课表/课程卡徽章全都用它，是全 app 统一的识别点（见交接文档 14.2）。
    val arcP = remember { Animatable(if (animateEntry) 0f else 1f) }
    val markP = remember { Animatable(if (animateEntry) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animateEntry) return@LaunchedEffect
        arcP.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
        markP.animateTo(1f, tween(durationMillis = 260, easing = LinearOutSlowInEasing))
    }
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val sw = s * 0.072f
        val inset = sw / 2f
        val c = color.copy(alpha = alpha)
        // ① 大半圆：sweep 从 0 扫到 270°（起点 135°，缺口落在正下方）
        drawArc(
            color = c,
            startAngle = 135f,
            sweepAngle = 270f * arcP.value,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(s - sw, s - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        val t = markP.value
        if (t <= 0f) return@Canvas
        // ② 中间符号：两段笔画各占一半进度，逐段写出（勾 = 折线两段；叉 = 两条对角）
        val mw = sw * 1.25f
        fun seg(from: Offset, to: Offset, prog: Float) {
            val p = prog.coerceIn(0f, 1f)
            if (p <= 0f) return
            drawLine(
                color = c,
                start = from,
                end = Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p),
                strokeWidth = mw,
                cap = StrokeCap.Round,
            )
        }
        if (cross) {
            seg(Offset(s * 0.35f, s * 0.35f), Offset(s * 0.65f, s * 0.65f), t * 2f)
            seg(Offset(s * 0.65f, s * 0.35f), Offset(s * 0.35f, s * 0.65f), (t - 0.5f) * 2f)
        } else {
            seg(Offset(s * 0.31f, s * 0.50f), Offset(s * 0.44f, s * 0.63f), t * 2f)
            seg(Offset(s * 0.44f, s * 0.63f), Offset(s * 0.69f, s * 0.37f), (t - 0.5f) * 2f)
        }
    }
}

/**
 * 警示感叹号水印（未上课态用）：大半圆弧 + 中央"!"，配琥珀黄。
 * 与 [ThreeQuarterCheck] 同尺寸、同右下偏移布局，可直接替换状态块水印。
 */
@Composable
internal fun ExclaimMark(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val sw = s * 0.072f
        val inset = sw / 2f
        drawArc(
            color = color.copy(alpha = alpha),
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(s - sw, s - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // 中央"!"：上竖线 + 底部圆点
        val cx = s * 0.5f
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(cx, s * 0.34f),
            end = Offset(cx, s * 0.60f),
            strokeWidth = sw * 1.25f,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = sw * 0.62f,
            center = Offset(cx, s * 0.70f),
        )
    }
}

// 课堂主页下钻目标（5 态）；attendanceId 单独存值
private enum class ClassroomDrill { NONE, TIMETABLE, ATTENDANCE, SCAN, CODE }

// ───────────────────────── 课堂主页 ─────────────────────────

/**
 * 课堂主页：整体照 Mishka（miuix）的版式 —— squircle 卡片、12.dp 间距、
 * 左侧大状态卡 + 右侧两张堆叠小卡、卡内 16.dp 内边距、超大水印图形。
 * 配色走 [MaterialTheme] 默认 colorScheme，不自定义背景色。
 *
 * @param onGoToTab 跳转到主界面某个 tab（头像 → 「我的」= 3）
 * @param onNavHide 滚动方向回调：true = 向下浏览（收起底部导航栏），false = 回顶（展开）
 */
@Composable
fun ClassroomPage(onGoToTab: (Int) -> Unit, onNavHide: (Boolean) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    // ★ 接入真实课程：/api/courses 替换 mock（后端仅 id/name/description/teacher，
    //   缺教室/时间/实时状态 → room="待定"、time="—"、status=PENDING）；失败降级 mock。
    // ★ 动画修复（2026-10-18）：初始必须是**空列表**而不是 mock —— 旧版先显示 mock 课程、
    //   接口返回后又整体换成真数据，等于「先出现一遍、再出现一遍」。现在先空着（内容区从
    //   零开始），加载成功才带凝结入场动画出现；只有断网失败才降级 mock。
    var realCourses by remember { mutableStateOf(emptyList<Course>()) }
    var coursesLoaded by remember { mutableStateOf(false) }
    val today = realCourses
    val history = emptyList<Course>()
    val courses = if (showAll) today + history else today

    // 问候栏/头像用真实姓名（来自 /user/me 或登录账号）
    var displayName by remember { mutableStateOf(KewuApi.currentUser?.user?.nickname ?: KewuApi.username ?: "同学") }
    // 真实课程列表（带 id），供首页录音/上传挑选绑定课程；无 id 时禁用录音
    var courseInfos by remember { mutableStateOf<List<CourseInfo>?>(null) }
    LaunchedEffect(Unit) {
        if (KewuApi.currentUser == null) KewuApi.me()
        displayName = KewuApi.currentUser?.user?.nickname ?: KewuApi.username ?: displayName
        val list = KewuApi.getCourses()
        if (list != null) {
            realCourses = list.map { c ->
                Course(
                    name = c.name, teacher = c.teacher, room = "待定", time = "—",
                    status = CourseStatus.PENDING, summary = c.description,
                )
            }
            courseInfos = list
        } else {
            // 断网兜底：此时才降级 mock（用户看到的是「加载失败后的备用数据」，不是闪烁）
            realCourses = mockToday()
        }
        coursesLoaded = true
    }

    // ★ coui 玻璃的硬约束：采样者必须在被采样层**之外**，否则首帧 RenderThread SIGSEGV。
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    LaunchedEffect(scrollState) {
        var last = 0
        snapshotFlow { scrollState.value }.collect { cur ->
            if (cur <= 0) {
                onNavHide(false)
            } else {
                val delta = cur - last
                if (delta > 6) onNavHide(true) else if (delta < -6) onNavHide(false)
            }
            last = cur
        }
    }

    // 课堂主页的下钻：课表页 / 签到详情。状态提升到本页，切走 tab 再回来会重置
    // （课表是「查看排课」的一次性入口，签到详情也是临时查看，均不需跨 tab 保留）。
    var drill by remember { mutableStateOf(ClassroomDrill.NONE) }
    var attendanceId by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        // 背景层：M3 默认背景色，是玻璃的采样源
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(MaterialTheme.colorScheme.background),
        )
        // 下钻路由：签到详情 / 课表页 / 课堂主页。
        // ★ 共享轴滑页（与「我的」页子路由同款，仅 slide、无 fade）：旧版 when 直接硬切，
        //   页面「啪」地换掉，非常生硬；slide-only 不引入 alpha 离屏层，玻璃采样者安全。
        val attId = attendanceId
        // CODE 态但会话 id 丢了（异常路径）→ 自动回到首页，不再靠子分支里手动纠偏。
        LaunchedEffect(drill, attId) {
            if ((drill == ClassroomDrill.CODE || drill == ClassroomDrill.ATTENDANCE) && attId == null) {
                drill = ClassroomDrill.NONE
            }
        }
        val drillKey = when {
            drill == ClassroomDrill.ATTENDANCE && attId != null -> 2
            drill == ClassroomDrill.SCAN -> 3
            drill == ClassroomDrill.CODE && attId != null -> 4
            drill == ClassroomDrill.TIMETABLE -> 1
            else -> 0
        }
        AnimatedContent(
            targetState = drillKey,
            transitionSpec = {
                // ★ 2026-10-19 加强切页力度：旧页整屏推出（-/3 屏），新页从屏外完整推进来，
                //   380ms —— 原先 -w/5 / 320ms 幅度太小，点「签到」跳扫码时几乎看不出在切页。
                //   仍然 **slide-only**（不加 fade/scale）：下钻页含玻璃采样者与相机预览，
                //   alpha/离屏层会踩 coui 首帧 SIGSEGV，且 AndroidView 在离屏层下会黑屏。
                if (targetState > initialState) {
                    slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { w -> w } togetherWith
                        slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { w -> -w / 3 }
                } else {
                    slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { w -> -w / 3 } togetherWith
                        slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { w -> w }
                }
            },
            label = "classroomDrill",
        ) { key ->
            when (key) {
                2 -> {
                    // 拦截系统返回手势，回到课堂主页，而不是让 Activity 直接 finish 退出 app。
                    BackHandler { drill = ClassroomDrill.NONE; attendanceId = null }
                    AttendanceDetailScreen(
                        attendanceId = attId!!,
                        studentId = KewuApi.username ?: "",
                        studentName = KewuApi.currentUser?.user?.nickname ?: (KewuApi.username ?: ""),
                        onBack = { drill = ClassroomDrill.NONE; attendanceId = null },
                    )
                }
                3 -> {
                    // 扫码签到：学生扫教师端展示的二维码（教师端在签到详情里展示二维码）。
                    BackHandler { drill = ClassroomDrill.NONE; attendanceId = null }
                    ScanPage(
                        fallbackSessionId = attendanceId,
                        onBack = { drill = ClassroomDrill.NONE; attendanceId = null },
                        // 签到成功 → 返回首页；失败（如需答随堂问题）→ 带暗号跳暗号签到页继续
                        onDone = { drill = ClassroomDrill.NONE; attendanceId = null },
                        onOpenCodeCheckIn = { sid, code ->
                            attendanceId = sid
                            drill = ClassroomDrill.CODE
                        },
                    )
                }
                4 -> {
                    // 暗号签到独立页（学生）：扫码失败的兜底 / 相机不可用
                    BackHandler { drill = ClassroomDrill.NONE; attendanceId = null }
                    CodeCheckInPage(
                        sessionId = attId!!,
                        onBack = { drill = ClassroomDrill.NONE; attendanceId = null },
                        onDone = { drill = ClassroomDrill.NONE; attendanceId = null },
                    )
                }
                1 -> {
                    BackHandler { drill = ClassroomDrill.NONE }
                    TimetablePage(onBack = { drill = ClassroomDrill.NONE }, onNavHide = onNavHide)
                }
                else -> {
                    // 首次进场（开 app / 切回课堂 tab）：分块 Mac 式入场。
                    // ★ 动画修复（2026-10-18）：所有块都「从零开始」—— 初始帧即不可见
                    //   （非玻璃 alpha=0 / 玻璃 tint≈0 + 内容透明），错峰延迟后才凝结上浮，
                    //   不再「控件先出现、再突然播一遍入场」。
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(bottom = 96.dp),
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.greetIn(40)) {
                            GreetingBar(name = displayName) { onGoToTab(3) }
                        }
                        Box(Modifier.macPopIn(140)) {
                            StatusSection(
                                courses = courses,
                                // 只数「今天」的课（time 为 HH:mm 的才是今天；明天/昨天/前天不计），
                                // 与课表页今天的 3 节对齐
                                todayCount = today.count { it.time.length == 5 && it.time[2] == ':' },
                                loading = !coursesLoaded,
                                onTodayCourses = { drill = ClassroomDrill.TIMETABLE },
                                onHomework = { onGoToTab(2) },
                            )
                        }
                        // 签到卡片（当有进行中的签到时显示）
                        // 身份分流：该签到的发布者（教师）→ 签到详情展示二维码；学生 → 扫码页
                        CheckInCardIfActive(pageBackdrop) { session ->
                            attendanceId = session.id
                            val me = KewuApi.currentUser?.user?.nickname ?: KewuApi.username
                            drill = if (me != null && session.publisher.isNotBlank() && session.publisher == me) {
                                ClassroomDrill.ATTENDANCE
                            } else {
                                ClassroomDrill.SCAN
                            }
                        }
                        RecordingOverlay(backdrop = pageBackdrop, courseInfos = courseInfos)
                        courses.forEachIndexed { i, c -> CourseCard(c, pageBackdrop, i) }
                        MoreBar(showAll, Modifier.riseEnter(rememberEnterProgress(0, baseDelayMs = 220L), 24f)) { showAll = !showAll }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

// ───────────────────────── 顶部：问候栏 ─────────────────────────

@Composable
private fun GreetingBar(name: String, onAvatar: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rememberGreeting(), color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            Text(
                name, color = cs.onSurface, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
        Avatar(name, onAvatar)
    }
}

@Composable
private fun rememberGreeting(): String {
    val hour = remember { ((Clock.System.now().toEpochMilliseconds() / 3_600_000L + 8L) % 24L).toInt() }
    return when {
        hour < 6 -> "凌晨好"
        hour < 12 -> "早上好"
        hour < 14 -> "中午好"
        hour < 18 -> "下午好"
        else -> "晚上好"
    }
}

@Composable
private fun Avatar(name: String, onAvatar: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
        .background(cs.secondaryContainer)
        .hapticClickable { onAvatar() },
        contentAlignment = Alignment.Center,
    ) {
        Text(name.firstOrNull()?.toString() ?: "我", color = cs.onSecondaryContainer,
            fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

// ───────────────────────── 状态区（Mishka Home 版式） ─────────────────────────

/**
 * 左：大状态卡（超大水印「大半圆+绿勾」+ 状态标题 + 两行副文本）
 * 右：两张堆叠小卡（标签 13.sp + 数值 20.sp Bold）—— 对齐 Mishka 的 statusSection。
 *
 * @param todayCount 今日课程数（不随「查看全部」展开变化）
 * @param onTodayCourses 点「今日课程」→ 进课表页看排课
 * @param onHomework 点「作业待交」→ 跳任务 tab
 */
@Composable
private fun StatusSection(
    courses: List<Course>,
    todayCount: Int,
    loading: Boolean = false,
    onTodayCourses: () -> Unit,
    onHomework: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val live = courses.firstOrNull { it.status == CourseStatus.LIVE }
    val done = courses.count { it.status == CourseStatus.DONE }
    // 下节课（未上课时直接显示教室，用户要求：不要"下节课在哪里上"这种话术）
    val next = courses.firstOrNull { it.status == CourseStatus.PENDING || it.status == CourseStatus.SUMMARIZING }
    // 状态语义色：**正在上课 = 琥珀黄「!」；无课 = 品牌绿大半圆+勾**（用户 2026-10-19 明确要求这种对应）
    val statusTint = if (live != null) Color(0xFFFFB300) else KewuGreen
    // 用户 2026-10-19：三卡原来用 surfaceVariant 偏灰 → 改近白（surface），
    // 白底下靠 squircleShadow 软阴影区分层次（阴影层与卡片层是兄弟节点，见下方）
    val statusContainer = cs.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 6.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧大状态卡：外层 Box 只提供尺寸；软阴影层与卡片层做兄弟（阴影不裁切才能渗出卡片边界）
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            // 底层：squircle 软阴影（白卡片在白底上的层次感全靠它）
            Box(Modifier.fillMaxSize().squircleShadow(22.dp))
            // 上层：卡片本体。clip=false——裁剪改由卡内中间层做（见下），
            // 否则 clipToBounds 会把上面这层投影一起裁掉。
            Box(
                Modifier
                    .fillMaxSize()
                    .squircleCard(22.dp, statusContainer, clip = false),
            ) {
                // 内容裁剪层：替代原先 squircleCard 内部的 clipToBounds。
                // ★ 裁剪节点必须留在卡片原点：水印子节点带 offset，若把 clip 挂到
                //   被 offset 的节点上，裁剪框会跟着一起移走，水印就裁不掉了。
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    // 超大水印：170dp 的大半圆+符号，offset 到右下角、被卡片裁掉大半。
                    // 注意用 **lambda 版 offset**（px，纯视觉平移）：Dp 版 `offset(x, y)` 会把
                    // x/y 加进 min intrinsic 尺寸，导致 `Row(height = IntrinsicSize.Min)` 被撑高 45dp。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .offset { IntOffset(52.dp.roundToPx(), 58.dp.roundToPx()) },
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        // 无课 → 品牌绿大半圆+勾；正在上课 → 琥珀黄大半圆+「!」（用户 2026-10-19 要求）
                        if (live != null) {
                            ExclaimMark(size = 170.dp, color = statusTint, alpha = 0.30f)
                        } else {
                            ThreeQuarterCheck(size = 170.dp, color = KewuGreen, alpha = 0.30f)
                        }
                    }
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    if (live != null) "！当前正在上课" else "无课",
                    color = statusTint, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (live != null) "${live.name} · ${live.room}"
                    else (next?.let { "下节课：${it.name} · ${it.room}" } ?: "今日课程已全部结束"),
                    color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
                Text(
                    when {
                        loading -> "正在同步课程…"
                        live != null -> "已总结 $done / ${courses.size} 节"
                        todayCount == 0 -> "今天没有排课"
                        else -> "今天共 $todayCount 节课"
                    },
                    color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
                    }
                }
            }
        }

        // 右侧两张堆叠小卡（均可点：进课表 / 跳任务页）
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MiniStatCard(
                label = "今日课程",
                value = "$todayCount",
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = onTodayCourses,
            )
            Spacer(Modifier.height(12.dp))
            MiniStatCard(
                label = "作业待交",
                value = "3", // mock：与任务页「本周待交」对齐
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = onHomework,
            )
        }
    }
}

/**
 * Mishka 堆叠小卡：标签 + 大数值。
 * [onClick] 非空时整卡可点，并在右侧画一个小箭头提示「可进入」。
 */
@Composable
private fun MiniStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .then(if (onClick != null) Modifier.hapticClickable { onClick() } else Modifier),
    ) {
        // 底层：squircle 软阴影（用户 2026-10-19：卡片改近白后，白底上的层次靠阴影）
        Box(Modifier.fillMaxSize().squircleShadow(22.dp))
        // 上层：近白卡片（surfaceVariant 偏灰 → surface；阴影层不裁切才能渗出边界，
        // 所以卡片层保持默认 clip=true 也无所谓——小卡没有越界内容）
        Box(
            Modifier
                .fillMaxSize()
                .squircleCard(22.dp, cs.surface)
                .padding(16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(value, color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (onClick != null) {
                    Spacer(Modifier.width(6.dp))
                    // 右侧小箭头（›）：弱提示「这张卡可以点」
                    Canvas(Modifier.size(13.dp)) {
                        val sw = size.width * 0.14f
                        val p = Path().apply {
                            moveTo(size.width * 0.35f, size.height * 0.2f)
                            lineTo(size.width * 0.65f, size.height * 0.5f)
                            lineTo(size.width * 0.35f, size.height * 0.8f)
                        }
                        drawPath(
                            p, cs.onSurfaceVariant,
                            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────── 录音浮层（coui 毛玻璃 · 真实录音 + 上传） ─────────────────────────

/** 首页录音状态机：Idle（待录音）/ Uploading（上传中）/ Success / Error。 */
private sealed class RecState {
    object Idle : RecState()
    object Uploading : RecState()
    data class Success(val recordingId: String) : RecState()
    data class Error(val msg: String) : RecState()
}

@Composable
private fun RecordingOverlay(backdrop: LayerBackdrop, courseInfos: List<CourseInfo>?) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // ★ AudioRecorder 需要 Android Context：commonMain 通过 platformAppContext() 取 Application Context
    val audio = remember { AudioRecorder(platformAppContext()) }

    val activeCourse = courseInfos?.firstOrNull()
    val classId = activeCourse?.id
    val courseName = activeCourse?.name ?: "课堂"

    var isRecording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableStateOf(0) }
    var lastDuration by remember { mutableStateOf(0) }
    var recordedPath by remember { mutableStateOf<String?>(null) }
    var recState by remember { mutableStateOf<RecState>(RecState.Idle) }
    var permissionHint by remember { mutableStateOf(false) }

    // 玻璃凝结入场（2026-10-18 动画统一改造）：初始 tint≈0 + 内容透明 = 不可见，
    // 240ms 后从零「长」出来，不再一进场就整卡出现、再突然滑一遍。
    val p = rememberEnterProgress(0, baseDelayMs = 240L)

    // 录音计时器（仅录音中累加）
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) { delay(1000); recordingTime++ }
        } else recordingTime = 0
    }

    fun fmtTime(s: Int): String {
        val m = s / 60
        val sec = s % 60
        return "${if (m < 10) "0$m" else "$m"}:${if (sec < 10) "0$sec" else "$sec"}"
    }

    fun uploadFile(path: String, durationSec: Int) {
        recState = RecState.Uploading
        scope.launch {
            try {
                val bytes = platformReadFile(path)
                if (bytes == null) { recState = RecState.Error("录音文件读取失败，请稍后重试"); return@launch }
                val ts = Clock.System.now().toEpochMilliseconds()
                val filename = "kewu_${classId ?: "unknown"}_$ts.wav"
                val res = KewuApi.uploadRecording(bytes, filename, classId ?: "unknown", null, durationSec.toDouble())
                recState = if (res != null) RecState.Success(res.recordingId)
                else RecState.Error("录音上传失败，请稍后重试")
            } catch (e: Exception) {
                recState = RecState.Error("录音上传失败，请稍后重试")
            }
        }
    }

    fun startRecordingSafe() {
        scope.launch {
            val ts = Clock.System.now().toEpochMilliseconds()
            val out = platformCacheDir() + "/kewu_rec_$ts.pcm"
            val ok = audio.startRecording(out)
            if (ok) {
                isRecording = true
                recordingTime = 0
                recordedPath = null
                recState = RecState.Idle
                permissionHint = false
            } else {
                permissionHint = !audio.hasPermission()
            }
        }
    }

    fun onRecordClick() {
        haptic(HapticKind.Medium)
        if (isRecording) {
            // 停止 → 生成 WAV → 自动上传
            val dur = recordingTime
            isRecording = false
            lastDuration = dur
            scope.launch {
                val path = audio.stopRecording()
                recordedPath = path
                if (path == null) recState = RecState.Error("录音文件生成失败，请稍后重试")
                else uploadFile(path, dur)
            }
        } else {
            if (classId == null) {
                recState = RecState.Error("暂无可绑定的课程，请先在「我的」绑定课程后再录音")
                return
            }
            if (audio.hasPermission()) startRecordingSafe()
            else {
                val act = platformActivity()
                if (act != null) requestAudioPermission(act) { granted ->
                    if (granted) startRecordingSafe() else permissionHint = true
                } else permissionHint = true
            }
        }
    }

    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        blurRadius = glassCondenseBlur(p, 20f),
        tintAlpha = 0.3f * p,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .glassEnter(p),
    ) {
        Column(Modifier.alpha(p).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Waveform(active = isRecording)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (classId == null) "无可用课程" else "$courseName · 课堂录音",
                        color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    val sub = when {
                        isRecording -> "录音中 ${fmtTime(recordingTime)}"
                        recordedPath != null && recState is RecState.Success -> "已上传 ✓"
                        recordedPath != null -> "录音完成，正在上传"
                        else -> "点击右侧按钮开始录音"
                    }
                    Text(sub, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                }
                // 录音 / 停止按钮
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) cs.error else cs.primary)
                        .hapticClickable { onRecordClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (isRecording) "⏹" else "⏺", color = Color.White, fontSize = 20.sp)
                }
            }

            // 权限提示
            if (permissionHint) {
                Spacer(Modifier.height(10.dp))
                Text("需要麦克风权限才能录音，请在系统弹窗中允许", color = cs.error, fontSize = 12.5.sp)
            }

            // 上传状态
            when (val st = recState) {
                is RecState.Uploading -> {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = cs.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("上传中…", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                }
                is RecState.Success -> {
                    Spacer(Modifier.height(12.dp))
                    StatusTag("已上传 · ${st.recordingId}", KewuGreen.copy(alpha = 0.18f), KewuGreen)
                }
                is RecState.Error -> {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(st.msg, color = cs.error, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        if (recordedPath != null && !isRecording) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(cs.error)
                                    .hapticClickable { haptic(HapticKind.Tick); uploadFile(recordedPath!!, lastDuration) }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                            ) { Text("重试", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                is RecState.Idle -> {}
            }
        }
    }
}

@Composable
private fun Waveform(active: Boolean = false) {
    val t by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(36.dp, 26.dp)) {
        val n = 5
        val bw = size.width / (n * 2f)
        val pi = 3.1415927f
        for (i in 0 until n) {
            val phase = t * 2f * pi + i * 0.8f
            // 未录音时压成细线，避免空转动画打扰
            val amp = if (active) (0.28f + 0.72f * abs(sin(phase))) else 0.12f
            val h = amp.coerceAtMost(1f) * size.height
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2f * bw + bw * 0.25f, (size.height - h) / 2f),
                size = Size(bw * 0.5f, h),
                cornerRadius = CornerRadius(bw * 0.25f),
            )
        }
    }
}

// ───────────────────────── 课程卡片流 ─────────────────────────

@Composable
private fun CourseCard(course: Course, backdrop: LayerBackdrop, index: Int) {
    // 玻璃凝结入场：初始 tint≈0 + 内容透明（不可见）→ 错峰从零上浮「长」出来
    val p = rememberEnterProgress(index, baseDelayMs = 300L, stepMs = 60L)
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        blurRadius = glassCondenseBlur(p, 20f),
        tintAlpha = 0.34f * p,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .glassEnter(p),
    ) {
        Row(
            Modifier.fillMaxWidth().alpha(p).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(course.status)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) { CourseContent(course) }
        }
    }
}

@Composable
private fun StatusBadge(status: CourseStatus) {
    val cs = MaterialTheme.colorScheme
    when (status) {
        // 已总结：标志性「四分之三圆 + 勾」
        CourseStatus.DONE -> ThreeQuarterCheck(size = 28.dp, color = KewuGreen)
        CourseStatus.LIVE -> {
            val pulse by rememberInfiniteTransition().animateFloat(
                0.65f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse),
            )
            Canvas(Modifier.size(28.dp)) {
                drawCircle(color = cs.tertiary.copy(alpha = 0.22f * pulse + 0.1f))
                drawCircle(color = cs.tertiary, radius = size.width * 0.17f * pulse)
            }
        }
        CourseStatus.SUMMARIZING -> {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = cs.primary, strokeWidth = 2.5.dp, strokeCap = StrokeCap.Round,
                )
            }
        }
        // 失败：同样的「大半圆外壳」，里面改成红色的叉（不用感叹号）
        CourseStatus.FAILED -> ThreeQuarterCheck(size = 28.dp, color = cs.error, cross = true)
        CourseStatus.PENDING -> {
            Canvas(Modifier.size(28.dp)) {
                drawCircle(
                    color = cs.onSurfaceVariant.copy(alpha = 0.45f),
                    style = Stroke(width = size.width * 0.09f),
                )
            }
        }
    }
}

@Composable
private fun CourseContent(course: Course) {
    val cs = MaterialTheme.colorScheme
    // 标题行：课程名 + 时间（单行）
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            course.name, color = cs.onSurface, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
        )
        if (course.time.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(course.time, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
        }
    }

    when (course.status) {
        CourseStatus.PENDING -> PendingBody(course)
        CourseStatus.LIVE -> LiveBody(course)
        CourseStatus.DONE -> DoneBody(course)
        CourseStatus.SUMMARIZING -> SummarizingBody(course)
        CourseStatus.FAILED -> FailedBody(course)
    }
}

@Composable
private fun PendingBody(course: Course) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    MetaText("${course.room} · ${course.teacher}")
    Spacer(Modifier.height(8.dp))
    StatusTag("未开始", cs.surfaceVariant, cs.onSurfaceVariant)
}

@Composable
private fun LiveBody(course: Course) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    MetaText(course.room)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusTag("录音中", cs.tertiaryContainer, cs.onTertiaryContainer)
        if (course.homework) StatusTag("随堂作业", cs.tertiaryContainer, cs.onTertiaryContainer)
    }
}

@Composable
private fun DoneBody(course: Course) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text(
        course.summary.ifEmpty { "本节课暂无摘要" },
        color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 2,
    )
    if (course.devices > 0 || course.aiReady) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (course.devices > 0) StatusTag("${course.devices} 台设备", cs.primaryContainer, cs.onPrimaryContainer)
            if (course.aiReady) StatusTag("AI 已就绪", cs.primaryContainer, cs.onPrimaryContainer)
        }
    }
}

@Composable
private fun SummarizingBody(course: Course) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text(
        "AI 正在整理课堂纪要…", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
    )
    Spacer(Modifier.height(3.dp))
    Text(
        course.estWait, color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1,
    )
}

@Composable
private fun FailedBody(course: Course) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text("录音上传不完整", color = cs.error, fontSize = 13.sp, maxLines = 1)
    Spacer(Modifier.height(8.dp))
    StatusTag("点击重试", cs.errorContainer, cs.onErrorContainer)
}

@Composable
private fun MetaText(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
    )
}

/** Mishka 风格小标签：胶囊底 + 对应 on 色文字。 */
@Composable
internal fun StatusTag(label: String, background: Color, content: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(label, color = content, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun MoreBar(showAll: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .squircleCard(16.dp, cs.surfaceVariant)
            .hapticClickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (showAll) "收起历史课程" else "查看全部课程",
            color = cs.onSurfaceVariant, fontSize = 14.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center,
        )
    }
}

// ───────────────────────── 统一入场动画体系（2026-10-18 动画改造） ─────────────────────────

/**
 * 统一的入场进度：先保持 0（不可见），按 [index] 错峰延迟后低阻尼弹簧跑向 1。
 * ★ 这是本次动画修复的核心：旧 `cardEnter` 初始帧 alpha=1（只有位移），首帧内容直接可见，
 * 延迟结束后又整体滑一遍 → 「控件先出现、再突然播入场」的割裂感。现在一切入场都从 0 起步。
 * 玻璃件另配 [glassEnter]（只位移）+ [glassCondenseBlur]/tint 插值（纯 draw 参数），
 * 非玻璃件配 [riseEnter]（位移 + 透明度 + 微缩放）。
 */
@Composable
internal fun rememberEnterProgress(
    index: Int,
    baseDelayMs: Long = 300L,
    stepMs: Long = 60L,
    maxDelayMs: Long = 900L,
): Float {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        delay((baseDelayMs + index * stepMs).coerceAtMost(maxDelayMs))
        target = 1f
    }
    val p by animateFloatAsState(
        targetValue = target,
        // 显式小阈值：默认 0.01 会让弹簧在第一次穿过目标时截断，回弹过冲被吃掉（本项目多次踩坑）
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 340f, visibilityThreshold = 0.0005f),
        label = "enterP$index",
    )
    return p
}

/**
 * 非玻璃元素入场：从下方上浮 + 淡入 + 轻微回弹缩放（0.96→1）。
 * alpha/scale 只允许用在**非采样者**上（squircle/drawBehind、纯文字、普通 Box）。
 */
@Composable
internal fun Modifier.riseEnter(p: Float, travelDp: Float = 60f): Modifier {
    val v = p.coerceIn(0f, 1f)
    return this
        .offset { IntOffset(0, ((1f - v) * travelDp).dp.roundToPx()) }
        .alpha(v)
        .scale(lerp(0.96f, 1f, v))
}

/**
 * 玻璃采样者入场：只做 placement 位移（禁 alpha/scale/graphicsLayer 包裹 —— coui 首帧 SIGSEGV 硬约束）。
 * 「从无到有」的另一半由调用方用纯 draw 参数完成：blurRadius 走 [glassCondenseBlur]、
 * tintAlpha 传 `目标值 * p`、玻璃内部内容包 `Modifier.alpha(p)`。
 */
@Composable
internal fun Modifier.glassEnter(p: Float, travelDp: Float = 70f): Modifier {
    val v = p.coerceIn(0f, 1f)
    return this.offset { IntOffset(0, ((1f - v) * travelDp).dp.roundToPx()) }
}

/** 玻璃凝结插值之 blur：从 4dp「凝结」到目标值（配合 tint 插值 = 登录页/助教输入框同款凝结出场）。 */
internal fun glassCondenseBlur(p: Float, targetDp: Float): Dp {
    val v = p.coerceIn(0f, 1f)
    return (4f + (targetDp - 4f) * v).dp
}

/**
 * 首页「正在上课」状态块：Mac app 打开式弹出 —— 从底部、近乎零大小放大入场，
 * 中段带一次「扭曲」（scaleX 压窄 + scaleY 拉长）再回弹到 1:1，配合轻微上浮与淡入。
 * 状态块是 squircle（drawBehind 绘制，非 coui 采样者），可安全叠 scale/alpha。
 */
@Composable
internal fun Modifier.macPopIn(delayMs: Int = 120): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); p.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
    val v = p.value.coerceIn(0f, 1f)
    // 扭曲：前半段纵向拉长、横向压窄，弹回 1:1（弹簧过冲自然产生「回正」的果冻感）
    val sx = lerp(0.82f, 1f, v)
    val sy = lerp(1.18f, 1f, v)
    return this
        .alpha(v)
        .offset { IntOffset(0, ((1f - v) * 48f).dp.roundToPx()) }
        .scale(sx, sy)
}

/** 问候栏轻量入场：从下方小幅上浮 + 淡入。 */
@Composable
internal fun Modifier.greetIn(delayMs: Int = 40): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); p.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 380f)) }
    val v = p.value.coerceIn(0f, 1f)
    return this.alpha(v).offset { IntOffset(0, ((1f - v) * 24f).dp.roundToPx()) }
}

/**
 * 文字缩放出现（Mac 式文字入场）：首次组合后按 [delayMs] 从 0.92 弹到 1.0 并淡入。
 * 用于纯文字（非玻璃采样者），可安全叠 scale/alpha。给首页「文字缩放出现」用。
 */
@Composable
internal fun Modifier.textRiseIn(delayMs: Int): Modifier {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); target = 1f }
    val v by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "textRise$delayMs",
    )
    return this.alpha(v).scale(lerp(0.92f, 1f, v))
}

// ───────────────────────── 签到卡片 ─────────────────────────

/**
 * 签到入口卡片 —— 当有进行中的签到（state=OPEN）时显示。
 * 整卡可点；身份分流由调用方按 [AttendanceSession.publisher] 与当前用户决定：
 * 教师（发布者本人）→ [AttendanceDetailScreen] 展示二维码；学生 → [ScanPage] 扫码。
 */
@Composable
private fun CheckInCardIfActive(backdrop: LayerBackdrop, onOpen: (AttendanceSession) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var currentSession by remember { mutableStateOf<AttendanceSession?>(null) }

    // 获取当前进行中的签到会话（取第一门课的 OPEN 会话）
    LaunchedEffect(Unit) {
        try {
            val courses = KewuApi.getCourses()
            if (!courses.isNullOrEmpty()) {
                val sessions = KewuApi.getAttendances(courses[0].id)
                currentSession = sessions?.firstOrNull { it.state == "OPEN" }
            }
        } catch (e: Exception) {
            // 网络异常时卡片静默不显示，不阻塞首页
        }
    }

    val session = currentSession ?: return // 没有进行中的签到，不显示

    // 玻璃凝结入场（签到卡是后到的数据：出现时从零「长」出来，不硬弹）
    val p = rememberEnterProgress(0, baseDelayMs = 300L)
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(20.dp),
        blurRadius = glassCondenseBlur(p, 22f),
        tintAlpha = 0.28f * p,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .glassEnter(p),
        onClick = { onOpen(session) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(p)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：醒目的进行中标记
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("签", color = cs.onPrimaryContainer, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(16.dp))

            // 右侧：标题 + 进入提示
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    color = cs.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "进行中 · 点击扫码签到",
                    color = KewuGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
