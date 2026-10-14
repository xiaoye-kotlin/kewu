package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuBlue
import com.kewu.app.KewuDeepBlue
import com.kewu.app.KewuSky
import com.kewu.app.ui.icons.AppIcons
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// 底部三个标签，图标从资源取，文字直接写
data class TabItem(
    val label: String,
    val painter: DrawableResource,
)

private val Tabs = listOf(
    TabItem("课堂", AppIcons.Classroom),
    TabItem("助教", AppIcons.Assistant),
    TabItem("任务", AppIcons.Task),
)

@Composable
fun MainScreen() {
    // 当前选中的标签，切页时带一点横向滑动
    var selected by remember { mutableStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(320)) { -it * dir } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(320)) { it * dir } + fadeOut(tween(220)))
            },
            label = "page",
        ) { index ->
            when (index) {
                0 -> PlaceholderPage("课堂", "正在接入课堂内容…")
                1 -> PlaceholderPage("助教", "AI 助教准备中…")
                else -> PlaceholderPage("任务", "任务列表待填充…")
            }
        }

        GlassBottomBar(
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// 占位页：初始化阶段先放个标题，后续往里填内容
@Composable
private fun PlaceholderPage(title: String, hint: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(KewuDeepBlue, KewuBlue, KewuSky))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(hint, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// 苹果风格的悬浮玻璃底栏：半透明白底 + 顶部高光 + 投影，选中项下面滑一个药丸
@Composable
private fun GlassBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 22.dp),
    ) {
        val itemWidth = maxWidth / Tabs.size
        val indicatorOffset by animateDpAsState(
            targetValue = itemWidth * selected,
            animationSpec = tween(360),
            label = "indicator",
        )

        Box(
            Modifier
                .fillMaxWidth()
                .shadow(22.dp, RoundedCornerShape(26.dp), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.72f),
                            Color.White.copy(alpha = 0.55f),
                        ),
                    ),
                )
                .border(0.8.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(26.dp))
                .padding(vertical = 10.dp),
        ) {
            // 滑动选中指示
            Box(
                Modifier
                    .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(KewuBlue.copy(alpha = 0.14f)),
                )
            }

            Row(Modifier.fillMaxWidth()) {
                Tabs.forEachIndexed { index, tab ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(index) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val active = index == selected
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 6.dp),
                        ) {
                            Image(
                                painter = painterResource(tab.painter),
                                contentDescription = tab.label,
                                modifier = Modifier.size(24.dp),
                                colorFilter = ColorFilter.tint(
                                    if (active) KewuBlue else Color(0xFF5B6472),
                                ),
                            )
                            Text(
                                tab.label,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) KewuBlue else Color(0xFF5B6472),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
