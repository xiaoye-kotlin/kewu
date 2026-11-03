package com.kewu.modules.audio

import com.kewu.db.AudioFusionNotes
import com.kewu.recserver.services.RedisService
import com.kewu.recserver.services.SisTranscriptionService
import com.kewu.recserver.services.ai.MaasAiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

// 多路录音并发转写 + 文本大融合（2026-11-03 升级「录音转笔记」）：
// 同一节课多台设备（教师手机 / 学生手机）同时录音，音频已存在 OBS。本服务把每份都转成文本、
// 带上角色标签拼接成一份长文本，再交给 DeepSeek 做语义对齐、去重、合并，生成一份终极课堂笔记。
//
// 避坑（对应需求）：
//   - 多路转写用 coroutineScope + async 并发；每一路单独 try-catch，某一路失败只记日志并跳过，绝不拖垮其他路；
//   - 拼接前按角色打标签（【教师端录音】/【学生端录音】），多路之间用空行分隔，方便模型分辨来源；
//   - 拼接文本可能极长，发给 DeepSeek 前整体截断到上限（默认 24000 字），避免超出模型上下文；
//   - Prompt 明确强调「语义对齐 + 去重 + 合并」（见 MaasAiService.fusionNotes）；
//   - 解析模型结果时清理 ```json / ``` 围栏（MaasAiService 已统一处理）；
//   - 全程更新 Redis 进度（转写中 → 融合中 → 总结中 → 完成 / 失败），最终 Markdown 落 MySQL。
class TranscribeFusionService(
    private val transcription: SisTranscriptionService,
    private val redis: RedisService,
    private val maas: MaasAiService
) {
    private val log = LoggerFactory.getLogger(TranscribeFusionService::class.java)

    // 正在跑的任务：同一 taskId 不重复起（每次受理生成新 UUID，这里主要做兜底）
    private val running: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 单条录音来源。 */
    data class AudioSource(val obsUrl: String, val role: String)

    /**
     * 受理一次融合：立即生成 taskId 并写 PROCESSING，返回给前端轮询；流水线由调用方丢到后台协程跑。
     */
    suspend fun start(sources: List<AudioSource>): String {
        val taskId = UUID.randomUUID().toString()
        running.add(taskId)
        setStatus(taskId, statusJson("PROCESSING", stage = "转写中", progress = 0))
        return taskId
    }

    /** 后台协程跑整条流水线（不阻塞接口）。 */
    suspend fun run(taskId: String, sources: List<AudioSource>) {
        running.add(taskId)
        try {
            setStatus(taskId, statusJson("PROCESSING", stage = "转写中", progress = 5))

            // 第一步：多路并发转写（核心容错点）
            val results = transcribeAll(sources)

            if (results.isEmpty()) {
                throw RuntimeException("所有录音转写均失败，无法生成笔记")
            }

            // 第二步：按角色拼接（带标签）
            val combined = buildCombined(results)
            log.info("多路转写完成 taskId=$taskId 成功=${results.size}/${sources.size} 拼接长度=${combined.length}")

            // 第三步：AI 融合总结（强调去重合并）
            setStatus(taskId, statusJson("PROCESSING", stage = "融合中", progress = 70))
            setStatus(taskId, statusJson("PROCESSING", stage = "总结中", progress = 80))
            val note = maas.fusionNotes(combined)

            // 第四步：落库 + 状态 FINISHED
            saveNote(taskId, results.size, note, combined)
            setStatus(taskId, statusJson("FINISHED", progress = 100))
            log.info("多路融合笔记生成完成 taskId=$taskId 笔记长度=${note.length}")
        } catch (e: Throwable) {
            // 任何失败（SIS 全失败 / 大模型异常）都只记 FAILED，不抛 500
            log.error("多路融合笔记生成失败 taskId=$taskId：${e.message}")
            setStatus(taskId, statusJson("FAILED", reason = e.message ?: e.javaClass.simpleName))
        } finally {
            running.remove(taskId)
        }
    }

    // 并发转写全部音频：coroutineScope + async，每路独立 try-catch。
    // 失败时返回 null（记日志跳过），不影响其他路——这是防止单路 SIS 超时 / 报错拖垮整服务的核心。
    private suspend fun transcribeAll(sources: List<AudioSource>): List<Pair<String, String>> =
        coroutineScope {
            sources.mapIndexed { idx, src ->
                async {
                    try {
                        val text = transcription.transcribe(src.obsUrl, "auto")
                        if (text.isBlank()) {
                            log.warn("转写结果为空（已跳过该路）taskId 路#$idx role=${src.role} url=${src.obsUrl.take(80)}")
                            null
                        } else {
                            roleLabel(src.role) to text
                        }
                    } catch (t: Throwable) {
                        // SIS 提交/轮询抛出的 TranscriptionException 或其它异常都只吞掉这一路
                        log.warn("转写失败（已跳过该路）taskId 路#$idx role=${src.role}：${t.message?.take(200) ?: t.javaClass.simpleName}")
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }

    // 角色标签：teacher/教师 -> 教师端录音；student/学生 -> 学生端录音；其它 -> 原角色+端录音
    private fun roleLabel(role: String): String {
        val r = role.lowercase()
        return when {
            r in setOf("teacher", "教师", "老师") -> "教师端录音"
            r in setOf("student", "学生") -> "学生端录音"
            role.isBlank() -> "未知端录音"
            else -> "${role}端录音"
        }
    }

    // 拼接：每条「【角色标签】文本」一段，多路之间用空行分隔，便于模型分辨来源。
    // 拼接后整体截断到上限，避免超出模型上下文（DeepSeek 虽长，但多份长录音叠加很容易爆）。
    private fun buildCombined(results: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        results.forEachIndexed { i, (label, text) ->
            if (i > 0) sb.append("\n\n")
            sb.append("【$label】\n").append(text.trim())
        }
        val combined = sb.toString()
        return if (combined.length > MAX_COMBINED_CHARS) {
            log.warn("拼接文本过长（${combined.length}），截断到 $MAX_COMBINED_CHARS 字再送模型")
            combined.take(MAX_COMBINED_CHARS) + "\n（以下因篇幅过长被截断）"
        } else combined
    }

    private suspend fun saveNote(taskId: String, sourceCount: Int, content: String, transcript: String) =
        withContext(Dispatchers.IO) {
            transaction {
                AudioFusionNotes.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[AudioFusionNotes.taskId] = taskId
                    it[AudioFusionNotes.sourceCount] = sourceCount
                    it[AudioFusionNotes.content] = content
                    it[AudioFusionNotes.transcript] = transcript.take(60000)
                    it[createdAt] = nowStr()
                }
            }
        }

    /** 从 MySQL 读取已生成的笔记（GET 轮询接口用）。 */
    suspend fun getNoteByTaskId(taskId: String): FusionNoteDTO? = withContext(Dispatchers.IO) {
        transaction {
            AudioFusionNotes.selectAll().where { AudioFusionNotes.taskId eq taskId }
                .orderBy(AudioFusionNotes.createdAt to SortOrder.DESC)
                .limit(1)
                .map {
                    FusionNoteDTO(
                        taskId = it[AudioFusionNotes.taskId],
                        sourceCount = it[AudioFusionNotes.sourceCount],
                        content = it[AudioFusionNotes.content],
                        transcript = it[AudioFusionNotes.transcript].orEmpty(),
                        createdAt = it[AudioFusionNotes.createdAt]
                    )
                }.firstOrNull()
        }
    }

    /** 读 Redis 里的任务状态（进度轮询用）。Redis 不可用时返回 null（上层回落成 PENDING）。 */
    suspend fun getStatus(taskId: String): String? = redis.getFusionStatus(taskId)

    private suspend fun setStatus(taskId: String, json: String) {
        // Redis 写入失败（连不上）就静默降级，绝不阻塞流水线
        redis.setFusionStatus(taskId, json)
    }

    /** 每一步失败都带上阶段名，App 能如实告诉用户卡在哪。 */
    private fun statusJson(status: String, reason: String? = null, stage: String? = null, progress: Int? = null): String =
        buildJsonObject {
            put("status", status)
            if (stage != null) put("stage", stage)
            if (progress != null) put("progress", progress)
            if (reason != null) put("reason", reason.take(500))   // 截断，别让 reason 撑爆 Redis
        }.toString()

    private fun nowStr(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    companion object {
        // 拼接文本送模型前的上限：多份长录音叠加可能极长，截断避免超出 DeepSeek 上下文
        private const val MAX_COMBINED_CHARS = 24000
    }
}

/** GET 接口返回的融合笔记结构。 */
data class FusionNoteDTO(
    val taskId: String,
    val sourceCount: Int,
    val content: String,
    val transcript: String,
    val createdAt: String
)
