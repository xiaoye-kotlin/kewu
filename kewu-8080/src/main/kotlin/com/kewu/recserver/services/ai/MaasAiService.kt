package com.kewu.recserver.services.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// 统一 AI 实现：DeepSeek / OpenAI 兼容的 chat/completions。
// 配置只允许从环境变量读取（密钥不进代码、不进仓库）：
//   MAAS_API_URL     必填，chat/completions 完整地址
//   MAAS_API_KEY     必填，Bearer Token
//   MAAS_MODEL_NAME  必填，例如 DeepSeek-V4.1-Flash-1017
// 请求体统一为 OpenAI 兼容结构：{"model":"...","messages":[...]}。
class MaasAiService : AiService {

    private val log = LoggerFactory.getLogger(MaasAiService::class.java)

    // 现读（2026-11-03）：总控端改了 AI 配置立即生效
    private val apiUrl: String get() = env("MAAS_API_URL")
    private val apiKey: String get() = env("MAAS_API_KEY")
    private val modelName: String get() = env("MAAS_MODEL_NAME")

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        // 强制直连：不继承系统/环境里的代理设置。否则一旦 JAVA_TOOL_OPTIONS 之类残留了
        // 代理地址（FlClash 就干过这事），AI 调用会全部以 connect timed out 静默失败
        .proxy(java.net.ProxySelector.of(null))
        .build()

    override suspend fun summarize(transcript: String): String {
        if (transcript.isBlank()) return "（转写内容为空，无法总结）"
        return chat(
            system = "你是一个课堂纪要助手。请把下面的课堂转写文本提炼成简洁的中文要点，" +
                "按条理分点列出，不要编造原文未提及的内容。",
            user = transcript,
            maxTokens = 1024
        )
    }

    /**
     * 课堂纪要（2026-10-26，SIS 转写之后调用）：给小悟辅导学生用的结构化纪要。
     * 转写稿可能很长（45 分钟约 1 万字），截取前 20000 字。
     */
    suspend fun classNotes(courseName: String, transcript: String): String {
        if (transcript.isBlank()) return ""
        return chat(
            system = "你是课悟的课堂纪要助手。下面是一节「$courseName」课的语音转写稿（可能有识别错字，请按上下文纠正）。" +
                "请输出结构化课堂纪要，用中文、不用 Markdown 符号，按以下小标题分段：" +
                "【本节主题】一句话；【知识点】3-8 条，每条一句话讲清概念或结论；【例题与方法】老师讲过的例题、解题方法；" +
                "【重点难点】老师强调的易错点；【课后安排】作业、预习、考试提醒（没有就写无）。不要编造转写稿里没有的内容。",
            user = transcript.take(20000),
            maxTokens = 2048
        )
    }

    /**
     * 课堂专注分析（2026-11-02）：学生手机录到的是「老师讲课（远） + 自己和同桌说话（近）」。
     * 对照本节课内容判断这位学生录音里有没有与课程无关的闲聊。返回模型原文（应为一行 JSON），解析交给调用方。
     */
    suspend fun focusAnalysis(courseName: String, lecture: String, studentText: String): String = chat(
        system = "你是课堂专注度分析助手。给你两段文字：①本节「$courseName」课的内容概要；②某位学生手机在座位上录到的语音转写" +
            "（里面既有远处老师讲课，也有学生本人和周围同学的说话）。判断这位学生上课时有没有讲与课程无关的话、和同学闲聊、开小差。" +
            "只输出一行 JSON，不要其他文字：{\"verdict\":\"认真\"或\"疑似开小差\"或\"疑似聊天\",\"evidence\":\"不超过30字的依据，可引用原话\",\"confidence\":0到100的整数}。" +
            "转写里绝大部分是课程内容时判「认真」；不要因为识别错字误判。",
        user = "【课程内容概要】\n${lecture.take(3000)}\n\n【学生录音转写】\n${studentText.take(9000)}",
        maxTokens = 512
    )

    override suspend fun answer(question: String, context: String, maxTokens: Int): String {
        if (question.isBlank()) return "（问题为空）"
        return chat(
            system = "你是课堂智能助手，只能基于下面提供的课堂内容来回答学生的问题；" +
                "如果内容里没有相关信息，就如实说明无法回答，不要凭空编造。",
            user = "【课堂内容】\n${context.ifBlank { "（无）" }}\n\n【学生问题】\n$question",
            maxTokens = maxTokens
        )
    }

