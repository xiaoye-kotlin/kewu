package com.kewu.db

import com.kewu.modules.assignment.models.Question
import com.kewu.modules.assignment.models.QuestionType
import com.kewu.modules.assignment.models.SubmissionStatus
import com.kewu.modules.attendance.models.AttendanceState
import com.kewu.modules.attendance.models.AttendanceMode
import com.kewu.modules.attendance.models.QuizQuestion
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 首次启动（表为空）时灌的演示数据，内容与原来内存版的 init 块一致
object SeedData {
    fun run() = transaction {
        // ---- 课程 + 课堂纪要 ----
        listOf(
            Triple("c101", "Kotlin 入门", "面向新手的 Kotlin 基础课|张老师"),
            Triple("c102", "华为 ICT 实战", "比赛相关的实战训练|李老师")
        ).forEach { (id, name, rest) ->
            val (desc, teacher) = rest.split("|")
            Courses.insert {
                it[Courses.id] = id
                it[Courses.name] = name
                it[Courses.description] = desc
                it[Courses.teacher] = teacher
            }
        }
        listOf(
            listOf("n1011", "c101", "第一章 环境搭建", "本节课讲解了 JDK、IDEA、Gradle 的安装与配置, 以及第一个 Kotlin 程序怎么跑起来。", "张老师", "2026-10-10 10:00"),
            listOf("n1012", "c101", "第二章 基础语法", "变量、函数、类与对象的基本写法, 顺便聊了下空安全怎么避免 NPE。", "张老师", "2026-10-12 10:00"),
            listOf("n1021", "c102", "SDN 网络基础", "软件定义网络的概念, 以及华为交换机上怎么配一条简单的流表。", "李老师", "2026-10-11 14:00")
        ).forEach { n ->
            ClassNotes.insert {
                it[id] = n[0]; it[courseId] = n[1]; it[title] = n[2]
                it[content] = n[3]; it[author] = n[4]; it[createdAt] = n[5]
            }
        }

        // ---- 作业 ----
        listOf(
            listOf("a101", "c101", "Kotlin 基础语法练习", "用 Kotlin 写一个函数, 接收可空的字符串参数并返回它的长度, 为空时返回 0。", "2026-10-20 23:59", "100", "空安全,函数定义,可空类型", "张老师", "2026-10-15 09:00"),
            listOf("a102", "c101", "第一个 Kotlin 程序", "搭建环境并输出 Hello Kewu, 截图提交环境版本号。", "2026-10-18 23:59", "100", "环境搭建,main 函数", "张老师", "2026-10-13 09:00")
        ).forEach { a ->
            Assignments.insert {
                it[id] = a[0]; it[courseId] = a[1]; it[title] = a[2]
                it[description] = a[3]; it[deadline] = a[4]; it[totalScore] = a[5].toInt()
                it[knowledgePoints] = a[6].split(",").let { l -> kewuJson.encodeToString<List<String>>(l) }
                it[publisher] = a[7]; it[createdAt] = a[8]
            }
        }

        // ---- 提交（s1003 故意和 s1001 写得很像, 用来演示查重）----
        val c1 = "我定义了一个函数 getLength, 参数写成 String? 表示可以传 null。" +
                "函数里用 s?.length ?: 0 这种写法, 当 s 为空的时候就返回 0, 这样就不会出现空指针异常了。" +
                "Kotlin 的空安全设计得挺好, 编译期就能挡住大部分 NPE。"
        val c3 = "我定义了一个函数 getLength, 参数写成 String? 表示可以传 null。" +
                "函数里用 s?.length ?: 0 这种写法, 当 s 为空的时候就返回 0, 这样就不会出现空指针异常了。" +
                "Kotlin 的空安全设计得挺好, 编译期就能挡住大部分 NPE, 我觉得很实用。"
        val c2 = "我用了 Elvis 运算符来处理空值, 先判断参数是不是 null, 是的话直接返回 0。" +
                "另外我还加了一个 require 检查, 防止传入空字符串。整体跑通了, 测试用例也都过了。"
        data class SeedSub(
            val id: String, val a: String, val sid: String, val name: String,
            val content: String, val ver: Int, val at: String,
            val status: String, val score: Double?, val comment: String,
            val reviewer: String?, val reviewedAt: String?, val history: List<String>
        )
        listOf(
            SeedSub("s1001", "a101", "2023001", "张三", c1, 2, "2026-10-16 20:11",
                SubmissionStatus.REVIEWED, 92.0, "思路清晰, 还提到了编译期检查, 很好。", "张老师", "2026-10-17 09:20",
                listOf("1|我先写了个简单版本, 直接 return s.length, 但是空的时候会崩。|2026-10-15 21:30", "2|$c1|2026-10-16 20:11")),
            SeedSub("s1002", "a101", "2023002", "李四", c2, 1, "2026-10-16 22:40",
                SubmissionStatus.REVIEWED, 78.0, "能跑通, 但没说清楚可空类型怎么声明。", "张老师", "2026-10-17 09:25",
                listOf("1|$c2|2026-10-16 22:40")),
            SeedSub("s1003", "a101", "2023003", "王五", c3, 1, "2026-10-17 08:02",
                SubmissionStatus.PENDING, null, "", null, null,
                listOf("1|$c3|2026-10-17 08:02")),
            SeedSub("s1004", "a102", "2023001", "张三", "JDK 21 + IDEA 2025.3 + Gradle 8.11, 程序输出了 Hello Kewu。", 1, "2026-10-14 19:00",
                SubmissionStatus.PENDING, null, "", null, null,
                listOf("1|JDK 21 + IDEA 2025.3 + Gradle 8.11, 程序输出了 Hello Kewu。|2026-10-14 19:00"))
        ).forEach { s ->
            Submissions.insert {
                it[id] = s.id; it[assignmentId] = s.a; it[studentId] = s.sid
                it[studentName] = s.name; it[content] = s.content; it[version] = s.ver
                it[submittedAt] = s.at; it[status] = s.status
                it[score] = s.score; it[comment] = s.comment
                it[reviewer] = s.reviewer ?: ""; it[reviewedAt] = s.reviewedAt ?: ""
                it[history] = buildJsonArray {
                    s.history.forEach { h ->
                        val (v, c, t) = h.split("|")
                        add(buildJsonObject {
                            put("version", v.toInt())
                            put("content", c)
                            put("submittedAt", t)
                        })
                    }
                }.toString()
            }
        }

        // ---- 签到：c101 名单 + 一场开着的签到 + 三条记录 ----
        AttendanceRoster.batchInsert(listOf("2023001", "2023002", "2023003", "2023004")) { sid ->
            this[AttendanceRoster.courseId] = "c101"
            this[AttendanceRoster.studentId] = sid
        }
        AttendanceSessions.insert {
            it[id] = "t101"; it[courseId] = "c101"; it[title] = "第三周课堂签到"
            it[mode] = AttendanceMode.QUIZ; it[state] = AttendanceState.OPEN
            it[publisher] = "张老师"; it[createdAt] = "2026-10-17 08:00"; it[expireAt] = "2026-10-17 08:10"
            it[lateAfterSeconds] = 300; it[latitude] = 31.2304; it[longitude] = 121.4737
            it[radiusMeters] = 300
            it[quiz] = kewuJson.encodeToString<QuizQuestion>(QuizQuestion(
                question = "本节课纪要《第二章 基础语法》里提到了哪个英文技术名词? 写一个就行",
                hint = "变量、函数、类与对象的基本写法里出现过",
                acceptedAnswers = listOf("变量", "函数", "NPE", "Kotlin"),
                sourceNoteTitle = "第二章 基础语法"
            ))
            it[startAtMillis] = System.currentTimeMillis() - 8 * 60 * 1000
        }
        data class SeedRec(
            val id: String, val sid: String, val name: String, val at: String,
            val status: String, val method: String, val answer: String?,
            val secs: Long, val dist: Double?, val dev: String?, val risk: Int, val reasons: List<String>
        )
        listOf(
            SeedRec("r1", "2023001", "张三", "2026-10-17 08:00:35", "NORMAL", AttendanceMode.QUIZ, "NPE", 35, 42.0, "dev-001", 0, emptyList()),
            SeedRec("r2", "2023002", "李四", "2026-10-17 08:06:10", "LATE", AttendanceMode.QUIZ, "变量", 370, 1500.0, "dev-002", 40, listOf("签到位置离课堂约 1500 米, 不在教室附近(半径 300 米)")),
            SeedRec("r3", "2023003", "王五", "2026-10-17 08:01:02", "NORMAL", AttendanceMode.QUIZ, "Kotlin", 62, 55.0, "dev-001", 50, listOf("设备 dev-001 上已有其他同学签到, 疑似代签"))
        ).forEach { r ->
            AttendanceRecords.insert {
                it[id] = r.id; it[sessionId] = "t101"; it[studentId] = r.sid
                it[studentName] = r.name; it[checkedInAt] = r.at; it[status] = r.status
                it[method] = r.method; it[answer] = r.answer
                it[secondsAfterStart] = r.secs; it[distanceMeters] = r.dist; it[deviceId] = r.dev
                it[riskScore] = r.risk
                it[riskReasons] = kewuJson.encodeToString(r.reasons)
            }
        }
    }

