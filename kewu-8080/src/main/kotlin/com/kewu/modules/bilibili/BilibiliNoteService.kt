package com.kewu.modules.bilibili

import com.kewu.db.BilibiliNotes
import com.kewu.recserver.services.RedisService
import com.kewu.recserver.services.SisTranscriptionService
import com.kewu.recserver.services.ai.MaasAiService
import com.kewu.recserver.services.storage.AudioStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

// B站视频一键转课堂笔记：组装整条异步流水线，并负责落库 / 状态读写。
//
// 避坑（对应需求）：
//   - 返回给前端的永远是 PROCESSING（接口立即返回，流水线丢到后台协程跑）；
//   - 流水线任一步失败都只在 Redis 记 FAILED+reason，绝不抛 500 崩服务；
//   - 配置（SIS / MaaS / OBS / Redis）全部从环境变量读，不写死。
class BilibiliNoteService(
    private val fetcher: BilibiliFetcher,
    private val transcription: SisTranscriptionService,
    private val storage: AudioStorage,
    private val redis: RedisService,
    private val maas: MaasAiService
) {
    private val log = LoggerFactory.getLogger(BilibiliNoteService::class.java)

    /** 后台协程跑整条流水线：B站抓取 → 下载 → OBS → SIS 转写 → 盘古笔记 → 落库 → 更新状态。 */
    suspend fun generate(bvid: String) {
        setStatus(bvid, statusJson("PROCESSING", stage = "获取视频信息"))
        try {
            // 第一步：B站 API 拿标题 + 音频直链（内部带 UA/Referer，防 412）
            val (title, audioUrl) = step("获取视频信息") { fetcher.fetchAudio(bvid) }
            // 第二步：下载音频字节
            setStatus(bvid, statusJson("PROCESSING", stage = "下载音频"))
            val bytes = step("下载音频") { fetcher.downloadAudio(audioUrl) }
            // 第三步：上传到 OBS（华为云 SIS 只认 OBS 上的音频地址，不能直接吃 B站直链）
            val obsUrl = step("上传音频") { storage.saveObject("bilibili/${bvid}-${System.currentTimeMillis()}.m4a", bytes, "audio/mp4") }
            // 第四步：SIS 录音识别（内部提交 job + 每 10s 轮询，最长约 10 分钟）
            setStatus(bvid, statusJson("PROCESSING", stage = "华为云语音转写"))
            val transcript = step("语音转写") { transcription.transcribe(obsUrl, "auto") }
            if (transcript.isBlank()) throw RuntimeException("语音转写结果为空（视频可能没有人声），请重试")
            // 第五步：大模型整理成视频总结
            setStatus(bvid, statusJson("PROCESSING", stage = "AI 生成总结"))
            val note = step("AI 总结") { maas.bilibiliNotes(transcript) }
            // 第六步：落库 + 更新 Redis 状态为 FINISHED
            saveNote(bvid, title, note, transcript)
            setStatus(bvid, statusJson("FINISHED"))
            log.info("B站笔记生成完成 bvid=$bvid 标题=${title.take(30)} 笔记长度=${note.length}")
        } catch (e: Throwable) {
            // 任何失败（B站解析失败 / 下载失败 / SIS 超时 / 大模型异常）都只记 FAILED，不抛 500
            log.error("B站笔记生成失败 bvid=$bvid：${e.message}")
            setStatus(bvid, statusJson("FAILED", e.message ?: e.javaClass.simpleName))
        }
    }

    /** 从 MySQL 读取已生成的笔记（GET 接口用）。 */
    suspend fun getNoteByBvid(bvid: String): BilibiliNoteDTO? = withContext(Dispatchers.IO) {
        transaction {
            BilibiliNotes.selectAll().where { BilibiliNotes.bvid eq bvid }
                .orderBy(BilibiliNotes.createdAt to SortOrder.DESC)
                .limit(1)
                .map {
                    BilibiliNoteDTO(
                        bvid = it[BilibiliNotes.bvid],
                        title = it[BilibiliNotes.title],
                        content = it[BilibiliNotes.content],
                        createdAt = it[BilibiliNotes.createdAt],
                        transcript = it[BilibiliNotes.transcript].orEmpty()
                    )
                }.firstOrNull()
        }
    }

    /** 读 Redis 里的任务状态（进度轮询用）。Redis 不可用时返回 null（上层回落成 PENDING）。 */
    suspend fun getStatus(bvid: String): String? = redis.getBiliStatus(bvid)

    // 同 bvid 只保留最新一份：先删后插
    private suspend fun saveNote(bvid: String, title: String, content: String, transcript: String) = withContext(Dispatchers.IO) {
        transaction {
            BilibiliNotes.deleteWhere { BilibiliNotes.bvid eq bvid }
            BilibiliNotes.insert {
                it[id] = UUID.randomUUID().toString()
                it[BilibiliNotes.bvid] = bvid
                it[BilibiliNotes.title] = title
                it[BilibiliNotes.content] = content
                it[BilibiliNotes.transcript] = transcript.take(30000)
                it[createdAt] = nowStr()
            }
        }
    }

    private suspend fun setStatus(bvid: String, json: String) {
        // Redis 写入失败（连不上）就静默降级，绝不阻塞流水线
        redis.setBiliStatus(bvid, json)
    }

    /** 每一步失败都带上步骤名，App 能如实告诉学生卡在哪。 */
    private suspend fun <T> step(name: String, block: suspend () -> T): T =
        try { block() } catch (e: Throwable) { throw RuntimeException("${name}失败：${e.message?.take(200) ?: e.javaClass.simpleName}", e) }

    private fun statusJson(status: String, reason: String? = null, stage: String? = null): String =
        buildJsonObject {
            put("status", status)
            if (stage != null) put("stage", stage)
            if (reason != null) put("reason", reason.take(500))   // 截断，别让 reason 撑爆 Redis
        }.toString()

    private fun nowStr(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}

/** GET 接口返回的笔记结构。 */
data class BilibiliNoteDTO(
    val bvid: String,
    val title: String,
    val content: String,
    val createdAt: String,
    val transcript: String = ""
)
