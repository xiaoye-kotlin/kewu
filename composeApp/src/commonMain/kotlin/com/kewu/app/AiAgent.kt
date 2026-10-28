package com.kewu.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 小悟 · AI 软件操作代理（2026-10-24）。
 *
 * 让助教不只能问答，还能**直接帮用户操作软件**：用户说「帮我加入班级」「打开发音管理」
 * 「带我去签到」，模型在回答末尾输出一条结构化指令：
 *
 *   [KEWU_ACTION]{"action":"open","target":"join_class"}[/KEWU_ACTION]
 *
 * [parse] 把指令从回答文本里剥出来，[execute] 在 App 内真正执行（导航 / 调业务接口），
 * 返回一句给用户看的结果说明，随 AI 气泡一起展示。
 *
 * ★ 安全边界（写进系统提示词，模型须遵守）：
 *   不能代写作业 / 不能代替签到答题 / 不能修改成绩或批阅结果 / 不能替考试。
 *   遇到这类请求要礼貌拒绝并引导用户自己完成。
 *
 * 角色差异：[studentCapability] / [teacherCapability] 分别描述两端可用操作；
 * 指令集保持同名同构，target 取值见下方常量。
 */
object AiAgent {

    /**
     * 一条操作指令。
     * [target] / [value] 是主参数；其余键值（deadline / score / points / description …）
     * 全收进 [args]，这样「发布作业」这类多参数动作不用给每个字段单开一列。
     */
    data class Action(
        val action: String,
        val target: String = "",
        val value: String = "",
        val args: Map<String, String> = emptyMap(),
    )

    /** 剥指令后的干净文本 + 解析出的动作（null = 纯回答）。 */
    data class Parsed(val text: String, val action: Action?)

    private val json = Json { ignoreUnknownKeys = true }

    private const val MARK_OPEN = "[KEWU_ACTION]"
    private const val MARK_CLOSE = "[/KEWU_ACTION]"

    // ── 动作词汇（target 常量） ──
    // go_tab.target: home | assistant | tasks | mine
    const val TAB_HOME = "home"          // 学生=课堂，教师=控制台
    const val TAB_ASSISTANT = "assistant"
    const val TAB_TASKS = "tasks"
    const val TAB_MINE = "mine"

    // open.target（学生）
    const val OPEN_JOIN_CLASS = "join_class"     // 加入班级（我的-加入班级）
    const val OPEN_BIND_COURSE = "bind_course"   // 课程绑定
    const val OPEN_RECORDINGS = "recordings"     // 录音管理
    const val OPEN_PROFILE = "profile"           // 编辑资料
    const val OPEN_NOTIFICATIONS = "notifications"
    const val OPEN_ABOUT = "about"

    // open.target（教师）
    const val OPEN_CREATE_ATTENDANCE = "create_attendance" // 发起签到
    const val OPEN_POST_NOTE = "post_note"                 // 发布笔记
    const val OPEN_NEW_COURSE = "new_course"               // 新建课程
    const val OPEN_CLASS_MANAGE = "class_manage"           // 班级管理
    const val OPEN_PUBLISH_ASSIGNMENT = "publish_assignment" // 打开「作业」页发布表单

    // 直达 API 动作（教师）：action = publish_assignment（value=标题，target=课程名/ID 可选）

    // 直达 API 动作：action = join_class（value=6位邀请码）/ bind_course（value=课程ID）

    // ── 解析 ──

    /** 流式输出过程中可直接上屏的部分：动作指令块（及其未写完的前缀）不显示。 */
    fun visiblePart(raw: String): String {
        val i = raw.indexOf(MARK_OPEN)
        if (i >= 0) return raw.substring(0, i).trimEnd()
        // 末尾可能是「[KEWU_AC」这种还没写完的标记前缀
        for (k in MARK_OPEN.length - 1 downTo 1) {
            if (raw.endsWith(MARK_OPEN.substring(0, k))) return raw.dropLast(k).trimEnd()
        }
        return raw
    }

