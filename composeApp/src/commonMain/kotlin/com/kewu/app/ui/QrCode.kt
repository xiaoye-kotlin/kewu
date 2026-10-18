package com.kewu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

// ───────────────────────── 自绘二维码（签到动态码渲染） ─────────────────────────
//
// 项目未引入二维码库，且本地拉依赖网络不稳，故内置一个**纯 Kotlin 二维码编码器**
// （算法移植自 Project Nayuki 的 QR-Code-generator，MIT 协议），用 Compose Canvas 渲染。
// 编码 Byte 模式（UTF-8）、纠错级 M、固定 mask 0，输出 size×size 模块矩阵。
//
// 用法：QrCodeView(text = qrContent) —— text 即后端 `/code` 返回的 `qrContent`。

/** 纠错级（仅用 M），对应下面两张表的行。 */
private const val LEVEL_M = 1

/** 每个纠错块里的纠错码字个数 [level][version(1..40)]，index 0 占位。 */
private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
    intArrayOf(0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
    intArrayOf(0, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    intArrayOf(0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
)

/** 纠错块个数 [level][version(1..40)]，index 0 占位。 */
private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
    intArrayOf(0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
    intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
    intArrayOf(0, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
    intArrayOf(0, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
)

/** GF(256) 乘法（Russian peasant，模多项式 x^8+x^4+x^3+x^2+1 = 0x11D）。 */
private fun rsMultiply(x: Int, y: Int): Int {
    var z = 0
    var yy = y
    for (i in 7 downTo 0) {
        z = (z shl 1) xor ((z ushr 7) * 0x11D)
        z = z xor (((yy ushr i) and 1) * x)
    }
    return z
}

/** 生成 Reed-Solomon 生成多项式系数（degree 个，缺最高次项）。 */
private fun rsComputeDivisor(degree: Int): IntArray {
    val result = IntArray(degree)
    result[degree - 1] = 1
    var root = 1
    for (i in 0 until degree) {
        for (j in result.indices) {
            result[j] = rsMultiply(result[j], root)
            if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
        }
        root = rsMultiply(root, 2)
    }
    return result
}

/** 计算 data 对 divisor 的 Reed-Solomon 余数（即纠错码字）。 */
private fun rsComputeRemainder(data: ByteArray, divisor: IntArray): IntArray {
    val result = IntArray(divisor.size)
    for (b in data) {
        val factor = (b.toInt() and 0xFF) xor result[0]
        System.arraycopy(result, 1, result, 0, result.size - 1)
        result[result.size - 1] = 0
        for (i in result.indices) result[i] = result[i] xor rsMultiply(divisor[i], factor)
    }
    return result
}

/** 该版本原始数据模块总数（含函数图形）。 */
private fun getNumRawDataModules(ver: Int): Int {
    var result = (16 * ver + 128) * ver + 64
    if (ver >= 2) {
        val numAlign = ver / 7 + 2
        result -= (25 * numAlign - 10) * numAlign - 55
        if (ver >= 7) result -= 36
    }
    return result
}

/** 该版本（level M）的数据码字个数 = 总码字数 - 纠错码字数。 */
private fun numDataCodewords(ver: Int): Int =
    getNumRawDataModules(ver) / 8 -
        ECC_CODEWORDS_PER_BLOCK[LEVEL_M][ver] * NUM_ERROR_CORRECTION_BLOCKS[LEVEL_M][ver]

/** 二维码编码器：把字节数据编成 size×size 的布尔模块矩阵。 */
private class QrEncoder(data: ByteArray) {
    val version: Int
    val size: Int
    val modules: Array<BooleanArray>
    private val isFunction: Array<BooleanArray>

    init {
        // 1. 选最小能放下的版本（Byte 模式：4 位模式指示 + 8/16 位长度 + 数据）
        var v = 1
        while (v < 40) {
            val ccbits = if (v <= 9) 8 else 16
            if (4 + ccbits + data.size * 8 <= numDataCodewords(v) * 8) break
            v++
        }
        version = v
        size = version * 4 + 17
        modules = Array(size) { BooleanArray(size) }
        isFunction = Array(size) { BooleanArray(size) }

        // 2. 组数据码字（含终止符/补齐）
        val dataCodewords = encodeData(data)
        // 3. 分块 + 算纠错 + 交织，得到最终码字流
        val finalCodewords = interleaveWithEcc(dataCodewords)
        // 4. 画函数图形 → 填数据 → mask → 格式信息（固定 mask 0）
        drawFunctionPatterns()
        drawCodewords(finalCodewords)
        applyMask(0)
        drawFormatBits(0)
    }

    // ── 数据编码 ──

    private fun encodeData(data: ByteArray): ByteArray {
        val capacityBits = numDataCodewords(version) * 8
        val ccbits = if (version <= 9) 8 else 16
        val bits = ArrayList<Boolean>(capacityBits)
        fun append(value: Int, n: Int) {
            for (i in n - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }
        append(0b0100, 4)                       // Byte 模式指示
        append(data.size, ccbits)               // 字符数
        for (b in data) append(b.toInt() and 0xFF, 8)
        append(0, minOf(4, capacityBits - bits.size))   // 终止符 ≤4 位
        while (bits.size % 8 != 0) bits.add(false)      // 补到字节边界
        var pad = 0xEC                                  // 补齐字节 0xEC / 0x11 交替
        while (bits.size < capacityBits) {
            append(pad, 8)
            pad = pad xor (0xEC xor 0x11)
        }
        val out = ByteArray(bits.size / 8)
        for (i in out.indices) {
            var byte = 0
            for (j in 0..7) if (bits[i * 8 + j]) byte = byte or (1 shl (7 - j))
            out[i] = byte.toByte()
        }
        return out
    }

    private fun interleaveWithEcc(data: ByteArray): ByteArray {
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[LEVEL_M][version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[LEVEL_M][version]
        val rawCodewords = getNumRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks
        val dataShortLen = shortBlockLen - blockEccLen

        val rsDiv = rsComputeDivisor(blockEccLen)
        val blocks = arrayOfNulls<ByteArray>(numBlocks)
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = dataShortLen + (if (i < numShortBlocks) 0 else 1)
            var dat = data.copyOfRange(k, k + datLen)
            k += datLen
            val ecc = rsComputeRemainder(dat, rsDiv)
            if (i < numShortBlocks) dat = dat.copyOf(dat.size + 1)   // 短块补一个 0 字节
            val block = ByteArray(dat.size + ecc.size)
            dat.copyInto(block, 0)
            for (e in ecc.indices) block[dat.size + e] = ecc[e].toByte()
            blocks[i] = block
        }
        val result = ByteArray(rawCodewords)
        k = 0
        for (i in 0 until blocks[0]!!.size) {
            for (j in 0 until numBlocks) {
                // 跳过短块的那个补齐字节
                if (i != dataShortLen || j >= numShortBlocks) {
                    result[k] = blocks[j]!![i]
                    k++
                }
            }
        }
        return result
    }

    // ── 绘制 ──

    private fun setFunctionModule(x: Int, y: Int, dark: Boolean) {
        if (x in 0 until size && y in 0 until size) {   // 边界保护（角落定位图形的外圈分隔符会越界）
            modules[y][x] = dark
            isFunction[y][x] = true
        }
    }

    private fun drawFunctionPatterns() {
        // 时序图形（横竖各一条）
        for (i in 0 until size) {
            setFunctionModule(6, i, i % 2 == 0)
            setFunctionModule(i, 6, i % 2 == 0)
        }
        // 三个定位图形
        drawFinderPattern(3, 3)
        drawFinderPattern(size - 4, 3)
        drawFinderPattern(3, size - 4)
        // 校正图形
        val pos = alignmentPatternPositions()
        for (i in pos.indices) {
            for (j in pos.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0)) continue
                drawAlignmentPattern(pos[i], pos[j])
            }
        }
        drawVersion()
        drawFormatBits(0)   // 先占位（把格式信息区标为函数模块）
    }

    private fun alignmentPatternPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val numAlign = version / 7 + 2
        val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var i = result.size - 1
        var p = size - 7
        while (i >= 1) {
            result[i] = p
            p -= step
            i--
        }
        return result
    }

    private fun drawFinderPattern(x: Int, y: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                setFunctionModule(x + dx, y + dy, dist != 2 && dist != 4)
            }
        }
    }

    private fun drawAlignmentPattern(x: Int, y: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                setFunctionModule(x + dx, y + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }
    }

    private fun drawVersion() {
        if (version < 7) return
        var rem = version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val bit = (bits ushr i) and 1 != 0
            val a = size - 11 + i % 3
            val b = i / 3
            setFunctionModule(a, b, bit)
            setFunctionModule(b, a, bit)
        }
    }

    private fun drawFormatBits(msk: Int) {
        // level M 的 formatBits = 0
        val data = (0 shl 3) or msk
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412
        // 第一份
        for (i in 0..5) setFunctionModule(8, i, (bits ushr i) and 1 != 0)
        setFunctionModule(8, 7, (bits ushr 6) and 1 != 0)
        setFunctionModule(8, 8, (bits ushr 7) and 1 != 0)
        setFunctionModule(7, 8, (bits ushr 8) and 1 != 0)
        for (i in 9 until 15) setFunctionModule(14 - i, 8, (bits ushr i) and 1 != 0)
        // 第二份
        for (i in 0 until 8) setFunctionModule(size - 1 - i, 8, (bits ushr i) and 1 != 0)
        for (i in 8 until 15) setFunctionModule(8, size - 15 + i, (bits ushr i) and 1 != 0)
        setFunctionModule(size - 8, 8, true)   // 恒暗模块
    }

    private fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            var r = right
            if (r == 6) r = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = r - j
                    val upward = ((r + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3].toInt() and 0xFF) and (1 shl (7 - (i and 7))) != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(msk: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!isFunction[y][x]) {
                    val invert = when (msk) {
                        0 -> (x + y) % 2 == 0
                        else -> false
                    }
                    modules[y][x] = modules[y][x] xor invert
                }
            }
        }
    }
}

/**
 * 渲染一张可扫的二维码（含四周静默区）。dark/light 为模块颜色。
 * [text] 即后端签到 `/code` 返回的 `qrContent`。
 */
@Composable
internal fun QrCodeView(
    text: String,
    modifier: Modifier = Modifier,
    dark: Color = Color.Black,
    light: Color = Color.White,
) {
    val qr = remember(text) { runCatching { QrEncoder(text.encodeToByteArray()) }.getOrNull() }
    Canvas(modifier) {
        val n = qr?.size ?: 21
        val quiet = 4                                  // 4 模块静默区（扫码必需）
        val cell = this.size.minDimension / (n + 2 * quiet)
        val ox = (this.size.width - cell * n) / 2f
        val oy = (this.size.height - cell * n) / 2f
        drawRect(light, topLeft = Offset(ox - quiet * cell, oy - quiet * cell),
            size = Size(cell * (n + 2 * quiet), cell * (n + 2 * quiet)))
        qr?.let {
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (it.modules[y][x]) {
                        drawRect(dark, topLeft = Offset(ox + x * cell, oy + y * cell),
                            size = Size(cell + 0.6f, cell + 0.6f))
                    }
                }
            }
        }
    }
}
