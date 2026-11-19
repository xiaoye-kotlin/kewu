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
/**
 * 一场签到的完整明细（2026-11-19 用户："给 AI 的数据一定要全面，包括签到名单和未签到名单"）。
 * [present] 已签到（含状态/签到时间）、[absent] 未签到/缺勤；两者都带联系方式，
 * 这样老师问「谁没来」「某某的电话/邮箱」时小悟能直接答上来。
 */
data class AttendanceRosterInfo(
    val courseName: String,
    val className: String,
    val sessionTitle: String,
    val createdAt: String,
    val present: List<RosterStudentInfo>,
    val absent: List<RosterStudentInfo>,
)

data class RosterStudentInfo(
    val name: String,
    val studentNo: String,
    val phone: String?,
    val email: String?,
    val status: String = "",
    val checkedInAt: String = "",
)

object AiContext {

    private fun nowCn(): String {
        val ms = kotlin.time.Clock.System.now().toEpochMilliseconds() + 8 * 3_600_000L
        val min = (ms / 60_000L) % (24 * 60)
        fun p2(v: Long) = if (v < 10) "0$v" else "$v"
        val dow = listOf("周四", "周五", "周六", "周日", "周一", "周二", "周三")[((ms / 86_400_000L) % 7).toInt()]
        return "${todayCn()} ${p2(min / 60)}:${p2(min % 60)}（$dow）"
    }

    /**
     * ★ 2026-11-19：某门课「最近一场签到」的完整名单明细。
     *
     * 数据源：
     * - 签到记录 [KewuApi.getAttendanceRecords] → 已签到的学生（姓名/学号/状态/签到时刻）
     * - 班级通讯录 [KewuApi.getClassContacts] → 全班学生（姓名/学号/电话/邮箱）
     * 以学号(studentId/studentNo)为键做差集，算出「未签到（缺勤）」名单，并给两方都附上联系方式。
     *
     * 兼容：签到记录的 studentId 是登录账号（学号）；通讯录的 studentNo 也是学号。
     * 若某学生签到过但不在通讯录（如退班），仍进已签到名单；若通讯录有但没签到记录，进缺勤名单。
     * 单门课最多取 [LIMIT] 人，避免 system prompt 过长。
     */
    suspend fun attendanceDetail(courseId: String, courseName: String, classId: String?, className: String): AttendanceRosterInfo? =
        withTimeoutOrNull(5_000) {
            val sessions = KewuApi.getAttendances(courseId).orEmpty()
            val last = sessions.maxByOrNull { it.createdAt } ?: return@withTimeoutOrNull null
            val records = KewuApi.getAttendanceRecords(last.id).orEmpty()
            val contacts = if (classId.isNullOrBlank()) emptyList()
            else KewuApi.getClassContacts(classId).first.orEmpty()

            // 通讯录按学号建索引（补联系方式用）
            val contactByNo = contacts.associateBy { it.studentNo }
            val LIMIT = 60

            val present = records.map { r ->
                val c = contactByNo[r.studentId]
                RosterStudentInfo(
                    name = r.studentName.ifBlank { c?.name ?: r.studentId },
                    studentNo = r.studentId,
                    phone = c?.phone, email = c?.email,
                    status = statusCn(r.status),
                    checkedInAt = r.checkedInAt.takeLast(8),
                )
            }.take(LIMIT)

            val checkedNos = records.map { it.studentId }.toSet()
            // 缺勤 = 通讯录里没出现在签到记录中的学生
            val absent = contacts.filter { it.studentNo !in checkedNos }.map { c ->
                RosterStudentInfo(c.name, c.studentNo, c.phone, c.email, "缺勤", "")
            }.take(LIMIT)

            AttendanceRosterInfo(courseName, className, last.title, last.createdAt, present, absent)
        }

