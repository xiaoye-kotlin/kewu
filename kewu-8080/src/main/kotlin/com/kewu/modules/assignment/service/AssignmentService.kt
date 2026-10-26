package com.kewu.modules.assignment.service

import com.kewu.modules.assignment.models.*
import com.kewu.modules.assignment.repository.AssignmentRepository
import com.kewu.modules.course.repository.CourseRepository
import com.kewu.modules.teacher.repository.TeacherRepository
import com.kewu.modules.user.repository.UserRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 作业模块的业务逻辑: 发布 -> 提交(多版本) -> 批阅, 外加 AI 预批阅 / 查重 / 学情画像 / 跨课程待批阅聚合
class AssignmentService(
    private val repository: AssignmentRepository,
    private val courseRepository: CourseRepository,
    private val teacherRepository: TeacherRepository,
    private val userRepository: UserRepository,
    private val uploadRepository: com.kewu.modules.upload.repository.UploadRepository
) {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun now(): String = LocalDateTime.now().format(fmt)

    // ---------- 发布作业 ----------

    fun publish(req: CreateAssignmentRequest): Result<Assignment> {
        if (req.title.isBlank()) return Result.failure(IllegalArgumentException("作业标题不能为空"))
        if (!courseRepository.checkExists(req.courseId)) {
            return Result.failure(IllegalArgumentException("课程不存在: ${req.courseId}"))
        }
        validateQuestions(req.questions).onFailure { return Result.failure(it) }
        val a = Assignment(
            id = repository.nextId("a"),
            courseId = req.courseId,
            title = req.title,
            description = req.description,
            deadline = req.deadline,
            totalScore = if (req.totalScore <= 0) 100 else req.totalScore,
            knowledgePoints = req.knowledgePoints,
            publisher = req.publisher,
            createdAt = now(),
            questions = req.questions,
            targetStudents = req.targetStudents.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        )
        repository.createAssignment(a)
        return Result.success(a)
    }

    // 发布时把题目过一遍, 免得存进去一堆前端渲染不出来的脏数据
    private fun validateQuestions(list: List<Question>): Result<Unit> {
        val ids = mutableSetOf<String>()
        list.forEachIndexed { i, q ->
            val at = "第 ${i + 1} 题"
            if (q.id.isBlank()) return Result.failure(IllegalArgumentException("$at 缺少 id"))
            if (!ids.add(q.id)) return Result.failure(IllegalArgumentException("$at id 和前面重复了: ${q.id}"))
            if (q.type !in QuestionType.ALL) {
                return Result.failure(IllegalArgumentException("$at 题型不对: ${q.type}, 只能是 ${QuestionType.ALL.joinToString(" / ")}"))
            }
            if (q.stem.isBlank()) return Result.failure(IllegalArgumentException("$at 题干不能为空"))
            if (q.score < 0) return Result.failure(IllegalArgumentException("$at 分值不能是负数"))

            when (q.type) {
                QuestionType.SINGLE_CHOICE -> {
                    if (q.options.size < 2) return Result.failure(IllegalArgumentException("$at 单选题至少给 2 个选项"))
                    if (q.answer.isBlank()) {
                        return Result.failure(IllegalArgumentException("$at 单选题必须给正确答案(填 A/B/C/D 这种选项序号)"))
                    }
                    if (!optionIndexOk(normChoice(q.answer), q.options.size)) {
                        return Result.failure(IllegalArgumentException("$at 单选题答案 ${q.answer} 不在选项范围内"))
                    }
                }
                QuestionType.MULTIPLE_CHOICE -> {
                    if (q.options.size < 2) return Result.failure(IllegalArgumentException("$at 多选题至少给 2 个选项"))
                    if (q.answer.isBlank()) {
                        return Result.failure(IllegalArgumentException("$at 多选题必须给正确答案(多个用逗号隔开, 比如 A,C)"))
                    }
                    val parts = q.answer.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (parts.isEmpty()) return Result.failure(IllegalArgumentException("$at 多选题答案不能为空"))
                    parts.forEach { p ->
                        if (!optionIndexOk(normChoice(p), q.options.size)) {
                            return Result.failure(IllegalArgumentException("$at 多选题答案 $p 不在选项范围内"))
                        }
                    }
                }
                QuestionType.TRUE_FALSE -> {
                    if (normBool(q.answer) == null) {
                        return Result.failure(IllegalArgumentException("$at 判断题答案只能是 true 或 false, 现在是 ${q.answer}"))
                    }
                }
            }
        }
        return Result.success(Unit)
    }

    private fun optionIndexOk(s: String, size: Int): Boolean {
        if (s.length != 1) return false
        val idx = s[0] - 'A'
        return idx in 0 until size
    }

    fun listAssignments(courseId: String?): List<Assignment> = repository.listAssignments(courseId)

    fun getDetail(assignmentId: String): Result<AssignmentDetail> {
        val a = repository.findAssignment(assignmentId)
            ?: return Result.failure(IllegalArgumentException("作业不存在"))
        return Result.success(AssignmentDetail(a, buildStats(a)))
    }

    // ---------- 提交作业(可以交很多次, 每次留一个版本) ----------

    fun submit(assignmentId: String, req: SubmitRequest): Result<Submission> {
        val a = repository.findAssignment(assignmentId)
            ?: return Result.failure(IllegalArgumentException("作业不存在"))
        if (req.studentId.isBlank()) return Result.failure(IllegalArgumentException("缺少 studentId"))
        if (req.content.isBlank()) return Result.failure(IllegalArgumentException("提交内容不能为空"))
        if (req.attachments.size > 9) return Result.failure(IllegalArgumentException("附件最多 9 个"))
        // 附件必须是 /api/uploads 真存过的（按 id 回查并以服务端记录为准），防止塞任意外链
        val attachments = req.attachments.map { att ->
            uploadRepository.find(att.id)
                ?: return Result.failure(IllegalArgumentException("附件「${att.name.ifBlank { att.id }}」无效，请重新上传"))
        }
        val durationSec = req.durationSec?.takeIf { it in 1..(7 * 24 * 3600) }

        val old = repository.findSubmissionOf(assignmentId, req.studentId)
        val version = (old?.version ?: 0) + 1

        // 有题目就顺手把客观题判了, 老师能少改一大半
        val results = judgeQuestions(a, req.answers)
        val objFull = a.questions.filter { QuestionType.isObjective(it.type) }.sumOf { it.score }
        val objScore = results.filter { it.judgedBy == JudgedBy.AUTO }.sumOf { it.autoScore }
        val ai = aiSuggestion(a, req.content, othersOf(assignmentId, req.studentId)).let {
            if (results.isEmpty()) it else it.copy(
                questionResults = results,
                objectiveScore = objScore,
                objectiveFullScore = objFull
            )
        }

        val history = (old?.history ?: emptyList()) + SubmissionVersion(version, req.content, now(), ai, attachments, durationSec)
        val s = Submission(
            id = old?.id ?: repository.nextId("s"),
            assignmentId = assignmentId,
            studentId = req.studentId,
            studentName = req.studentName.ifBlank { old?.studentName ?: req.studentId },
            content = req.content,
            version = version,
            submittedAt = now(),
            status = SubmissionStatus.PENDING, // 交完就回到待批阅
            score = old?.score,               // 保留上次成绩, 但状态是待批
            comment = old?.comment.orEmpty(),
            reviewer = old?.reviewer.orEmpty(),
            reviewedAt = old?.reviewedAt.orEmpty(),
            history = history,
            ai = ai,
            answers = req.answers,
            questionResults = results,
            attachments = attachments,
            durationSec = durationSec,
            aiHelp = req.aiHelp.filter { it.count > 0 }.take(100)
        )
        repository.saveSubmission(s)
        return Result.success(s)
    }

    // 逐题判定: 选择/判断/填空机器改, 简答留给老师
    private fun judgeQuestions(a: Assignment, answers: List<QuestionAnswer>): List<QuestionResult> {
        if (a.questions.isEmpty() || answers.isEmpty()) return emptyList()
        val map = answers.associate { it.questionId to it.value }
        return a.questions.map { q ->
            val sv = map[q.id].orEmpty()
            val base = QuestionResult(
                questionId = q.id, type = q.type, stem = q.stem,
                studentValue = sv, standardAnswer = q.answer, fullScore = q.score
            )
            when {
                !QuestionType.isObjective(q.type) -> base.copy(judgedBy = JudgedBy.PENDING)
                sv.isBlank() -> base.copy(correct = false, autoScore = 0.0, judgedBy = JudgedBy.AUTO)
                else -> {
                    val ok = answerMatches(q.type, sv, q.answer)
                    base.copy(
                        correct = ok,
                        autoScore = if (ok) q.score.toDouble() else 0.0,
                        judgedBy = JudgedBy.AUTO
                    )
                }
            }
        }
    }

    private fun answerMatches(type: String, student: String, standard: String): Boolean = when (type) {
        QuestionType.SINGLE_CHOICE -> normChoice(student) == normChoice(standard)
        QuestionType.MULTIPLE_CHOICE -> {
            val a = student.split(",", "，").map { normChoice(it) }.filter { it.isNotBlank() }.sorted()
            val b = standard.split(",", "，").map { normChoice(it) }.filter { it.isNotBlank() }.sorted()
            a.isNotEmpty() && a == b
        }
        QuestionType.TRUE_FALSE -> normBool(student) != null && normBool(student) == normBool(standard)
        else -> normText(student) == normText(standard) // 填空: 忽略大小写和空格
    }

    // 学生可能写 "B" 也可能写 "B. 可空类型", 统一扒出字母序号
    private fun normChoice(s: String): String =
        Regex("[A-Za-z]+").find(s.trim().uppercase())?.value ?: s.trim().uppercase()

    private fun normBool(s: String): String? = when (s.trim().lowercase()) {
        "true", "正确", "对", "t", "yes", "y", "1" -> "true"
        "false", "错误", "错", "f", "no", "n", "0" -> "false"
        else -> null
    }

    private fun normText(s: String): String = s.replace(Regex("\\s+"), "").lowercase()

    fun listSubmissions(assignmentId: String): Result<List<Submission>> {
        if (repository.findAssignment(assignmentId) == null) {
            return Result.failure(IllegalArgumentException("作业不存在"))
        }
        return Result.success(repository.listSubmissions(assignmentId))
    }

    fun mySubmission(assignmentId: String, studentId: String): Result<Submission> {
        val s = repository.findSubmissionOf(assignmentId, studentId)
            ?: return Result.failure(IllegalArgumentException("还没提交过这道作业"))
        return Result.success(s)
    }

    fun getSubmission(id: String): Result<Submission> {
        val s = repository.findSubmission(id) ?: return Result.failure(IllegalArgumentException("提交记录不存在"))
        return Result.success(s)
    }

    // ---------- 批阅 ----------

    fun review(submissionId: String, req: ReviewRequest): Result<Submission> {
        val s = repository.findSubmission(submissionId)
            ?: return Result.failure(IllegalArgumentException("提交记录不存在"))
        val a = repository.findAssignment(s.assignmentId)
            ?: return Result.failure(IllegalArgumentException("作业不存在"))
        // 老师按题给分了就把逐题结果补上, 顺便能自动算出总分
        val merged = if (req.questionScores.isNotEmpty() && s.questionResults.isNotEmpty()) {
            val byId = req.questionScores.associate { it.questionId to it.score.toDouble() }
            s.questionResults.map { r ->
                val v = byId[r.questionId] ?: return@map r
                if (v < 0 || v > r.fullScore) {
                    return Result.failure(IllegalArgumentException("题 ${r.questionId} 的分要在 0 到 ${r.fullScore} 之间, 现在是 $v"))
                }
                r.copy(finalScore = v, judgedBy = JudgedBy.TEACHER)
            }
        } else s.questionResults

        val finalScore = when {
            req.score != null -> req.score
            req.questionScores.isNotEmpty() -> req.questionScores.sumOf { it.score }.toDouble()
            else -> return Result.failure(IllegalArgumentException("要么给 score, 要么给 questionScores"))
        }
        if (finalScore < 0 || finalScore > a.totalScore) {
            return Result.failure(IllegalArgumentException("分数要在 0 到 ${a.totalScore} 之间"))
        }
        val returned = req.action.equals("RETURN", true)
        val updated = s.copy(
            status = if (returned) SubmissionStatus.RETURNED else SubmissionStatus.REVIEWED,
            score = if (returned) s.score else finalScore,
            comment = req.comment,
            reviewer = req.reviewer,
            reviewedAt = now(),
            questionResults = merged
        )
        repository.saveSubmission(updated)
        return Result.success(updated)
    }

    // ---------- 创新点 1: AI 预批阅(可解释给分) ----------

    fun aiPreReview(submissionId: String): Result<AiSuggestion> {
        val s = repository.findSubmission(submissionId)
            ?: return Result.failure(IllegalArgumentException("提交记录不存在"))
        val a = repository.findAssignment(s.assignmentId)
            ?: return Result.failure(IllegalArgumentException("作业不存在"))
        return Result.success(aiSuggestion(a, s.content, othersOf(s.assignmentId, s.studentId)))
    }

    // 打分逻辑: 每一项都留下来龙去脉, 老师能看到"为什么给这个分"
    private fun aiSuggestion(a: Assignment, content: String, others: List<Submission>): AiSuggestion {
        val reasons = mutableListOf<ScoreReason>()
        val total = a.totalScore.toDouble()

        // ① 完成度基础分: 交了就给, 鼓励学生先完成
        val base = total * 0.4
        reasons += ScoreReason("完成度", "已提交作答, 给基础分", base)

        // ② 知识点命中: 作业标了哪些知识点, 答案里提到了几个
        val points = a.knowledgePoints
        val hit = mutableListOf<String>()
        val missed = mutableListOf<String>()
        points.forEach { p ->
            // 知识点是短语, 直接看答案里有没有提到(忽略大小写)
            val key = p.trim()
            if (key.isNotEmpty() && content.contains(key, ignoreCase = true)) hit += p
            else missed += p
        }
        val perPoint = if (points.isEmpty()) 0.0 else (total * 0.4) / points.size
        val pointScore = hit.size * perPoint
        reasons += ScoreReason(
            "知识点覆盖",
            if (points.isEmpty()) "该作业没标知识点, 跳过" else "命中 ${hit.size}/${points.size} 个: ${hit.joinToString("、").ifEmpty { "无" }}",
            pointScore
        )

        // ③ 作答充分度: 字数太少八成是敷衍
        val len = content.length
        val lenScore = when {
            len >= 150 -> 15.0
            len >= 80 -> 10.0
            len >= 30 -> 5.0
            else -> -10.0
        }
        reasons += ScoreReason("作答充分度", "共 $len 字", lenScore)

        // ④ 结构/代码痕迹: 有换行或代码关键字, 说明写得比较认真
        val hasCode = content.contains("fun ") || content.contains("return") ||
                content.contains("=") || content.contains(";") || content.contains("\n")
        val structScore = if (hasCode) 5.0 else 0.0
        reasons += ScoreReason("表达结构", if (hasCode) "含代码或多段表述" else "单段纯文字", structScore)

        // 算总分, 夹在 0~满分 之间
        val raw = reasons.sumOf { it.score }
        val suggested = raw.coerceIn(0.0, total)

        // ⑤ 相似度风险: 跟别人写得太像要提醒老师
        val maxSim = others.maxOfOrNull { similarity(content, it.content) } ?: 0.0
        if (maxSim >= 0.6) {
            reasons += ScoreReason("原创性", "与已有提交相似度 ${(maxSim * 100).toInt()}%, 建议人工核查", 0.0)
        }

        val riskLevel = when {
            maxSim >= 0.6 -> "HIGH"
            maxSim >= 0.35 || len < 30 -> "MID"
            else -> "LOW"
        }
        val confidence = (0.5 + 0.3 * (hit.size.toDouble() / (points.size.coerceAtLeast(1))) +
                0.2 * if (len >= 80) 1.0 else 0.4).coerceIn(0.0, 0.98)

        return AiSuggestion(
            suggestedScore = Math.round(suggested * 10) / 10.0,
            confidence = Math.round(confidence * 100) / 100.0,
            hitKnowledgePoints = hit,
            missedKnowledgePoints = missed,
            reasons = reasons,
            riskLevel = riskLevel,
            summary = "建议 ${Math.round(suggested * 10) / 10.0} 分(满分 ${a.totalScore}), ${
                when (riskLevel) {
                    "HIGH" -> "但相似度偏高, 建议重点核查"
                    "MID" -> "可参考, 留意作答是否完整"
                    else -> "可直接参考"
                }
            }"
        )
    }

    private fun othersOf(assignmentId: String, selfStudentId: String): List<Submission> =
        repository.listSubmissions(assignmentId).filter { it.studentId != selfStudentId }

    // ---------- 创新点 2: 相似片段检测(中文 bigram + Jaccard) ----------

    fun similarityReport(assignmentId: String): Result<List<SimilarityPair>> {
        if (repository.findAssignment(assignmentId) == null) {
            return Result.failure(IllegalArgumentException("作业不存在"))
        }
        val list = repository.listSubmissions(assignmentId)
        val pairs = mutableListOf<SimilarityPair>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                val sim = similarity(a.content, b.content)
                if (sim >= 0.3) { // 低于 30% 就不打扰老师了
                    pairs += SimilarityPair(
                        submissionA = a.id, studentA = a.studentName.ifBlank { a.studentId },
                        submissionB = b.id, studentB = b.studentName.ifBlank { b.studentId },
                        similarity = Math.round(sim * 1000) / 1000.0,
                        sharedFragments = commonFragments(a.content, b.content)
                    )
                }
            }
        }
        return Result.success(pairs.sortedByDescending { it.similarity })
    }

    // 把中文切成相邻两字的集合再算交集占比, 对中文抄袭挺管用
    private fun similarity(a: String, b: String): Double {
        val sa = bigrams(a)
        val sb = bigrams(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.intersect(sb).size.toDouble()
        val union = sa.union(sb).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    private fun bigrams(s: String): Set<String> {
        val t = s.filter { !it.isWhitespace() }
        if (t.length < 2) return if (t.isEmpty()) emptySet() else setOf(t)
        return (0..t.length - 2).map { t.substring(it, it + 2) }.toSet()
    }

    // 找出两人答案里共同的最长片段, 给老师看证据
    private fun commonFragments(a: String, b: String, minLen: Int = 8, limit: Int = 3): List<String> {
        val res = mutableListOf<String>()
        var rest = b
        var len = minOf(a.length, 24)
        while (len >= minLen && res.size < limit) {
            var i = 0
            while (i + len <= a.length && res.size < limit) {
                val piece = a.substring(i, i + len)
                if (rest.contains(piece)) {
                    res += piece
                    rest = rest.replace(piece, "")
                    i += len
                } else i++
            }
            len--
        }
        return res
    }

    // ---------- 创新点 3: 智能批阅队列(谁该先改) ----------

    fun reviewQueue(assignmentId: String): Result<List<ReviewQueueItem>> {
        if (repository.findAssignment(assignmentId) == null) {
            return Result.failure(IllegalArgumentException("作业不存在"))
        }
        val all = repository.listSubmissions(assignmentId)
        val reviewedScores = all.mapNotNull { it.score }
        val classAvg = if (reviewedScores.isEmpty()) null else reviewedScores.average()

        val items = all.filter { it.status != SubmissionStatus.REVIEWED }.map { s ->
            var priority = 50.0
            val reasons = mutableListOf<String>()

            val maxSim = all.filter { it.id != s.id }.maxOfOrNull { similarity(s.content, it.content) } ?: 0.0
            if (maxSim >= 0.6) {
                priority += 40
                reasons += "与其他提交相似度 ${(maxSim * 100).toInt()}%, 疑似雷同"
            }
            val ai = s.ai
            if (ai != null && classAvg != null && kotlin.math.abs(ai.suggestedScore - classAvg) > 20) {
                priority += 25
                reasons += "AI 建议分与班级均分相差 ${kotlin.math.abs(ai.suggestedScore - classAvg).toInt()} 分, 建议人工确认"
            }
            if (s.version >= 2) {
                priority += 10
                reasons += "已修改 ${s.version} 次, 学生投入度高"
            }
            if (s.status == SubmissionStatus.RETURNED) {
                priority += 15
                reasons += "被打回过, 等复批"
            }
            if (reasons.isEmpty()) reasons += "常规待批阅"

            ReviewQueueItem(
                submissionId = s.id, studentId = s.studentId,
                studentName = s.studentName.ifBlank { s.studentId },
                version = s.version,
                priority = priority,
                reasons = reasons,
                aiSuggestedScore = ai?.suggestedScore
            )
        }
        return Result.success(items.sortedByDescending { it.priority })
    }

    // ---------- 视角拓展: 跨课程待批阅聚合(教师端一屏看完全部积压) ----------

    // teacherId 不传就是看全部课程, 传了就只看这个老师教的班对应的课
    fun pendingReviewOverview(teacherIdOrName: String?): Result<PendingReviewOverview> {
        var teacherId = ""
        var teacherName = ""
        var scope = "ALL"
        val courseIds = if (teacherIdOrName.isNullOrBlank()) {
            courseRepository.listCourses().map { it.id }
        } else {
            val u = userRepository.findUserById(teacherIdOrName)
                ?: userRepository.findByUsername(teacherIdOrName)
                ?: return Result.failure(IllegalArgumentException("老师账号不存在: $teacherIdOrName"))
            teacherId = u.id
            teacherName = u.nickname ?: u.username
            scope = "TEACHER"
            teacherRepository.listByTeacher(u.id).map { it.courseId }.distinct()
        }

        val all = mutableListOf<PendingReviewItem>()
        val groups = mutableListOf<PendingCourseGroup>()

        courseIds.distinct().forEach { cid ->
            val courseName = courseRepository.findCourse(cid)?.name ?: cid
            val inCourse = mutableListOf<PendingReviewItem>()
            val aGroups = mutableListOf<PendingAssignmentGroup>()

            repository.listAssignments(cid).forEach { a ->
                val subs = repository.listSubmissions(a.id)
                // 没批的(待批阅)和被打回的(等复批)都算老师手上的活
                val pending = subs.filter { it.status != SubmissionStatus.REVIEWED }
                if (pending.isEmpty()) return@forEach
                var urgent = 0
                pending.forEach { s ->
                    val item = buildPendingItem(a, cid, courseName, s, subs)
                    if (item.urgent) urgent++
                    inCourse += item
                }
                aGroups += PendingAssignmentGroup(
                    assignmentId = a.id, title = a.title,
                    deadline = a.deadline, pendingCount = pending.size, urgentCount = urgent
                )
            }
            if (inCourse.isEmpty()) return@forEach

            all += inCourse
            groups += PendingCourseGroup(
                courseId = cid, courseName = courseName,
                pendingCount = inCourse.size,
                assignmentCount = aGroups.size,
                urgentCount = inCourse.count { it.urgent },
                oldestWaitHours = round1(inCourse.maxOf { it.waitHours }),
                assignments = aGroups.sortedByDescending { it.pendingCount }
            )
        }

        val items = all.sortedByDescending { it.priority }
        return Result.success(
            PendingReviewOverview(
                teacherId = teacherId, teacherName = teacherName, scope = scope,
                total = items.size,
                courseCount = groups.size,
                assignmentCount = groups.sumOf { it.assignmentCount },
                studentCount = items.map { it.studentId }.distinct().size,
                urgentCount = items.count { it.urgent },
                highRiskCount = items.count { it.riskLevel == "HIGH" },
                oldestWaitHours = if (items.isEmpty()) 0.0 else round1(items.maxOf { it.waitHours }),
                avgWaitHours = if (items.isEmpty()) 0.0 else round1(items.map { it.waitHours }.average()),
                groups = groups.sortedByDescending { it.pendingCount },
                items = items
            )
        )
    }

    // 给一份待批阅算优先级: 积压时长 + 疑似雷同 + 打回 + 投入度 + 是否过期
    private fun buildPendingItem(
        a: Assignment, courseId: String, courseName: String,
        s: Submission, others: List<Submission>
    ): PendingReviewItem {
        val wait = hoursSince(s.submittedAt)
        var priority = 50.0
        val reasons = mutableListOf<String>()

        val maxSim = others.filter { it.id != s.id }.maxOfOrNull { similarity(s.content, it.content) } ?: 0.0
        if (maxSim >= 0.6) {
            priority += 40
            reasons += "与同作业其他提交相似度 ${(maxSim * 100).toInt()}%, 疑似雷同"
        }
        if (wait >= 48) {
            priority += 20
            reasons += "已积压 ${wait.toInt()} 小时, 学生等久了"
        } else if (wait >= 24) {
            priority += 10
            reasons += "已等待 ${wait.toInt()} 小时"
        }
        if (s.status == SubmissionStatus.RETURNED) {
            priority += 15
            reasons += "被打回过, 等复批"
        }
        if (s.version >= 2) {
            priority += 10
            reasons += "已修改 ${s.version} 次, 学生投入度高"
        }
        if (isOverdue(a.deadline)) {
            priority += 10
            reasons += "已过截止时间 ${a.deadline}"
        }
        if (reasons.isEmpty()) reasons += "常规待批阅"

        val risk = when {
            maxSim >= 0.6 -> "HIGH"
            maxSim >= 0.35 -> "MID"
            else -> s.ai?.riskLevel ?: "LOW"
        }
        return PendingReviewItem(
            submissionId = s.id, assignmentId = a.id, assignmentTitle = a.title,
            courseId = courseId, courseName = courseName,
            studentId = s.studentId, studentName = s.studentName.ifBlank { s.studentId },
            submittedAt = s.submittedAt, waitHours = round1(wait), version = s.version,
            status = s.status, aiSuggestedScore = s.ai?.suggestedScore,
            riskLevel = risk, priority = priority, urgent = priority >= 70.0, reasons = reasons
        )
    }

    private fun hoursSince(time: String): Double {
        val t = runCatching { LocalDateTime.parse(time, fmt) }.getOrNull() ?: return 0.0
        return Duration.between(t, LocalDateTime.now()).toMinutes() / 60.0
    }

    private fun isOverdue(deadline: String): Boolean {
        if (deadline.isBlank()) return false
        val d = runCatching { LocalDateTime.parse(deadline, fmt) }.getOrNull() ?: return false
        return LocalDateTime.now().isAfter(d)
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    // ---------- 创新点 4: 知识点学情画像 ----------

    fun courseAnalytics(courseId: String): Result<CourseAnalytics> {
        if (!courseRepository.checkExists(courseId)) {
            return Result.failure(IllegalArgumentException("课程不存在"))
        }
        val assignments = repository.listAssignments(courseId)
        val submissions = assignments.flatMap { repository.listSubmissions(it.id) }
        val reviewed = submissions.mapNotNull { s -> s.score?.let { s to it } }

        // 每个知识点: 找到标了它的作业, 统计这些作业的平均得分率
        val mastery = mutableListOf<KnowledgeMastery>()
        assignments.flatMap { it.knowledgePoints }.distinct().forEach { point ->
            val related = assignments.filter { point in it.knowledgePoints }
            val scores = related.flatMap { a ->
                repository.listSubmissions(a.id).mapNotNull { s ->
                    s.score?.let { it / a.totalScore.toDouble() }
                }
            }
            if (scores.isNotEmpty()) {
                mastery += KnowledgeMastery(
                    point = point,
                    sampleCount = scores.size,
                    avgScoreRate = Math.round(scores.average() * 1000) / 1000.0
                )
            }
        }

        val weak = mastery.sortedBy { it.avgScoreRate }.take(3).map { it.point }

        // 成绩趋势: 按作业发布时间排, 看班级整体是进步还是退步
        val trend = assignments.mapNotNull { a ->
            val scores = repository.listSubmissions(a.id).mapNotNull { it.score }
            if (scores.isEmpty()) null
            else ScorePoint(a.title, Math.round(scores.average() * 10) / 10.0)
        }

        return Result.success(
            CourseAnalytics(
                courseId = courseId,
                assignmentCount = assignments.size,
                submissionCount = submissions.size,
                knowledgeMastery = mastery.sortedByDescending { it.avgScoreRate },
                weakPoints = weak,
                scoreTrend = trend
            )
        )
    }

    // ---------- 统计 ----------

    fun stats(assignmentId: String): Result<AssignmentStats> {
        val a = repository.findAssignment(assignmentId)
            ?: return Result.failure(IllegalArgumentException("作业不存在"))
        return Result.success(buildStats(a))
    }

    private fun buildStats(a: Assignment): AssignmentStats {
        val list = repository.listSubmissions(a.id)
        val scores = list.mapNotNull { it.score }
        val buckets = listOf("0-59", "60-69", "70-79", "80-89", "90-100").map { label ->
            val (lo, hi) = label.split("-").let { it[0].toDouble() to it[1].toDouble() }
            ScoreBucket(label, scores.count { it >= lo && it <= hi })
        }
        val withAi = list.filter { it.ai != null && it.score != null }
        val aiGap = if (withAi.isEmpty()) null
        else Math.round(withAi.map { kotlin.math.abs(it.ai!!.suggestedScore - it.score!!) }.average() * 10) / 10.0

        var highRisk = 0
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                if (similarity(list[i].content, list[j].content) >= 0.6) highRisk++
            }
        }

        return AssignmentStats(
            assignmentId = a.id,
            title = a.title,
            submittedCount = list.size,
            reviewedCount = list.count { it.status == SubmissionStatus.REVIEWED },
            pendingCount = list.count { it.status != SubmissionStatus.REVIEWED },
            avgScore = if (scores.isEmpty()) null else Math.round(scores.average() * 10) / 10.0,
            maxScore = scores.maxOrNull(),
            minScore = scores.minOrNull(),
            scoreDistribution = buckets,
            avgVersions = if (list.isEmpty()) 0.0 else Math.round(list.map { it.version }.average() * 100) / 100.0,
            aiGapAvg = aiGap,
            highRiskCount = highRisk
        )
    }
}
