package com.kewu.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * 通知系统数据层（2026-10-26）。
 *
 * 学生端 / 教师端共享同一份本地通知列表（存于 kv）。后端就绪前用本地数据 + 演示种子；
 * 后端就绪后 [KewuApi.getNotifications] / [KewuApi.markNotificationRead] 改为请求接口，
 * 本对象可作为「已读状态本地缓存」继续存在。
 *
 * ★★★ 关键隔离：本对象是「首页右上角铃铛」的系统通知。
 *    AI（小悟）发给用户的消息**不在这里**——它们走 [ChatHistory]（助教 tab 内阅读），
 *    未读红点显示在底部导航栏第二个「助教」上。两套红点互不相通，详见后端需求文档。
 */
object Notifications {

    const val KEY_LIST = "kewu_notifications"

    // ── 学生端通知类型 ──
    const val TYPE_NEW_ASSIGNMENT = "new_assignment"          // 新作业
    const val TYPE_RECORDING_DONE = "recording_transcribed"   // 录音转写成功
    const val TYPE_GRADE_PUBLISHED = "grade_published"        // 成绩公布
    const val TYPE_COURSE_REMINDER = "course_reminder"        // 上课提醒
    const val TYPE_SYSTEM = "system"                          // 系统通知

    // ── 教师端通知类型 ──
    const val TYPE_NEW_SUBMISSION = "new_submission"          // 收到新提交
    const val TYPE_STUDENT_QUESTION = "student_question"      // 学生提问（助教对话里）
    const val TYPE_ATTENDANCE_DONE = "attendance_done"        // 签到结束汇总

    data class Info(
        val id: String,
        val type: String,
        val title: String,
        val body: String,
        val ts: Long,
        var read: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<Info> = try {
        val raw = kvGet(KEY_LIST) ?: return emptyList()
        json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Info(
                id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                type = o["type"]?.jsonPrimitive?.content ?: "",
                title = o["title"]?.jsonPrimitive?.content ?: "",
                body = o["body"]?.jsonPrimitive?.content ?: "",
                ts = o["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                read = o["read"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            )
        }.sortedByDescending { it.ts }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(list: List<Info>) {
        val arr = buildJsonArray {
            list.sortedByDescending { it.ts }.forEach { n ->
                add(buildJsonObject {
                    put("id", n.id)
                    put("type", n.type)
                    put("title", n.title)
                    put("body", n.body)
                    put("ts", n.ts)
                    put("read", n.read)
                })
            }
        }
        kvSet(KEY_LIST, arr.toString())
    }

    /** 未读条数（铃铛红点数字）。 */
    fun unreadCount(): Int = load().count { !it.read }

    /** 打开通知中心时调用：全部标记为已读。 */
    fun markAllRead() {
        val list = load()
        if (list.none { !it.read }) return
        save(list.map { it.copy(read = true) })
    }

    fun markRead(id: String) {
        val list = load()
        val target = list.firstOrNull { it.id == id } ?: return
        if (target.read) return
        save(list.map { if (it.id == id) it.copy(read = true) else it })
    }

    /** 后端未就绪：首次运行按角色种入演示通知，便于验证铃铛红点（仅种一次）。 */
    fun seedIfEmpty(role: String) {
        if (kvGet(KEY_LIST) != null) return
        val now = Clock.System.now().toEpochMilliseconds()
        save(if (role == ROLE_TEACHER) teacherSeed(now) else studentSeed(now))
    }

    private fun studentSeed(now: Long) = listOf(
        Info("s1", TYPE_NEW_ASSIGNMENT, "新作业待完成",
            "《数据结构》第 4 章习题已发布，截止 10 月 30 日 23:59。", now - 3_600_000, false),
        Info("s2", TYPE_RECORDING_DONE, "录音转写成功",
            "你上传的课堂录音已转写为文字，可在「任务」查看转写稿。", now - 7_200_000, false),
        Info("s3", TYPE_GRADE_PUBLISHED, "成绩已公布",
            "《高等数学》单元测成绩已出，点击查看详情。", now - 86_400_000, true),
    )

    private fun teacherSeed(now: Long) = listOf(
        Info("t1", TYPE_NEW_SUBMISSION, "收到新提交",
            "学生 张三 提交了《数据结构》第 4 章作业，待批改。", now - 3_600_000, false),
        Info("t2", TYPE_STUDENT_QUESTION, "学生提问",
            "李四 在助教对话中向你提问：递归与迭代的区别是什么？", now - 5_400_000, false),
        Info("t3", TYPE_ATTENDANCE_DONE, "签到已结束",
            "《操作系统》本节课签到率 96%，可查看签到明细。", now - 86_400_000, true),
    )
}
