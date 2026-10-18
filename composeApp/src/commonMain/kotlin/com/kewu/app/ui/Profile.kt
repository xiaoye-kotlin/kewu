package com.kewu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ───────────────────────── 个人资料数据模型（mock） ─────────────────────────

/**
 * 学生个人资料。
 *
 * ★ 学号（[studentId]）是「我的」页的必显字段，也是课悟绑定课表 / 校园功能的核心标识，
 * 所以放在模型第一位并贯穿主卡展示与编辑页。真实接入后由登录接口返回、写入本地。
 */
data class UserProfile(
    val name: String,
    val studentId: String,
    val school: String,
    val className: String,
    val phone: String,
    val email: String,
)

/** 演示默认资料：学号、学校、班级齐全，符合文档「我的页顶部：头像 + 名字 + 学校/班级」。 */
internal val defaultProfile = UserProfile(
    name = "刘宇豪",
    studentId = "20260551073",
    school = "重庆对外经贸学院",
    className = "计算机科学与技术 5 班",
    phone = "178****2370",
    email = "1640432@qq.com",
)

// ───────────────────────── 通用图标字形（Canvas 绘制） ─────────────────────────

/**
 * 「我的」页功能列表用的小图标：用与导航栏一致的 Canvas 描边风格，
 * 与全 app 的线条语言统一（不混用 emoji / 位图）。
 *
 * @param kind 0=课程绑定 1=录音管理 2=通知 3=关于我们 4=退出登录
 */
@Composable
internal fun ProfileGlyph(kind: Int, color: Color, sizeDp: Dp = 22.dp) {
    Canvas(Modifier.size(sizeDp)) {
        val s = size.width
        val stroke = Stroke(width = s * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            0 -> { // 课程绑定：日历（圆角框 + 两个挂环 + 一条中线）
                drawRoundRect(color, topLeft = Offset(s * 0.16f, s * 0.22f),
                    size = Size(s * 0.68f, s * 0.62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f), style = stroke)
                drawLine(color, Offset(s * 0.30f, s * 0.22f), Offset(s * 0.30f, s * 0.12f), strokeWidth = s * 0.10f, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.70f, s * 0.22f), Offset(s * 0.70f, s * 0.12f), strokeWidth = s * 0.10f, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.16f, s * 0.44f), Offset(s * 0.84f, s * 0.44f), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
            }
            1 -> { // 录音管理：麦克风（胶囊头 + 支架 + 底座）
                drawRoundRect(color, topLeft = Offset(s * 0.34f, s * 0.12f),
                    size = Size(s * 0.32f, s * 0.42f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.16f), style = stroke)
                drawLine(color, Offset(s * 0.50f, s * 0.54f), Offset(s * 0.50f, s * 0.72f), strokeWidth = s * 0.10f, cap = StrokeCap.Round)
                drawLine(color, Offset(s * 0.34f, s * 0.74f), Offset(s * 0.66f, s * 0.74f), strokeWidth = s * 0.10f, cap = StrokeCap.Round)
            }
            2 -> { // 通知：铃铛（钟形 + 底部圆点）
                val bell = Path().apply {
                    moveTo(s * 0.32f, s * 0.30f)
                    cubicTo(s * 0.32f, s * 0.16f, s * 0.68f, s * 0.16f, s * 0.68f, s * 0.30f)
                    lineTo(s * 0.74f, s * 0.58f)
                    lineTo(s * 0.26f, s * 0.58f)
                    close()
                }
                drawPath(bell, color, style = stroke)
                drawArc(color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(s * 0.30f, s * 0.58f), size = Size(s * 0.40f, s * 0.18f), style = stroke)
                drawCircle(color, radius = s * 0.05f, center = Offset(s * 0.50f, s * 0.78f))
            }
            3 -> { // 关于我们：信息圈（i）
                drawCircle(color, style = stroke, radius = s * 0.38f, center = Offset(s * 0.50f, s * 0.50f))
                drawCircle(color, radius = s * 0.045f, center = Offset(s * 0.50f, s * 0.38f))
                drawLine(color, Offset(s * 0.50f, s * 0.50f), Offset(s * 0.50f, s * 0.66f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
            }
            4 -> { // 退出登录：门 + 出门箭头
                drawRoundRect(color, topLeft = Offset(s * 0.26f, s * 0.16f),
                    size = Size(s * 0.40f, s * 0.68f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.06f), style = stroke)
                drawLine(color, Offset(s * 0.46f, s * 0.16f), Offset(s * 0.46f, s * 0.84f), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
                val arrow = Path().apply {
                    moveTo(s * 0.58f, s * 0.50f)
                    lineTo(s * 0.74f, s * 0.50f)
                    moveTo(s * 0.66f, s * 0.42f)
                    lineTo(s * 0.74f, s * 0.50f)
                    lineTo(s * 0.66f, s * 0.58f)
                }
                drawPath(arrow, color, style = stroke)
            }
        }
    }
}