    /** 把一场签到明细渲染成注入 system prompt 的文本（已签到 / 未签到两行，带联系方式）。 */
    fun formatAttendanceDetail(d: AttendanceRosterInfo): String = buildString {
        append("《${d.className.ifBlank { d.courseName }}》${d.sessionTitle}（${d.createdAt.take(16)}）：")
        append("应到 ${d.present.size + d.absent.size} · 实到 ${d.present.size} · 缺勤 ${d.absent.size}；")
        if (d.present.isNotEmpty()) {
            append("【已签到】" + d.present.joinToString("、") { s ->
                s.name + "（学号 ${s.studentNo}" +
                    (s.status.takeIf { it.isNotBlank() }?.let { "，$it ${s.checkedInAt}" } ?: "") +
                    contactSuffix(s) + "）"
            } + "；")
        }
        if (d.absent.isNotEmpty()) {
            append("【未签到/缺勤】" + d.absent.joinToString("、") { s ->
                s.name + "（学号 ${s.studentNo}" + contactSuffix(s) + "）"
            })
        } else if (d.present.isNotEmpty()) {
            append("【未签到/缺勤】无（全班都签到了）")
        }
    }

    private fun contactSuffix(s: RosterStudentInfo): String = buildString {
        s.phone?.takeIf { it.isNotBlank() }?.let { append("，电话 $it") }
        s.email?.takeIf { it.isNotBlank() }?.let { append("，邮箱 $it") }
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
        // 课堂内容：下课后服务器 SIS 转写 + AI 纪要（每门课最近 2 节），小悟据此知道老师讲了什么
        val classNotes = withTimeoutOrNull(4_000) {
            courses.map { c -> async { c.name to KewuApi.getClassNotes(c.id, 2) } }.awaitAll()
        }.orEmpty().filter { it.second.isNotEmpty() }
        // ★ C1（2026-11-05）：课表（上课时间/地点/周次/任课老师）——用户「问老师课程时间安排、在哪上课都不知道」
        val schedule = withTimeoutOrNull(3_000) { KewuApi.getMySchedule().orEmpty() }.orEmpty()
        val profile = ProfileStore.load(uname)
        buildString {
            appendLine("【当前时间】${nowCn()}")
            // ★ W7（2026-11-04 晚 3）：注入学生收藏的 B 站合集 —— 用户「AI 也能知道我收藏的内容」。
            //   AiContext.student() 被助教页 / 通话页 / 各问小悟页共用，一处注入即可全覆盖。
            com.kewu.app.ui.BiliFavorites.contextLine().takeIf { it.isNotBlank() }?.let { appendLine(it) }
            appendLine("【我】${KewuApi.displayName("同学")}，学号 $uname" +
                (profile?.school?.takeIf { it.isNotBlank() }?.let { "，学校 $it" } ?: "") +
                (profile?.className?.takeIf { it.isNotBlank() }?.let { "，班级 $it" } ?: ""))
            appendLine("【我的课程（${courses.size} 门）】" +
                courses.joinToString("；") { "${it.name}（${it.teacher.ifBlank { "老师未知" }}）" }.ifBlank { "暂无" })
            // ★ C1：课表全貌——每节课的星期/时间/地点/任课老师，AI 据此能答「xx 老师的课什么时候、在哪上」
            val wdNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            appendLine("【我的课表（共 ${schedule.size} 节）】" +
                schedule.sortedWith(compareBy({ it.weekday }, { it.startTime })).take(48)
                    .joinToString("；") { e ->
                        buildString {
                            append(wdNames.getOrElse(e.weekday - 1) { "周${e.weekday}" })
                            append(" ").append(e.startTime).append("-").append(e.endTime)
                            append("《").append(e.courseName).append("》")
                            e.location.takeIf { it.isNotBlank() }?.let { append(" @").append(it) }
                            e.teacherName.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
                            e.weeks.takeIf { it.isNotBlank() }?.let { append("〔").append(it).append(" 周〕") }
                        }
                    }.ifBlank { "暂无课表" })
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
            appendLine("【最近课堂内容（课堂录音转写后的 AI 纪要，辅导时以此为准，结合老师讲过的方法讲解）】")
            if (classNotes.isEmpty()) appendLine("暂无（下课后录音转写完成才会出现）")
            classNotes.forEach { (course, notes) ->
                notes.forEach { (date, text) -> appendLine("·《$course》$date：" + text.take(800)) }
            }
            val rec = RecordingManager.status.value
            appendLine("【课堂录音】" + when (rec) {
                is RecordingStatus.Recording -> "正在录音（${rec.elapsedSec} 秒）"
                is RecordingStatus.Uploading -> "录音上传中"
                else -> "未在录音"
            })
        }
    }

