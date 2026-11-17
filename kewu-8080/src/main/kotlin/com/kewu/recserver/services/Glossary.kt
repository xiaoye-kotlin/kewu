package com.kewu.recserver.services

/**
 * 专有名词纠错字典（2026-11-17 §87）。
 *
 * 语音识别（SIS）对品牌名 / 学科术语经常听错：把「小悟」听成「小吴 / 小五 / 小wu」，
 * 把「课悟」听成「课物 / 可悟」，英文 Kewu 大小写乱飞，数学里的「泰勒公式」听成「泰乐公式」。
 * 这些词在课堂录音转写与 AI 复盘中出现频率极高，一旦错就会污染整份纪要，所以统一在**拿到转写文本后立刻纠正一次**。
 *
 * 设计原则：
 * 1. 只做「错→对」的确定性替换，不做模糊匹配，避免误伤学生真实姓名；
 * 2. 长词优先（先替「小悟老师」再替「小吴」），防止被短词截断；
 * 3. 英文按不区分大小写匹配，统一写回品牌写法 `Kewu`；
 * 4. 幂等：正确写法本身不在错词表里，重复调用结果不变。
 */
object Glossary {

    /** 错词 → 正确写法。顺序即替换顺序（长词在前）。 */
    private val replacements: List<Pair<String, String>> = listOf(
        // —— 品牌：小悟（AI 助教） ——
        "小悟老师" to "小悟老师",
        "小吴老师" to "小悟老师",
        "小五老师" to "小悟老师",
        "晓悟老师" to "小悟老师",
        "小wu" to "小悟",
        "小吴" to "小悟",
        "小五" to "小悟",
        "小伍" to "小悟",
        "小舞" to "小悟",
        "小务" to "小悟",
        "小雾" to "小悟",
        "晓悟" to "小悟",
        "肖悟" to "小悟",
        "小吾" to "小悟",
        // —— 品牌：课悟（平台名） ——
        "课物" to "课悟",
        "可悟" to "课悟",
        "科悟" to "课悟",
        "课务" to "课悟",
        "客悟" to "课悟",
        "克悟" to "课悟",
        "课吾" to "课悟",
        // —— 学科术语 ——
        "泰乐公式" to "泰勒公式",
        "台勒公式" to "泰勒公式",
        "泰勒共识" to "泰勒公式",
        "泰勒工式" to "泰勒公式",
        "洛必达法则" to "洛必达法则",
        "洛比达法则" to "洛必达法则",
        "罗必达法则" to "洛必达法则",
        "拉格朗日中值定理" to "拉格朗日中值定理",
        "拉格朗日终止定理" to "拉格朗日中值定理",
        "柯西不等式" to "柯西不等式",
        "哥西不等式" to "柯西不等式",
        "牛顿莱布尼茨公式" to "牛顿-莱布尼茨公式",
        "牛顿莱布尼兹公式" to "牛顿-莱布尼茨公式",
        "微积分基本定理" to "微积分基本定理",
        "导数" to "导数",
        "倒数" to "倒数", // 占位：不做替换，仅说明这两个词不混（避免后续误加）
        "因式分解" to "因式分解",
        "阴式分解" to "因式分解",
        "二次函数" to "二次函数",
        "二次涵数" to "二次函数",
        "平行四边型" to "平行四边形",
        "三角涵数" to "三角函数",
        "三角函数" to "三角函数",
    )

    /** 英文品牌：不区分大小写，统一成 Kewu（不动已正确的写法）。 */
    private val englishFixes: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bke\\s*wu\\b") to "Kewu",
        Regex("(?i)\\bkewu\\b") to "Kewu",
        Regex("(?i)\\bkevu\\b") to "Kewu",
    )

    /**
     * 纠正一段文本里的专有名词。空串原样返回；不做 trim，保留原有换行与空白结构。
     */
    fun fix(text: String): String {
        if (text.isBlank()) return text
        var out = text
        for ((wrong, right) in replacements) {
            if (wrong == right) continue
            if (out.contains(wrong)) out = out.replace(wrong, right)
        }
        for ((regex, right) in englishFixes) {
            out = regex.replace(out, right)
        }
        return out
    }

    /** 供测试 / 其它模块查看词表规模。 */
    val size: Int get() = replacements.count { it.first != it.second }
}
