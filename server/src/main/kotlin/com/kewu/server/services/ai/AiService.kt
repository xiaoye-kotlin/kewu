package com.kewu.server.services.ai

// AI 模块接口：把转写文本喂进去，拿回总结和问答。现在先留口子，还没接
interface AiService {
    suspend fun summarize(transcript: String): String
    suspend fun answer(question: String, context: String): String
}

// 空实现占位，等 AI 能力就绪直接换掉这一行就行
class NoOpAiService : AiService {
    override suspend fun summarize(transcript: String): String = "（AI 总结未接入）"
    override suspend fun answer(question: String, context: String): String = "（AI 问答未接入）"
}
