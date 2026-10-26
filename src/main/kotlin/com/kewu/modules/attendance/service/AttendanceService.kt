package com.kewu.modules.attendance.service

import com.kewu.modules.attendance.models.*
import com.kewu.modules.attendance.repository.AttendanceRepository
import com.kewu.modules.course.repository.CourseRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

// 签到模块的业务逻辑: 发起签到 -> 学生签到(动态码/暗号/AI问答) -> 记录统计 + 反代签风控
class AttendanceService(
    private val repository: AttendanceRepository,
    private val courseRepository: CourseRepository
) {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private fun now(): String = LocalDateTime.now().format(fmt)
    private val windowSec = 30L // 动态码多久换一次

    // ---------- 发起签到 ----------

    fun create(req: CreateAttendanceRequest): Result<AttendanceSession> {
        if (req.title.isBlank()) return Result.failure(IllegalArgumentException("签到标题不能为空"))
        if (!courseRepository.checkExists(req.courseId)) {
            return Result.failure(IllegalArgumentException("课程不存在: ${req.courseId}"))
        }
        val mode = req.mode.uppercase()
        if (mode !in listOf(AttendanceMode.QR, AttendanceMode.CODE, AttendanceMode.QUIZ)) {
            return Result.failure(IllegalArgumentException("签到方式只支持 QR / CODE / QUIZ"))
        }
        val start = LocalDateTime.now()
        val s = AttendanceSession(
            id = repository.nextId("t"),
            courseId = req.courseId,
            title = req.title,
            mode = mode,
            state = AttendanceState.OPEN,
            publisher = req.publisher,
            createdAt = start.format(fmt),
            expireAt = start.plusMinutes(req.durationMinutes.toLong().coerceAtLeast(1)).format(fmt),
            lateAfterSeconds = req.lateAfterSeconds,
            latitude = req.latitude,
            longitude = req.longitude,
            radiusMeters = req.radiusMeters,
            // QUIZ 模式: 题目直接从这门课的课堂纪要里生成, 签到顺便复习
            quiz = if (mode == AttendanceMode.QUIZ) buildQuiz(req.courseId) else null
        )
        repository.saveSession(s)
        return Result.success(sanitize(s))
    }

    // 答案只留在后端校验用, 返回给前端(尤其学生端)时要抠掉, 不然问答签到就没意义了
    private fun sanitize(s: AttendanceSession): AttendanceSession =
        if (s.quiz == null) s else s.copy(quiz = s.quiz.copy(acceptedAnswers = emptyList()))

    // ---------- 动态签到码(防截图代签) ----------

    fun currentCode(sessionId: String): Result<AttendanceCode> {
        val s = repository.findSession(sessionId)
            ?: return Result.failure(IllegalArgumentException("签到不存在"))
        if (s.state != AttendanceState.OPEN) {
            return Result.failure(IllegalArgumentException("这场签到已经结束了"))
        }
        val nowMs = System.currentTimeMillis()
        val w = nowMs / (windowSec * 1000)
        val code = codeFor(sessionId, w)
        val remaining = (windowSec - (nowMs / 1000) % windowSec).toInt()
        return Result.success(
            AttendanceCode(
                sessionId = sessionId,
                code = code,
                qrContent = "kewusign://attend?sid=$sessionId&code=$code",
                windowSeconds = windowSec.toInt(),
                remainingSeconds = remaining
            )
        )
    }

    // 每 30 秒换一个 6 位数, 拍照片发给同学也没用
    private fun codeFor(sessionId: String, window: Long): String {
        val h = ("$sessionId|$window|kewu").hashCode()
        return ((h and 0x7fffffff) % 1000000).toString().padStart(6, '0')
    }

    // 容错: 当前窗口和上一个窗口的码都算有效, 免得网络一卡就签不上
    private fun verifyCode(sessionId: String, code: String): Boolean {
        val w = System.currentTimeMillis() / (windowSec * 1000)
        val input = code.trim()
        return (0..1).any { codeFor(sessionId, w - it) == input }
    }

    // ---------- 学生签到 ----------

    fun checkIn(sessionId: String, req: CheckInRequest): Result<AttendanceRecord> {
        val s = repository.findSession(sessionId)
            ?: return Result.failure(IllegalArgumentException("签到不存在"))
        if (s.state != AttendanceState.OPEN) {
            return Result.failure(IllegalArgumentException("这场签到已经结束了"))
        }
        if (req.studentId.isBlank()) return Result.failure(IllegalArgumentException("缺少 studentId"))
        if (repository.findRecordOf(sessionId, req.studentId) != null) {
            return Result.failure(IllegalArgumentException("你已经签过到了"))
        }

        // ① 码校验: QR / CODE 模式必须带上当前有效的码
        if (s.mode != AttendanceMode.QUIZ) {
            if (req.code.isBlank()) return Result.failure(IllegalArgumentException("请填写签到码"))
            if (!verifyCode(sessionId, req.code)) {
                return Result.failure(IllegalArgumentException("签到码不对或已过期, 请重新获取"))
            }
        }

        // ② AI 问答签到: 答对才算签到成功, 这是防代签的关键
        var answer: String? = null
        if (s.mode == AttendanceMode.QUIZ) {
            val q = s.quiz ?: return Result.failure(IllegalArgumentException("这场签到没有题目"))
            if (req.answer.isBlank()) return Result.failure(IllegalArgumentException("请回答随堂问题"))
            val ok = q.acceptedAnswers.any { it.equals(req.answer.trim(), true) } ||
                    q.acceptedAnswers.any { req.answer.contains(it, true) }
            if (!ok) return Result.failure(IllegalArgumentException("回答不在本节课的关键词里, 再想想? 提示: ${q.hint}"))
            answer = req.answer.trim()
        }

        // ③ 位置校验 + 设备风控, 算出可疑分
        val seconds = (System.currentTimeMillis() - repository.startMillis(sessionId)) / 1000
        val reasons = mutableListOf<String>()
        var risk = 0

        val dist = if (s.latitude != null && s.longitude != null && req.latitude != null && req.longitude != null) {
            distance(s.latitude, s.longitude, req.latitude, req.longitude)
        } else null
        if (dist != null && dist > s.radiusMeters) {
            risk += 40
            reasons += "签到位置离课堂约 ${dist.toInt()} 米, 超出教室范围(${s.radiusMeters} 米)"
        }
        val dev = req.deviceId?.takeIf { it.isNotBlank() }
        if (dev != null) {
            val sameDevice = repository.listRecords(sessionId).any { it.deviceId == dev && it.studentId != req.studentId }
            if (sameDevice) {
                risk += 50
                reasons += "设备 $dev 上已有其他同学签到, 疑似代签"
            }
        }
        if (seconds < 2) {
            risk += 30
            reasons += "发起后 ${seconds} 秒就签到了, 速度异常"
        }

        val status = if (seconds > s.lateAfterSeconds) CheckInStatus.LATE else CheckInStatus.NORMAL
        if (status == CheckInStatus.LATE) reasons += "超过 ${s.lateAfterSeconds} 秒, 记为迟到"

        val r = AttendanceRecord(
            id = repository.nextId("r"),
            sessionId = sessionId,
            studentId = req.studentId,
            studentName = req.studentName.ifBlank { req.studentId },
            checkedInAt = now(),
            status = status,
            method = s.mode,
            answer = answer,
            secondsAfterStart = seconds,
            distanceMeters = dist?.let { round1(it) },
            deviceId = dev,
            riskScore = risk.coerceAtMost(100),
            riskReasons = reasons
        )
        repository.saveRecord(r)
        repository.addToRoster(s.courseId, req.studentId)
        return Result.success(r)
    }

    // ---------- 查询 / 统计 ----------

    fun listSessions(courseId: String?): List<AttendanceSession> =
        repository.listSessions(courseId).map { sanitize(it) }

    fun detail(sessionId: String): Result<AttendanceDetail> {
        val s = repository.findSession(sessionId)
            ?: return Result.failure(IllegalArgumentException("签到不存在"))
        return Result.success(AttendanceDetail(sanitize(s), buildStats(s)))
    }

    fun records(sessionId: String): Result<List<AttendanceRecord>> {
        if (repository.findSession(sessionId) == null) {
            return Result.failure(IllegalArgumentException("签到不存在"))
        }
        return Result.success(repository.listRecords(sessionId))
    }

    fun my(sessionId: String, studentId: String): Result<AttendanceRecord> {
        val r = repository.findRecordOf(sessionId, studentId)
            ?: return Result.failure(IllegalArgumentException("你还没签到"))
        return Result.success(r)
    }

    fun close(sessionId: String): Result<AttendanceSession> {
        val s = repository.findSession(sessionId)
            ?: return Result.failure(IllegalArgumentException("签到不存在"))
        val closed = s.copy(state = AttendanceState.CLOSED)
        repository.saveSession(closed, repository.startMillis(sessionId))
        return Result.success(sanitize(closed))
    }

    fun stats(sessionId: String): Result<AttendanceStats> {
        val s = repository.findSession(sessionId)
            ?: return Result.failure(IllegalArgumentException("签到不存在"))
        return Result.success(buildStats(s))
    }

    // 疑似代签名单
    fun riskList(sessionId: String): Result<List<RiskItem>> {
        if (repository.findSession(sessionId) == null) {
            return Result.failure(IllegalArgumentException("签到不存在"))
        }
        val items = repository.listRecords(sessionId)
            .filter { it.riskScore >= 30 }
            .map {
                RiskItem(
                    recordId = it.id, studentId = it.studentId, studentName = it.studentName,
                    riskScore = it.riskScore, reasons = it.riskReasons, checkedInAt = it.checkedInAt
                )
            }
            .sortedByDescending { it.riskScore }
        return Result.success(items)
    }

    // 实时看板: 给老师看"现在签了多少人了"
    fun realtime(sessionId: String): Result<RealtimeBoard> {
        val s = repository.findSession(sessionId)
            ?: return Result.failure(IllegalArgumentException("签到不存在"))
        val list = repository.listRecords(sessionId)
        val expected = repository.rosterOf(s.courseId).size.coerceAtLeast(list.size)
        val elapsed = (System.currentTimeMillis() - repository.startMillis(sessionId)) / 1000
        return Result.success(
            RealtimeBoard(
                sessionId = sessionId,
                expected = expected,
                checked = list.size,
                checkRate = if (expected == 0) 0.0 else round2(list.size.toDouble() / expected),
                elapsedSeconds = elapsed,
                latest = list.sortedByDescending { it.secondsAfterStart }.take(5)
                    .map { RecentCheckIn(it.studentName, it.secondsAfterStart, it.status) }
            )
        )
    }

    private fun buildStats(s: AttendanceSession): AttendanceStats {
        val list = repository.listRecords(s.id)
        val expected = repository.rosterOf(s.courseId).size.coerceAtLeast(list.size)
        val checked = list.size
        val late = list.count { it.status == CheckInStatus.LATE }
        val maxMinute = (list.maxOfOrNull { it.secondsAfterStart } ?: 0L) / 60 + 1
        val timeline = (0..maxMinute.toInt()).map { m ->
            TimePoint(m, list.count { it.secondsAfterStart / 60 <= m })
        }
        return AttendanceStats(
            sessionId = s.id,
            title = s.title,
            mode = s.mode,
            expected = expected,
            checked = checked,
            late = late,
            absent = (expected - checked).coerceAtLeast(0),
            checkRate = if (expected == 0) 0.0 else round2(checked.toDouble() / expected),
            avgSeconds = if (list.isEmpty()) null else round1(list.map { it.secondsAfterStart }.average()),
            timeline = timeline,
            methodBreakdown = list.groupBy { it.method }.map { MethodCount(it.key, it.value.size) }
        )
    }

    // 从这门课最近的课堂纪要里抠关键词, 自动生成一道随堂小题
    private fun buildQuiz(courseId: String): QuizQuestion {
        val notes = courseRepository.listNotes(courseId)
        val note = notes.lastOrNull()
        if (note == null) {
            return QuizQuestion(
                question = "这门课还没课堂纪要, 先随便写个本节课的关键词吧",
                hint = "自由发挥", acceptedAnswers = listOf("Kotlin", "华为", "课堂"), sourceNoteTitle = ""
            )
        }
        val text = note.title + " " + note.content
        val en = Regex("[A-Za-z]{2,}").findAll(text).map { it.value }.distinct().toList()
        val cn = Regex("[\\u4e00-\\u9fa5]{2,4}").findAll(text).map { it.value }.distinct()
            .filter { w -> STOP_WORDS.none { w.contains(it) } }.toList()
        val answers = (en + cn).distinct().take(12)
        return QuizQuestion(
            question = "刚讲的《${note.title}》里提到过什么? 写出任意一个关键词就算签到成功",
            hint = answers.firstOrNull() ?: "看看纪要里出现的词",
            acceptedAnswers = answers,
            sourceNoteTitle = note.title
        )
    }

    // 出题时要滤掉的口水词
    private val STOP_WORDS = listOf("本节", "讲解", "我们", "这个", "就是", "可以", "怎么", "以及", "顺便", "里提", "基础")

    // 两点球面距离(米)
    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