    fun parse(raw: String): Parsed {
        val s = raw.indexOf(MARK_OPEN)
        val e = raw.indexOf(MARK_CLOSE)
        if (s < 0 || e <= s) return Parsed(raw.trim(), null)
        val jsonStr = raw.substring(s + MARK_OPEN.length, e).trim()
        val text = (raw.substring(0, s) + raw.substring(e + MARK_CLOSE.length)).trim()
        val action = parseActionJson(jsonStr)
        return Parsed(if (text.isEmpty()) "" else text, action)
    }

    private fun parseActionJson(s: String): Action? = try {
        val o = json.parseToJsonElement(s).jsonObject
        val known = setOf("action", "target", "value")
        val args = o.entries.mapNotNull { (k, v) ->
            if (k in known) null
            else (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it }
        }.toMap()
        Action(
            action = o["action"]?.jsonPrimitive?.content ?: return null,
            target = o["target"]?.jsonPrimitive?.content ?: "",
            value = o["value"]?.jsonPrimitive?.content ?: "",
            args = args,
        )
    } catch (_: Exception) {
        null
    }

    // ── 执行（suspend：导航 + 业务接口） ──

    /**
     * 执行动作，返回给用户看的结果说明（已执行/打不开等）；未知动作返回 null（页面回退展示原回答）。
     */
    suspend fun execute(role: String, a: Action): String? = when (a.action) {
        "go_tab" -> {
            val idx = tabIndex(a.target)
            if (idx < 0) null
            else {
                if (role == "teacher") AiNav.goTeacherTab(idx) else AiNav.goStudentTab(idx)
                "已帮你切换到「${tabName(a.target, role)}」。"
            }
        }
        // 发布作业：教师端可直接代办（value=标题，target=课程名/课程ID 可选），
        // 也可只打开「作业」页让用户自己填表。
        "open" -> when (a.target) {
            OPEN_PUBLISH_ASSIGNMENT -> openPublishPage(role)
            else -> openTarget(role, a.target)
        }
        "publish_assignment" -> publishAssignment(role, a)
        "join_class" -> {
            if (role != "student") "加入班级是学生端功能，教师请在「班级管理」查看邀请码。"
            else {
                val code = a.value.trim()
                if (code.length < 4) "邀请码看起来不完整（需要老师发的 6 位邀请码），请确认后再发我一次。"
                else {
                    val r = KewuApi.joinClassByInvite(code)
                    if (r.data != null) "✅ ${r.data}"
                    else "加入班级没成功：${r.error ?: "网络异常"}。可以再试一次，或到「我的 → 加入班级」手动输入。"
                }
            }
        }
        "bind_course" -> {
            val cid = a.value.trim()
            if (cid.isEmpty()) {
                openTarget(role, OPEN_BIND_COURSE)
            } else {
                val err = KewuApi.bindCourse(cid)
                if (err == null) "✅ 已帮你绑定课程（ID：$cid）。"
                else "绑定课程没成功：$err。可以到「我的 → 课程绑定」查看已绑定课程并重试。"
            }
        }
        else -> null
    }

    /** 只跳到「作业」页让用户自己填发布表单（缺少标题/课程时的兜底）。 */
    private fun openPublishPage(role: String): String {
        if (role != "teacher") return "发布作业是教师端功能，学生端没有这个入口。"
        AiNav.goTeacherTab(tabIndex(TAB_TASKS)) // 教师端 tab 2 = 作业（批阅/发布）
        return "已帮你打开「作业」页，点右上角「发布」就能填写并发布作业；" +
            "也可以直接跟我说「发布作业：第 5 章习题」，我可以直接帮你发出去。"
    }

