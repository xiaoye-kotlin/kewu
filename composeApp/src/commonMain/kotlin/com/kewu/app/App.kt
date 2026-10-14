package com.kewu.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.permissions.AppGate
import com.kewu.app.ui.MainScreen
import kotlinx.coroutines.delay

// 课悟品牌色，后面几个界面都复用
val KewuBlue = Color(0xFF1450C8)
val KewuDeepBlue = Color(0xFF0B1E4B)
val KewuSky = Color(0xFF2F7BF0)

private enum class Phase { Splash, Main }

@Composable
fun App() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = KewuBlue,
            background = KewuDeepBlue,
        ),
    ) {
        // 启动先放一段过场动画，结束后再切到主界面
        var phase by remember { mutableStateOf(Phase.Splash) }
        Crossfade(targetState = phase, animationSpec = tween(900), label = "startup") { p ->
            when (p) {
                Phase.Splash -> SplashScreen(onDone = { phase = Phase.Main })
                Phase.Main -> AppGate { MainScreen() }
            }
        }
    }
}

// 启动页：呼吸式 logo 加上渐入文字，播完自动进入主界面
@Composable
private fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(1500); onDone() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(KewuDeepBlue, KewuBlue, KewuSky))),
        contentAlignment = Alignment.Center,
    ) {
        val scale by rememberInfiniteTransition(label = "breath").animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
            label = "breathScale",
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = androidx.compose.animation.fadeIn(tween(600)) +
                    androidx.compose.animation.scaleIn(tween(700)),
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .scale(scale)
                        .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("课", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(24.dp))

            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                    androidx.compose.animation.slideInVertically(tween(700)) { it / 2 },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("课悟 · Kewu", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "学生端",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        modifier = Modifier.height(24.dp),
                    )
                }
            }
        }
    }
}