    // 题目系统的联调作业 a103。seedIfEmpty 只在整表为空时才跑，而线上库早有数据，
    // 所以这个单独做成幂等的"不存在才插"，升级后自动就有了，重复启动不会重插。
    fun ensureDemoAssignment() = transaction {
        val exists = !Assignments.selectAll().where { Assignments.id eq "a103" }.empty()
        if (exists) return@transaction
        Assignments.insert {
            it[id] = "a103"
            it[courseId] = "c101"
            it[title] = "Kotlin 空安全小测"
            it[description] = "单选 + 多选 + 判断 + 填空 + 简答, 顺便看看客观题能不能自动判。"
            it[deadline] = "2026-11-01 23:59"
            it[totalScore] = 100
            it[knowledgePoints] = kewuJson.encodeToString(listOf("空安全", "可空类型", "Elvis 运算符"))
            it[publisher] = "张老师"
            it[createdAt] = "2026-10-25 09:00"
            it[questions] = kewuJson.encodeToString(
                listOf(
                    Question(
                        id = "q1", type = QuestionType.SINGLE_CHOICE,
                        stem = "下列关于 Kotlin 空安全的说法, 正确的是?",
                        options = listOf("A. 每个变量默认不可空", "B. 可空类型用 ? 声明", "C. 不存在空指针", "D. !! 用于判空"),
                        answer = "B", score = 20,
                        analysis = "可空类型用 T? 声明, 访问时用 ?. 或 !!.。"
                    ),
                    Question(
                        id = "q2", type = QuestionType.MULTIPLE_CHOICE,
                        stem = "哪些写法能安全处理可空变量?(多选)",
                        options = listOf("A. s?.length", "B. s!!.length", "C. s?.length ?: 0", "D. if (s != null) s.length"),
                        answer = "A,C,D", score = 20,
                        analysis = "!! 为空时直接抛 NPE, 不算安全处理。"
                    ),
                    Question(
                        id = "q3", type = QuestionType.TRUE_FALSE,
                        stem = "Kotlin 里 String 和 String? 是同一种类型。",
                        options = listOf("正确", "错误"),
                        answer = "false", score = 20,
                        analysis = "String 不可空, String? 可空, 是两种不同的类型。"
                    ),
                    Question(
                        id = "q4", type = QuestionType.FILL_BLANK,
                        stem = "可空类型声明时要在类型后面加一个符号: ____",
                        answer = "?", score = 20,
                        analysis = "T? 表示这个变量可以为 null。"
                    ),
                    Question(
                        id = "q5", type = QuestionType.SHORT_ANSWER,
                        stem = "说说 ?. 和 !!. 的区别, 以及你什么时候会用 !!。",
                        answer = "?. 为空时整体返回 null 不报错; !!. 为空时直接抛 NPE。只有能确定不为 null 时才用 !!。",
                        score = 20
                    )
                )
            )
        }
    }
}