    /**
     * B站视频转课堂笔记（2026-11-01）：把视频逐字稿整理成结构化 Markdown 学习笔记。
     * 转写稿可能很长（45 分钟约 1 万字），截取前 12000 字。
     */
    suspend fun bilibiliNotes(transcript: String): String {
        if (transcript.isBlank()) return "（逐字稿为空，无法生成笔记）"
        return chat(
            system = "你是课悟 App 的学习助手。下面是一个 B 站教学视频的语音转写稿，请为学生写一份视频总结，" +
                "严格按以下三段输出，每段以【】小标题开头：【一句话总结】一句话说清视频讲了什么；" +
                "【核心知识点】3～6 条，每条以「· 」开头，写清概念 / 公式 / 方法；【视频大纲】按讲解顺序列出 3～6 个环节，每条以「· 」开头。" +
                "用中文，不要使用 Markdown 符号（# * 等），转写稿可能有错别字，按上下文理解。",
            user = transcript.take(12000),
            maxTokens = 2048
        )
    }

    /**
     * 多路录音融合总结（2026-11-03）：同一节课多设备录音转写文本拼接后，
     * 交给 DeepSeek 做语义对齐 + 去重 + 合并，生成一份结构化终极课堂笔记。
     * 拼接文本可能很长（多份长录音叠加），截取前 24000 字再送模型，避免超出上下文。
     */
    suspend fun fusionNotes(multiTranscript: String): String {
        if (multiTranscript.isBlank()) return "（多路转写内容为空，无法融合总结）"
        return chat(
            system = "你是一个专业的课堂助教。以下是同一节课来自不同设备的多份录音转写文本。" +
                "请先将它们进行语义对齐和去重（将重复的话语合并），然后总结成一份结构化的课堂笔记，" +
                "包含：课程大纲、核心知识点、课堂互动问答。",
            user = "【多路录音转写文本（已按设备分路）】\n${multiTranscript.take(24000)}",
            maxTokens = 2048
        )
    }