    /**
     * 直接代办发布作业（2026-10-24）。
     *   value  = 作业标题（必填）
     *   target = 课程名 / 课程 ID（可选，省略时用第一门课）
     *   args   = description|desc 说明，deadline 截止（如 2026-10-25 23:59），
     *            score|totalScore 总分（默认 100），points|knowledgePoints 知识点（逗号分隔）
     */
    private suspend fun publishAssignment(role: String, a: Action): String {
        if (role != "teacher") return "发布作业是教师端功能，学生端没有这个入口。"
        val title = a.value.trim().ifBlank { a.args["title"]?.trim().orEmpty() }
        if (title.isEmpty()) return openPublishPage(role)

        // ★ 2026-10-26：只用老师本人的课程（/user/me）。旧版本人课程为空时退回全站 getCourses() 再取 first()，
        //   可能把作业发到别人的课上。
        if (com.kewu.app.KewuApi.currentUser?.courses.isNullOrEmpty()) com.kewu.app.KewuApi.me(force = true)
        val courses: List<com.kewu.app.CourseInfo>? = com.kewu.app.KewuApi.currentUser?.courses
        if (courses.isNullOrEmpty()) {
            return "没找到你的课程，暂时没法发布作业。先到「我的 → 新建课程」建一门课，再让我帮你发。"
        }
        val want = a.target.trim().ifBlank { a.args["course"]?.trim().orEmpty() }
        val course = when {
            want.isEmpty() && courses.size == 1 -> courses.first()
            // 多门课又没指明：不替老师猜，问清楚再发
            want.isEmpty() -> return "你有 ${courses.size} 门课：${courses.joinToString("、") { it.name }}。" +
                "要发到哪一门？告诉我课程名，我就帮你发「$title」。"
            else -> courses.firstOrNull { it.id.equals(want, ignoreCase = true) || it.name.contains(want) }
                ?: return "没找到课程「$want」。你当前的课程有：${courses.joinToString("、") { it.name }}，" +
                    "告诉我其中一个，我就发到那门课。"
        }

        val args = a.args
        val description = (args["description"] ?: args["desc"] ?: "").trim()
        val deadline = (args["deadline"] ?: args["due"] ?: "").trim()
        val totalScore = (args["score"] ?: args["totalScore"])?.trim()?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
        val points = (args["points"] ?: args["knowledgePoints"]).orEmpty()
            .split(",", "，", "、", ";", "；")
            .map { it.trim() }.filter { it.isNotEmpty() }
        val publisher = com.kewu.app.KewuApi.currentUser?.user?.nickname
            ?: com.kewu.app.KewuApi.username ?: ""

        val r = com.kewu.app.KewuApi.publishAssignment(
            courseId = course.id, title = title, description = description,
            deadline = deadline, totalScore = totalScore,
            knowledgePoints = points, publisher = publisher,
        )
        return if (r != null) {
            buildString {
                append("✅ 已帮你在「${course.name}」发布作业「${r.title}」，总分 $totalScore")
                if (deadline.isNotEmpty()) append("，截止 $deadline")
                if (points.isNotEmpty()) append("，知识点：${points.joinToString("、")}")
                append("。到「作业」页就能看到提交情况。")
            }
        } else {
            "发布失败（网络异常或服务端拒绝）。" +
                "我也可以只带你去「作业」页，你手动填一次更稳妥。"
        }
    }

    private fun tabIndex(target: String): Int = when (target) {
        TAB_HOME -> 0
        TAB_ASSISTANT -> 1
        TAB_TASKS -> 2
        TAB_MINE -> 3
        else -> -1
    }

    private fun tabName(target: String, role: String): String = when (target) {
        TAB_HOME -> if (role == "teacher") "控制台" else "课堂"
        TAB_ASSISTANT -> "助教"
        TAB_TASKS -> if (role == "teacher") "作业" else "任务"
        TAB_MINE -> "我的"
        else -> target
    }

