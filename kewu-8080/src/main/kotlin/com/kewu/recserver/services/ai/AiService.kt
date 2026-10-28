package com.kewu.recserver.services.ai

// AI 模块接口：把转写文本喂进去，拿回总结和问答。现在先留口子，还没接
interface AiService {
    suspend fun summarize(transcript: String): String
    // maxTokens：openpangu 的思考链也算在额度里，App 让它一次出多道题时要给足（原 8081 写死 768，多题必失败）
    suspend fun answer(question: String, context: String, maxTokens: Int = 768): String
}

// 空实现占位，等 AI 能力就绪直接换掉这一行就行
class NoOpAiService : AiService {
    override suspend fun summarize(transcript: String): String = "（AI 总结未接入）"
    override suspend fun answer(question: String, context: String, maxTokens: Int): String = "（AI 问答未接入）"
}
