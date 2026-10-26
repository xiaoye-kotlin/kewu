package com.kewu.app

import com.kewu.app.ui.ProfileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 小悟的「软件全景」上下文（2026-10-26 用户反馈：问「作业什么时候交」它都不知道）。
 *
 * 把 App 里用户能看到的信息整理成一段纯文本塞进 system 提示词：当前时间、个人资料、课程、
 * 每份作业（截止时间 / 是否已交 / 成绩 / 评语 / 题目数）、今天的签到、课堂录音状态等。
 * 优先用进程级缓存（CourseRepo / AssignmentRepo），缺什么再补拉；整体 6 秒超时，拉不到的部分如实写「暂无」，
 * 绝不让上下文拖住提问。
 */
object AiContext {

    private fun nowCn(): String {
        val ms = kotlin.time.Clock.System.now().toEpochMilliseconds() + 8 * 3_600_000L
        val min = (ms / 60_000L) % (24 * 60)
        fun p2(v: Long) = if (v < 10) "0$v" else "$v"
        val dow = listOf("周四", "周五", "周六", "周日", "周一", "周二", "周三")[((ms / 86_400_000L) % 7).toInt()]
        return "${todayCn()} ${p2(min / 60)}:${p2(min % 60)}（$dow）"
    }

    /** 学生端全景。 */
    suspend fun student(): String = withTimeoutOrNull(9_000) { studentInner() } ?: "（上下文加载超时，仅知道当前时间：${nowCn()}）"

    private suspend fun studentInner(): String = coroutineScope {
        val uname = KewuApi.username.orEmpty()
        // ★ 2026-10-26 修「问我的作业，小悟说没有作业」：旧版整体 6 秒超时，逐课查签到慢就整段丢弃。
        //   现在各块各自限时：作业 / 课程优先用已加载的缓存（任务页、首页早已拉过），拿不到再限时刷新；签到最多等 3 秒。
        val courses = CourseRepo.courses.value
            ?: withTimeoutOrNull(4_000) { CourseRepo.refresh() }.orEmpty()
        val items = AssignmentRepo.items.value?.takeIf { it.isNotEmpty() }
            ?: withTimeoutOrNull(5_000) { AssignmentRepo.refresh(maxAgeMs = 60_000) }
            ?: AssignmentRepo.items.value.orEmpty()
        val names = courses.associate { it.id to it.name }
        // 今天各门课的签到（有进行中的就告诉 AI 学生签没签）
        val attendance = withTimeoutOrNull(3_000) { courses.map { c ->
            async {
                KewuApi.getAttendances(c.id).orEmpty().filter { it.createdAt.startsWith(todayCn()) }.map { s ->
                    val mine = if (uname.isNotBlank()) KewuApi.getMyAttendance(s.id, uname) else null
                    "${c.name}：${s.title}（${if (s.state.equals("OPEN", true)) "进行中" else "已结束"}，" +
                        (if (mine != null) "我已签到 ${mine.checkedInAt.takeLast(8)}，状态 ${statusCn(mine.status)}" else "我未签到") + "）"
                }
            }
        }.awaitAll().flatten() } ?: listOf("（签到信息加载超时）")
        val profile = ProfileStore.load(uname)
        buildString {
            appendLine("【当前时间】${nowCn()}")
            appendLine("【我】${KewuApi.displayName("同学")}，学号 $uname" +
                (profile?.school?.takeIf { it.isNotBlank() }?.let { "，学校 $it" } ?: "") +
                (profile?.className?.takeIf { it.isNotBlank() }?.let { "，班级 $it" } ?: ""))
            appendLine("【我的课程（${courses.size} 门）】" +
                courses.joinToString("；") { "${it.name}（${it.teacher.ifBlank { "老师未知" }}）" }.ifBlank { "暂无" })
            appendLine("【我的作业（${items.size} 份）】")
            if (items.isEmpty()) appendLine("暂无作业")
            items.forEach { it ->
                val a = it.assignment
                val s = it.mine
                val st = when {
                    s == null -> "未提交"
                    s.status == "RETURNED" -> "被老师打回待重交" + (s.comment?.takeIf { c -> c.isNotBlank() }?.let { c -> "（评语：${c.take(60)}）" } ?: "")
                    s.status == "REVIEWED" -> "已批阅，得分 ${s.score ?: "—"}/${a.totalScore}" + (s.comment?.takeIf { c -> c.isNotBlank() }?.let { c -> "（评语：${c.take(60)}）" } ?: "")
                    else -> "已提交待批阅（第 ${s.version} 版，${s.submittedAt}）"
                }
                appendLine("· 《${a.title}》${names[a.courseId] ?: a.courseId}，截止 ${a.deadline.ifBlank { "未设置" }}，" +
                    "满分 ${a.totalScore}" + (if (a.questions.isNotEmpty()) "，${a.questions.size} 道题" else "") +
                    (a.knowledgePoints.takeIf { k -> k.isNotEmpty() }?.let { k -> "，知识点 ${k.joinToString("、")}" } ?: "") +
                    "，状态：$st")
            }
            appendLine("【今天的签到】" + attendance.joinToString("；").ifBlank { "今天还没有签到" })
            val rec = RecordingManager.status.value
            appendLine("【课堂录音】" + when (rec) {
                is RecordingStatus.Recording -> "正在录音（${rec.elapsedSec} 秒）"
                is RecordingStatus.Uploading -> "录音上传中"
                else -> "未在录音"
            })
        }
    }

