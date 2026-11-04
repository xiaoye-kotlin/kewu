package com.kewu.modules.voicechat

import com.kewu.recserver.services.RedisService
import com.kewu.recserver.services.ai.MaasAiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * AI 语音对话的业务编排（微信语音那种「发一段、回一段」）。
 *
 * 全流程跑在协程里：ASR 和「读 Redis 历史」互不依赖，用 async 并发省掉一次串行等待；
 * 所有阻塞 IO（HTTP / Redis）都在 Dispatchers.IO，不占 Ktor 的请求线程。
 *
 * 链路：音频 → SIS 一句话识别 → Redis 读历史 → DeepSeek 生成回复 → 写回 Redis(1h) → SIS TTS → mp3。
 * 降级：识别为空播「没听清」；DeepSeek / TTS 出错播「服务正忙」；任何异常都不外抛，绝不 500。
 */
class VoiceChatService(
    private val sis: VoiceChatSisService = VoiceChatSisService(),
    private val redis: RedisService,
    private val maas: MaasAiService,
) {

    private val log = LoggerFactory.getLogger(VoiceChatService::class.java)

    /** audio 为 null 表示 TTS 也挂了，路由层降级成纯文本响应。 */
    data class VoiceReply(
        val audio: ByteArray?,
        val text: String,
        val stage: String,       // ok / asr-empty / maas-failed / tts-failed，放 header 方便排查
        val userText: String = "",
    )

    suspend fun chat(sessionId: String, audio: ByteArray): VoiceReply = coroutineScope {
        // 这两件事没有依赖，同时发出去：识别要 1~2 秒，读 Redis 只有几毫秒
        val asrDeferred = async(Dispatchers.IO) {
            runCatching { sis.recognizeShortAudio(audio) }
                .onFailure { log.warn("ASR 异常：${it.message}") }
                .getOrDefault(VoiceChatSisService.AsrResult())
        }
        val historyDeferred = async(Dispatchers.IO) {
            runCatching { redis.getVoiceChatHistory(sessionId) }
                .onFailure { log.warn("读对话历史异常：${it.message}") }
                .getOrNull()
        }

        val asr = asrDeferred.await()
        val historyRaw = historyDeferred.await()
        val userText = asr.text.trim()

        // 1) 识别为空 / 只有标点（环境嘈杂、没说话）→ 直接播预置语音，不调 AI、不动历史
        if (userText.isBlank() || !userText.any { it.isLetterOrDigit() }) {
            log.info("voice-chat[$sessionId] 没听清：${asr.error.orEmpty()}")
            return@coroutineScope finish(NOT_HEARD, "asr-empty", userText)
        }

        val history = parseHistory(historyRaw)
        val messages = listOf("system" to SYSTEM_PROMPT) + history + ("user" to userText)

        // 2) DeepSeek 拿到回复；出错返回 null，用兜底话术，且不把错误话写进历史污染上下文
        val aiText = runCatching { maas.chatMessages(messages, MAX_TOKENS) }
            .onFailure { log.warn("DeepSeek 异常：${it.message}") }
            .getOrNull()?.trim()

        val (replyText, stage) = if (aiText.isNullOrBlank()) {
            log.warn("voice-chat[$sessionId] DeepSeek 未返回可用回复")
            BUSY to "maas-failed"
        } else {
            // 3) 只把 user / assistant 写回 Redis，system 提示词每次现拼；保留最近 10 轮
            val next = (history + ("user" to userText) + ("assistant" to aiText)).takeLast(MAX_HISTORY)
            runCatching { redis.saveVoiceChatHistory(sessionId, serialize(next)) }
                .onFailure { log.warn("写对话历史异常：${it.message}") }
            aiText to "ok"
        }

        log.info("voice-chat[$sessionId] stage=$stage user=${userText.take(40)} reply=${replyText.take(40)}")
        finish(replyText, stage, userText)
    }

    /** 4) TTS 合成；整条链路挂了再试一次短句，仍失败就交给路由层返回纯文本。 */
    private suspend fun finish(replyText: String, stage: String, userText: String): VoiceReply {
        val mp3 = runCatching { sis.synthesizeMp3(replyText.take(500)) }.getOrNull()
            // 第二次只给 8 秒：多半是原文本触发了 TTS 报错，短句能救回来；
            // 要是 SIS 整体挂了就别再耗，直接降级
            ?: runCatching { withTimeoutOrNull(8_000) { sis.synthesizeMp3(BUSY) } }.getOrNull()
        return if (mp3 == null) {
            log.warn("voice-chat TTS 不可用，降级为纯文本")
            VoiceReply(audio = null, text = BUSY, stage = "tts-failed", userText = userText)
        } else {
            VoiceReply(audio = mp3, text = replyText, stage = stage, userText = userText)
        }
    }

    /** Redis 里存的是 JSON 数组格式的 messages：[{"role":"user","content":"..."}, ...]。 */
    private fun parseHistory(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val role = (o["role"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val content = (o["content"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                if (role != "user" && role != "assistant") return@mapNotNull null
                role to content.take(2000)
            }
        }.onFailure { log.warn("对话历史 JSON 解析失败，按空历史处理：${it.message}") }
            .getOrDefault(emptyList())
            .takeLast(MAX_HISTORY)
    }

    private fun serialize(messages: List<Pair<String, String>>): String = buildJsonArray {
        messages.forEach { (role, content) ->
            add(buildJsonObject {
                put("role", role)
                put("content", content)
            })
        }
    }.toString()

    companion object {
        const val NOT_HEARD = "抱歉，我没有听清，请再说一遍"
        const val BUSY = "抱歉，服务正忙，请稍后再试"

        // 语音场景要把回答压短：太长的文本 TTS 合成慢，念起来也累
        private const val SYSTEM_PROMPT = "你是「课悟」App 的语音助教小悟。学生通过语音和你聊天，" +
            "请用自然口语的中文回答，简洁清楚，一般不超过 80 个字，方便语音朗读；" +
            "不要使用 Markdown 符号、列表或表情，尽量贴合学习和课堂场景。"
        private const val MAX_HISTORY = 20   // 10 轮问答
        private const val MAX_TOKENS = 512
    }
}
