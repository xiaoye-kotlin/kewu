package com.kewu.app.permissions

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuBlue
import com.kewu.app.KewuDeepBlue
import com.kewu.app.KewuSky

// 启动先走一遍权限获取流程：逐个说明理由、点同意后继续，最后才进主界面
@Composable
fun PermissionFlow(
    requester: PermissionRequester,
    onGranted: @Composable () -> Unit,
) {
    var index by remember { mutableStateOf(0) }
    var granted by remember { mutableStateOf(setOf<String>()) }
    var finished by remember { mutableStateOf(false) }
    val list = KewuPermission.entries

    // 全部点完就直接切到主界面（onGranted 是 Composable，得在可组合上下文里调用）
    if (finished) {
        onGranted()
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(KewuDeepBlue, KewuBlue, KewuSky))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("欢迎使用课悟", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("进入前，先把这些权限交给我们", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(22.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                list.forEachIndexed { i, perm ->
                    val done = granted.contains(perm.key)
                    val current = i == index
                    PermissionCard(perm, done, current)
                }
            }
        }

        // 底部按钮：逐条授权，全部给完再进主界面
        PermissionActions(
            index = index,
            total = list.size,
            onGrant = { requester.launch(list[index]) { ok -> if (ok) granted = granted + list[index].key } },
            onAdvance = { if (index < list.lastIndex) index++ else finished = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PermissionCard(perm: KewuPermission, done: Boolean, current: Boolean) {
    val border = when {
        done -> KewuBlue.copy(alpha = 0.9f)
        current -> Color.White.copy(alpha = 0.9f)
        else -> Color.White.copy(alpha = 0.3f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (done) KewuBlue else Color.White.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (done) "✓" else (KewuPermission.entries.indexOf(perm) + 1).toString(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(perm.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(perm.reason, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun PermissionActions(
    index: Int,
    total: Int,
    onGrant: () -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val perm = KewuPermission.entries[index]
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(KewuSky)
                .clickable { onGrant() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("授权「${perm.title}」", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .clickable { onAdvance() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (index < total - 1) "下一步" else "开始使用", color = Color.White, fontSize = 15.sp)
        }
    }
}
