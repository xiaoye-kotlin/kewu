package com.kewu.app.ui

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

/**
 * 轻量自绘图标（项目未引入 coui-icons / material-icons-extended，core 无 Lock/Clear 的可见导出）。
 * 仿 MIUI 输入框末尾的「清除 X」与「密码锁」图标，用 ImageVector.Builder + PathNode 标准 API。
 * 描边颜色由使用处 Icon(tint=...) 决定，此处不写死 strokeColor。
 */
internal object AppIcons {
    /** 圆形 X 清除图标 */
    val Clear: ImageVector
        get() = ImageVector.Builder(
            name = "KewuClear",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(6.5f, 6.5f),
                    PathNode.LineTo(17.5f, 17.5f),
                    PathNode.MoveTo(17.5f, 6.5f),
                    PathNode.LineTo(6.5f, 17.5f),
                ),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                pathFillType = PathFillType.NonZero,
            )
        }.build()

    /** 锁图标（密码框可见/隐藏切换：图标不变，输入明文/密文切换）。 */
    val Lock: ImageVector
        get() = ImageVector.Builder(
            name = "KewuLock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(5f, 10f),
                    PathNode.LineTo(5f, 19f),
                    PathNode.LineTo(19f, 19f),
                    PathNode.LineTo(19f, 10f),
                    PathNode.LineTo(5f, 10f),
                ),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(8.5f, 10f),
                    PathNode.LineTo(8.5f, 7f),
                    PathNode.CurveTo(8.5f, 4.5f, 10.5f, 4.5f, 12f, 4.5f),
                    PathNode.CurveTo(13.5f, 4.5f, 15.5f, 4.5f, 15.5f, 7f),
                    PathNode.LineTo(15.5f, 10f),
                ),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                pathFillType = PathFillType.NonZero,
            )
        }.build()
}