    /** 教师端全景。 */
    suspend fun teacher(): String = withTimeoutOrNull(10_000) { teacherInner() } ?: "（上下文加载超时，仅知道当前时间：${nowCn()}）"

    private suspend fun teacherInner(): String = coroutineScope {
        val consoleD = async { KewuApi.getTeacherConsole() }
        val assignmentsD = async { KewuApi.getAssignments(null) }
        val pendingD = async { KewuApi.getPendingReview() }
        val classesD = async { KewuApi.getTeacherClasses() }
        if (KewuApi.currentUser == null) KewuApi.me()
        val console = consoleD.await()
        val classes = classesD.await()
        // ★ C1（2026-11-05）：老师自己的课表 + 各教学班学生名单（姓名/学号/联系方式，老师本就有权查看）——
        //   用户「把软件中所有信息都告诉 AI，了解所有用户信息」。
        val scheduleT = async { withTimeoutOrNull(3_000) { KewuApi.getMySchedule().orEmpty() }.orEmpty() }
        val rosters = withTimeoutOrNull(6_000) {
            classes?.classes.orEmpty().map { cls -> async {
                cls.name to (KewuApi.getClassContacts(cls.id).first.orEmpty())
            } }.awaitAll()
        }.orEmpty()
        // ★ 2026-11-19：每个教学班最近一场签到的完整名单（已签到/未签到 + 联系方式）。
        //   这是「谁没签到」「某某电话多少」能答上来的关键——原来只有 stats 数字，没有姓名。
        val attendanceDetails = withTimeoutOrNull(7_000) {
            classes?.classes.orEmpty().map { cls -> async {
                attendanceDetail(cls.courseId, cls.courseName.ifBlank { cls.name }, cls.id, cls.name)
            } }.awaitAll().filterNotNull()
        }.orEmpty()
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
            // ★ C1：老师自己的课表（上课时间/地点/周次）
            val wdNamesT = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            val schedT = scheduleT.await()
            appendLine("【我的课表（共 ${schedT.size} 节）】" +
                schedT.sortedWith(compareBy({ it.weekday }, { it.startTime })).take(48)
                    .joinToString("；") { e ->
                        buildString {
                            append(wdNamesT.getOrElse(e.weekday - 1) { "周${e.weekday}" })
                            append(" ").append(e.startTime).append("-").append(e.endTime)
                            append("《").append(e.courseName).append("》")
                            e.location.takeIf { it.isNotBlank() }?.let { append(" @").append(it) }
                            e.weeks.takeIf { it.isNotBlank() }?.let { append("〔").append(it).append(" 周〕") }
                        }
                    }.ifBlank { "暂无课表" })
            // ★ C1：各教学班学生名单（老师端接口，含联系方式——老师在 app 里本来就能看到）
            rosters.filter { it.second.isNotEmpty() }.forEach { (clsName, students) ->
                appendLine("【$clsName 学生名单（${students.size} 人）】" +
                    students.take(80).joinToString("；") { s ->
                        buildString {
                            append(s.name)
                            s.studentNo.takeIf { it.isNotBlank() }?.let { append("（学号 ").append(it).append("）") }
                            s.phone?.takeIf { it.isNotBlank() }?.let { append(" 电话 ").append(it) }
                            s.email?.takeIf { it.isNotBlank() }?.let { append(" 邮箱 ").append(it) }
                        }
                    })
            }
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
            // ★ 2026-11-19：签到名单明细（谁签到 / 谁没签到 + 联系方式）——老师问「缺勤的是谁」「某某电话」直接据此回答。
            if (attendanceDetails.isNotEmpty()) {
                appendLine("【最近签到名单明细（含未签到学生及联系方式，务必以此为准回答「谁没签到/谁缺勤/某同学联系方式」）】")
                attendanceDetails.forEach { d -> appendLine("· " + formatAttendanceDetail(d)) }
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