    /** 教师端全景。 */
    suspend fun teacher(): String = withTimeoutOrNull(6_000) { teacherInner() } ?: "（上下文加载超时，仅知道当前时间：${nowCn()}）"

    private suspend fun teacherInner(): String = coroutineScope {
        val consoleD = async { KewuApi.getTeacherConsole() }
        val assignmentsD = async { KewuApi.getAssignments(null) }
        val pendingD = async { KewuApi.getPendingReview() }
        val classesD = async { KewuApi.getTeacherClasses() }
        if (KewuApi.currentUser == null) KewuApi.me()
        val console = consoleD.await()
        val classes = classesD.await()
        // 老师的课程：/user/me 绑定课 → 控制台今日课程 → 教学班所属课程（教师账号通常不在 /me 里绑课，
        // 旧版只看 /me，小悟回答「当前暂无课程」）
        val courses = (KewuApi.currentUser?.courses.orEmpty() +
            console?.todayCourses.orEmpty().map { CourseInfo(it.courseId, it.courseName, "", "") } +
            classes?.classes.orEmpty().map { CourseInfo(it.courseId, it.courseName, "", "") })
            .filter { it.id.isNotBlank() }.distinctBy { it.id }
        val myIds = courses.map { it.id }.toSet()
        val all = assignmentsD.await().orEmpty()
        val assignments = if (myIds.isEmpty()) all else all.filter { it.courseId in myIds }
        val names = courses.associate { it.id to it.name }
        val stats = assignments.take(12).map { a -> async { a to KewuApi.getAssignmentStats(a.id) } }.awaitAll()
        val pending = pendingD.await()
        buildString {
            appendLine("【当前时间】${nowCn()}")
            appendLine("【我】${KewuApi.displayName("老师")}（教师，工号 ${KewuApi.username.orEmpty()}）")
            appendLine("【我的课程（${courses.size} 门）】" + courses.joinToString("；") { it.name }.ifBlank { "暂无" })
            classes?.let { c ->
                appendLine("【教学班】" + c.classes.joinToString("；") { "${it.name}（${it.courseName}，${it.studentCount} 人，邀请码 ${it.inviteCode}）" }.ifBlank { "暂无" })
            }
            console?.let {
                appendLine("【今日】签到 ${it.todaySessionCount} 场，实到 ${it.checkedToday}/${it.expectedToday}，" +
                    "签到率 ${(it.todayCheckRate * 100).toInt()}%；待批阅 ${it.pendingReview} 份")
                it.todayCourses.forEach { t ->
                    appendLine("· ${t.courseName}：${t.className.ifBlank { "未建班" }}，${t.studentCount} 人，" +
                        (if (t.state == "ON") "上课中（${t.startedAt} 开始）" else "未上课"))
                }
            }
            appendLine("【我布置的作业（${assignments.size} 份）】")
            if (stats.isEmpty()) appendLine("暂无作业")
            stats.forEach { (a, st) ->
                appendLine("· 《${a.title}》${names[a.courseId] ?: a.courseId}，发布 ${a.createdAt}，截止 ${a.deadline.ifBlank { "未设置" }}，" +
                    "满分 ${a.totalScore}" + (if (a.questions.isNotEmpty()) "，${a.questions.size} 道题" else "") +
                    (st?.let { s -> "，已交 ${s.submittedCount} 人，已批 ${s.reviewedCount}，待批 ${s.pendingCount}，均分 ${s.avgScore}" } ?: ""))
            }
            pending?.let { p ->
                appendLine("【待批阅】共 ${p.total} 份，紧急 ${p.urgentCount} 份" +
                    p.items.take(5).joinToString("") { "；${it.studentName}《${it.assignmentTitle}》已等 ${it.waitHours.toInt()} 小时" })
            }
        }
    }

    private fun statusCn(s: String) = when (s.uppercase()) {
        "PRESENT", "ON_TIME" -> "按时"
        "LATE" -> "迟到"
        "ABSENT" -> "缺勤"
        else -> s
    }
}