    /**
     * 多模态预留（2026-11-02）：发送图片 Base64 + 文本提示词。
     * DeepSeek/OpenAI 兼容格式里，user.content 是数组：[text, image_url]。
     * 后续“拍照搜题”可直接调用这里；错误不会抛出，统一返回友好中文提示。
     */
    suspend fun analyzeImageBase64(
        base64: String,
        prompt: String,
        imageFormat: String = "jpeg",
        maxTokens: Int = 2048
    ): String {
        if (base64.isBlank()) return "（图片内容为空）"
        if (prompt.isBlank()) return "（问题为空）"
        missingConfig()?.let { return it }

        val format = imageFormat.trim().removePrefix("image/").lowercase()
        if (format !in setOf("jpeg", "jpg", "png", "webp", "gif")) {
            return "（不支持的图片格式：$imageFormat）"
        }

        val body = buildJsonObject {
            put("model", modelName)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/$format;base64,${base64.trim()}")
                            })
                        })
                    })
                })
            })
        }.toString()
        return postChat(body, timeoutSeconds = 120)
    }

    /**
     * 流式对话（2026-10-26，App 打字机效果）：以 `stream=true` SSE 拉取，只把最终回答的
     * `delta.content` 逐段回调给 [onDelta]（思维链 reasoning_content 不外发）。成功结束返回 null，失败返回原因。
     */
    suspend fun stream(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        thinking: Boolean,
        /** 第 i 条消息附带的图片（data:image/...;base64,... 形式），有图的消息 content 改为 [text, image_url...] 数组。 */
        images: Map<Int, List<String>> = emptyMap(),
        onDelta: suspend (String) -> Unit,
    ): String? {
        missingConfig()?.let { return it }
        val body = buildJsonObject {
            put("model", modelName)
            put("max_tokens", maxTokens)
            put("stream", true)
            // 关闭思维链：部分模型默认先想几秒才出第一个字；DeepSeek 不支持的参数会自动忽略
            if (!thinking) put("thinking", buildJsonObject {
                put("type", "disabled")
            })
            put("messages", buildJsonArray {
                messages.forEachIndexed { i, (role, content) ->
                    val pics = images[i].orEmpty()
                    add(buildJsonObject {
                        put("role", role)
                        if (pics.isEmpty()) {
                            put("content", content)
                        } else {
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", content.ifBlank { "请看这张图片" })
                                })
                                pics.forEach { url ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject { put("url", url) })
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }.toString()
        return withContext(Dispatchers.IO) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer $apiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val resp = client.send(request, HttpResponse.BodyHandlers.ofLines())
                if (resp.statusCode() !in 200..299) {
                    log.warn("MaasAi: 流式调用失败 HTTP ${resp.statusCode()}")
                    return@withContext "AI 调用失败（HTTP ${resp.statusCode()}）"
                }
                val it = resp.body().iterator()
                while (it.hasNext()) {
                    val line = it.next().trim()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = try {
                        Json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta")?.jsonObject?.get("content")
                            ?.let { c -> if (c is JsonNull) null else c.jsonPrimitive.content }
                    } catch (_: Throwable) { null }
                    if (!delta.isNullOrEmpty()) onDelta(delta)
                }
                null
            } catch (t: Throwable) {
                log.warn("MaasAi: 流式调用异常：${t.message ?: t.javaClass.simpleName}")
                t.message ?: t.javaClass.simpleName
            }
        }
    }

    /**
     * 语音对话用（2026-11-04）：直接传完整的 messages（role/content 对，含多轮历史 + system），
     * 非流式拿一次回复。复用 postChat 的重试与兜底；任何失败返回 null 交给上层降级，绝不抛异常。
     */
    suspend fun chatMessages(messages: List<Pair<String, String>>, maxTokens: Int = 512): String? {
        if (messages.isEmpty() || missingConfig() != null) return null
        val body = buildJsonObject {
            put("model", modelName)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                messages.forEach { (role, content) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("content", content)
                    })
                }
            })
        }.toString()
        val r = postChat(body, timeoutSeconds = 45)
        // postChat 出错时返回的是「（...）」形式的中文哨兵，这里统一转成 null
        return if (r.startsWith("（") &&
            (r.contains("失败") || r.contains("异常") || r.contains("为空") || r.contains("未配置"))
        ) null else r
    }

    private suspend fun chat(system: String, user: String, maxTokens: Int): String {
        missingConfig()?.let { return it }
        val body = buildJsonObject {
            put("model", modelName)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            })
        }.toString()
        return postChat(body, timeoutSeconds = if (maxTokens > 1024) 150 else 60)
    }

    // 非流式 chat/completions。所有网络/解析异常都在这里兜住，只返回友好提示，绝不向外抛。
    private suspend fun postChat(body: String, timeoutSeconds: Long): String {
        return withContext(Dispatchers.IO) {
            var lastErr: String? = null
            // 最多试两次：模型网关偶有 connect timed out 的瞬时抖动，重试一次比直接报错体面得多
            repeat(2) { attempt ->
                try {
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $apiKey")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !in 200..299) {
                        log.warn("MaasAi: HTTP ${response.statusCode()} ${response.body().take(300)}")
                        return@withContext "（AI 调用失败：HTTP ${response.statusCode()}）"
                    }
                    return@withContext extractContent(response.body())
                } catch (t: Throwable) {
                    lastErr = t.message ?: t.javaClass.simpleName
                    log.warn("MaasAi: 调用异常（第 ${attempt + 1} 次）：$lastErr")
                    if (attempt == 0) delay(1000)
                }
            }
            "（AI 调用异常：$lastErr）"
        }
    }

    // 从 OpenAI 兼容响应里取 choices[0].message.content（reasoning_content 是思考链，不外发）。
    // 解析前先去 ```json ... ``` 围栏，避免模型多包一层 Markdown 时 Json.parseToJsonElement 崩溃。
    private fun extractContent(body: String): String {
        return try {
            val cleanedBody = stripMarkdownFences(body)
            val root = Json.parseToJsonElement(cleanedBody).jsonObject
            val choices = root["choices"]?.jsonArray
            val message = choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.content
                ?.let { stripMarkdownFences(it) }
            if (!content.isNullOrBlank()) content else "（AI 返回内容为空）"
        } catch (t: Throwable) {
            log.warn("MaasAi: 返回解析失败：${t.message ?: t.javaClass.simpleName}")
            "（AI 返回解析失败，请稍后再试）"
        }
    }

    // 去掉模型偶尔包上的 ```json ... ``` / ``` ... ``` 围栏。只处理 JSON 需要的那段，不改正文内容。
    private fun stripMarkdownFences(text: String): String {
        var t = text.trim()
        val fence = "```"
        val start = t.indexOf(fence)
        if (start < 0) return t
        t = t.substring(start + fence.length).trimStart()
        val newline = t.indexOf('\n')
        if (newline > 0) {
            val first = t.substring(0, newline).trim()
            if (first.matches(Regex("[A-Za-z0-9_-]+"))) t = t.substring(newline + 1)
        }
        val end = t.lastIndexOf(fence)
        if (end >= 0) t = t.substring(0, end)
        return t.trim()
    }

    private fun env(name: String): String = com.kewu.infra.SecretStore.get(name)?.trim().orEmpty()

    private fun missingConfig(): String? {
        val missing = buildList {
            if (apiUrl.isBlank()) add("MAAS_API_URL")
            if (apiKey.isBlank()) add("MAAS_API_KEY")
            if (modelName.isBlank()) add("MAAS_MODEL_NAME")
        }
        return if (missing.isEmpty()) null else "（AI 未配置：缺少环境变量 ${missing.joinToString(" / ")}）"
    }
}