    /** open 动作：打开「我的」各子页（教师端子页编号与学生端对齐，见 TeacherMyPage 注释）。 */
    private fun openTarget(role: String, target: String): String? {
        val student = role != "teacher"
        val mapping: Pair<Int, String>? = when (target) {
            OPEN_JOIN_CLASS -> if (student) 4 to "加入班级" else null
            OPEN_BIND_COURSE -> 3 to "课程绑定"
            OPEN_RECORDINGS -> if (student) 2 to "录音管理" else null
            OPEN_PROFILE -> if (student) 1 to "编辑资料" else null
            OPEN_NOTIFICATIONS -> 6 to "通知设置"
            OPEN_ABOUT -> 7 to "关于我们"
            OPEN_CREATE_ATTENDANCE -> if (!student) 2 to "发起签到" else null
            OPEN_POST_NOTE -> if (!student) 3 to "发布笔记" else null
            OPEN_NEW_COURSE -> if (!student) 4 to "新建课程" else null
            OPEN_CLASS_MANAGE -> if (!student) 5 to "班级管理" else null
            else -> null
        }
        // 角色不支持时给句明确反馈，避免模型幻觉的 target 静默失败
        val screenAndName: Pair<Int, String> = mapping ?: return when (target) {
            OPEN_JOIN_CLASS, OPEN_RECORDINGS, OPEN_PROFILE ->
                "这个操作只在学生端提供，教师端没有对应入口。"
            OPEN_CREATE_ATTENDANCE, OPEN_POST_NOTE, OPEN_NEW_COURSE, OPEN_CLASS_MANAGE ->
                "这个操作只在教师端提供。"
            else -> null
        } ?: return null
        if (student) AiNav.openStudentMy(screenAndName.first)
        else AiNav.openTeacherMy(screenAndName.first)
        return "已帮你打开「${screenAndName.second}」。"
    }

    // ── 能力描述（拼进系统提示词） ──

    private const val PROTOCOL = """
你可以直接帮用户在软件里操作。当用户想让你「打开/带我去/帮我进入」某个功能时，
在回答末尾单独一行输出指令（不要放进代码块）：
[KEWU_ACTION]{"action":"动作","target":"目标","value":"参数"}[/KEWU_ACTION]

可用动作：
· 切换页面 {"action":"go_tab","target":"home|assistant|tasks|mine"}
· 打开功能页 {"action":"open","target":"功能标识"}
· 直接代办 {"action":"join_class","value":"6位邀请码"}、{"action":"bind_course","value":"课程ID"}

只有当回答确实要触发软件操作时才输出指令，且一次只输出一条；纯问答不要输出任何指令。

★ 绝对不能做的事（遇到要礼貌拒绝并引导用户自己完成）：
代写作业、代替签到或回答签到测验、修改/预测成绩、代替考试。你只能「打开页面」和「代办加入班级/绑定课程/发布作业」这类软件操作，学习内容本身必须由用户自己完成。
"""

    fun studentCapability(): String = PROTOCOL + """
你所在的环境是学生端课悟 App，可用的 open 目标：
join_class 加入班级（邀请码加入，也可直接代办）；bind_course 课程绑定；recordings 录音管理；
profile 编辑资料；notifications 通知设置；about 关于我们。
go_tab 页面：home=课堂（课程与签到码）、assistant=助教、tasks=任务（作业）、mine=我的。
用户说「加入班级」且有邀请码时优先直接代办 join_class；说「录音管理/我的录音」用 open recordings。
"""

    fun teacherCapability(): String = PROTOCOL + """
你所在的环境是教师端课悟 App，可用的 open 目标：
bind_course 课程绑定；create_attendance 发起签到；post_note 发布课堂笔记；
new_course 新建课程；class_manage 班级管理（查看邀请码）；notifications 通知设置；about 关于我们；
publish_assignment 打开「作业」页的发布表单（只带队，不代填）。
go_tab 页面：home=控制台（发起/结束签到、课堂录音）、assistant=助教、tasks=作业（批阅/发布）、mine=我的。
学生相关操作（加入班级、学生录音管理）不在教师端，不要输出对应指令。

★ 教师可以直接让你发布作业（重要）：
{"action":"publish_assignment","value":"作业标题","target":"课程名或课程ID（可省略，默认第一门课）",
 "description":"作业说明（可省略）","deadline":"2026-10-25 23:59（可省略）",
 "score":"100（可省略，默认100）","points":"知识点1,知识点2（可省略）"}
用户说「帮我发布作业：xxx」「发一份作业叫 xxx」「布置作业 xxx」时，只要标题明确就直接用这条指令代发，
不要回答「我无法发布作业」也不要只让用户自己去做。标题里用户提到的截止日期/总分/知识点一并填进对应字段。
用户完全没说标题时，才用 {"action":"open","target":"publish_assignment"} 带他去发布页。
"""
}
