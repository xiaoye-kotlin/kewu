package com.kewu.admin.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.admin.AdminApi
import com.kewu.admin.ConfigItem
import com.kewu.admin.ConfigTestResult
import com.kewu.admin.configGroupLabel
import com.kewu.admin.configGroupTestable
import com.kewu.admin.configSourceLabel
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * 密钥与服务配置：管理云端服务器上的全部密钥（华为云 SIS / OBS、AI 大模型、Redis …）。
 * - 密钥只保存在云端（加密存储），接口只返回打码值；密钥输入框初始为空，留空 = 不修改。
 * - 只提交改过的键；"" = 删除云端覆盖值（回落到服务器环境变量 / 默认值）。
 * - restart=true 的项保存后提示重启后端（复用一键部署弹窗，部署会重启后端）。
 */
@Composable
fun ConfigPage(api: AdminApi, notify: (String) -> Unit, onDeploy: () -> Unit) {
    val cs = COUITheme.colorScheme
    var items by remember { mutableStateOf<List<ConfigItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var needRestart by remember { mutableStateOf(false) }
    var bannerKey by remember { mutableIntStateOf(0) }
    var clearTarget by remember { mutableStateOf<ConfigItem?>(null) }
    val edits = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val testResults = remember { mutableStateMapOf<String, ConfigTestResult>() }
    val scope = rememberCoroutineScope()

    fun isDirty(item: ConfigItem): Boolean {
        val edit = edits[item.key] ?: return false
        return if (item.secret) edit.isNotEmpty() else edit != item.value
    }

    val dirty = items.filter(::isDirty)

    suspend fun reload() {
        loading = true
        error = null
        try {
            items = api.configItems()
        } catch (e: Exception) {
            error = e.message ?: "配置加载失败"
        } finally {
            loading = false
        }
    }

    fun markRestart() {
        needRestart = true
        bannerKey++
    }

    fun save() {
        val changes = dirty.associate { it.key to (edits[it.key] ?: "") }
        if (changes.isEmpty()) return
        val restartTouched = dirty.any { it.restart }
        scope.launch {
            saving = true
            try {
                val updated = api.saveConfig(changes)
                items = updated.ifEmpty { api.configItems() }
                edits.clear()
                testResults.clear()
                if (restartTouched) markRestart()
                notify(if (restartTouched) "已保存 ${changes.size} 项，部分需重启后端后生效" else "已保存 ${changes.size} 项，已即时生效")
            } catch (e: Exception) {
                notify(e.message ?: "保存失败")
            } finally {
                saving = false
            }
        }
    }

    fun clearCloud(item: ConfigItem) {
        scope.launch {
            saving = true
            try {
                val updated = api.saveConfig(mapOf(item.key to ""))
                items = updated.ifEmpty { api.configItems() }
                edits.remove(item.key)
                if (item.restart) markRestart()
                notify("已清除「${item.label}」的云端值，改用服务器环境变量 / 默认值")
            } catch (e: Exception) {
                notify(e.message ?: "清除失败")
            } finally {
                saving = false
                clearTarget = null
            }
        }
    }

    fun test(group: String) {
        scope.launch {
            testing[group] = true
            testResults.remove(group)
            testResults[group] = try {
                api.testConfig(group)
            } catch (e: Exception) {
                ConfigTestResult(false, e.message ?: "测试失败")
            }
            testing[group] = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(
                "密钥与服务配置",
                "所有密钥只保存在云端服务器（加密存储），App 端不内置任何密钥；这里修改后对所有用户生效",
            ) {
                AdminButton("刷新", { scope.launch { reload() } }, icon = Icons.Outlined.Refresh, style = AdminButtonStyle.Secondary, enabled = !saving)
                AdminButton(
                    if (dirty.isEmpty()) "保存修改" else "保存修改（${dirty.size}）",
                    ::save,
                    icon = Icons.Outlined.Save,
                    enabled = dirty.isNotEmpty(),
                    loading = saving,
                )
            }

            if (needRestart) {
                key(bannerKey) {
                    RestartBanner(onDeploy = onDeploy, onDismiss = { needRestart = false })
                }
            }

            when {
                loading && items.isEmpty() -> LoadingBlock("正在读取云端配置…")
                error != null && items.isEmpty() -> ErrorBlock(error ?: "加载失败") { scope.launch { reload() } }
                items.isEmpty() -> EmptyBlock("暂无配置项", "后端尚未返回任何可管理的配置")
                else -> Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SecurityNote()
                    val groups = items.map { it.group }.distinct()
                    groups.forEachIndexed { index, group ->
                        ConfigGroupCard(
                            group = group,
                            items = items.filter { it.group == group },
                            index = index + 1,
                            edits = edits,
                            isDirty = ::isDirty,
                            testing = testing[group] == true,
                            result = testResults[group],
                            groupDirty = dirty.any { it.group == group },
                            enabled = !saving,
                            onTest = { test(group) },
                            onEdit = { item, value -> edits[item.key] = value },
                            onRevert = { item -> edits.remove(item.key) },
                            onClear = { item -> clearTarget = item },
                        )
                    }
                    // 给底部浮条留位，最后一组也能滚到浮条之上
                    Spacer(Modifier.height(96.dp))
                }
            }
        }

        DirtyBar(
            count = dirty.size,
            restartCount = dirty.count { it.restart },
            saving = saving,
            onRevert = { edits.clear() },
            onSave = ::save,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    clearTarget?.let { item ->
        ConfirmDialog(
            title = "清除云端值",
            text = "确定清除「${item.label}」（${item.key}）的云端值吗？清除后改用服务器环境变量或内置默认值" +
                if (item.restart) "，需重启后端后生效。" else "，即时生效。",
            confirmText = "确认清除",
            destructive = true,
            onConfirm = { clearCloud(item) },
            onDismiss = { clearTarget = null },
        )
    }
}

/** 安全说明（非玻璃，滚动区内）。 */
@Composable
private fun SecurityNote() {
    val cs = COUITheme.colorScheme
    val p = rememberEnterProgress(1)
    Row(
        Modifier.fillMaxWidth().riseEnter(p)
            .background(KewuGreen.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Shield, null, tint = KewuGreenDeep, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "密钥只显示打码值，输入框留空表示不修改；「清除云端值」后回落到服务器环境变量或默认值。测试连接使用已保存的配置。",
            color = cs.onSurface,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

/** 需重启横幅：液态玻璃 + 琥珀色调，按钮直接打开一键部署弹窗（部署会重启后端）。 */
@Composable
private fun RestartBanner(onDeploy: () -> Unit, onDismiss: () -> Unit) {
    val cs = COUITheme.colorScheme
    GlassStrip(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        index = 0,
        tint = AdminAmber.copy(alpha = 0.07f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).background(AdminAmber.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.RestartAlt, null, tint = AdminAmber, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("部分配置需重启后端后生效", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("一键部署会拉取最新后端并自动重启服务，新配置随之生效", color = cs.onSurfaceSecondary, fontSize = 12.sp)
            }
            AdminButton("一键部署并重启", onDeploy, icon = Icons.Outlined.CloudSync)
            Spacer(Modifier.width(6.dp))
            AdminIconAction(Icons.Outlined.Close, "关闭提示", onClick = onDismiss)
        }
    }
}

private data class GroupStyle(val icon: ImageVector, val accent: Color, val summary: String)

@Composable
private fun groupStyle(group: String): GroupStyle = when (group.uppercase()) {
    "AI" -> GroupStyle(Icons.Outlined.AutoAwesome, Color(0xFF7C3AED), "作业批阅、助教问答等 AI 能力")
    "SIS" -> GroupStyle(Icons.Outlined.GraphicEq, AdminAmber, "课堂录音实时转写")
    "OBS" -> GroupStyle(Icons.Outlined.Cloud, KewuBlue, "录音、附件等对象存储")
    "REDIS" -> GroupStyle(Icons.Outlined.Memory, AdminRed, "会话、课堂状态缓存")
    else -> GroupStyle(Icons.Outlined.Tune, COUITheme.colorScheme.onSurfaceSecondary, "其他服务参数")
}

@Composable
private fun ConfigGroupCard(
    group: String,
    items: List<ConfigItem>,
    index: Int,
    edits: Map<String, String>,
    isDirty: (ConfigItem) -> Boolean,
    testing: Boolean,
    result: ConfigTestResult?,
    groupDirty: Boolean,
    enabled: Boolean,
    onTest: () -> Unit,
    onEdit: (ConfigItem, String) -> Unit,
    onRevert: (ConfigItem) -> Unit,
    onClear: (ConfigItem) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val style = groupStyle(group)
    val cloudCount = items.count { it.source == "db" }
    val unsetCount = items.count { it.source == "unset" }
    Panel(Modifier.fillMaxWidth(), index = index) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(style.accent.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(style.icon, null, tint = style.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(configGroupLabel(group), color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(style.summary)
                        append(" · ${items.size} 项")
                        if (cloudCount > 0) append(" · 云端配置 $cloudCount")
                        if (unsetCount > 0) append(" · 未设置 $unsetCount")
                    },
                    color = cs.onSurfaceSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (configGroupTestable(group)) {
                result?.let {
                    StatusPill(if (it.ok) "连接正常" else "连接失败", if (it.ok) StatusTone.SUCCESS else StatusTone.ERROR)
                    Spacer(Modifier.width(10.dp))
                }
                AdminButton(
                    if (testing) "测试中…" else "测试连接",
                    onTest,
                    icon = Icons.Outlined.NetworkCheck,
                    style = AdminButtonStyle.Secondary,
                    loading = testing,
                    enabled = enabled,
                )
            }
        }
        if (configGroupTestable(group) && (result != null || groupDirty)) {
            TestResultLine(result, groupDirty)
        }
        items.forEach { item ->
            HorizontalDivider(Modifier.padding(horizontal = 22.dp), color = cs.dividerLine.copy(alpha = 0.7f))
            ConfigRow(
                item = item,
                edit = edits[item.key],
                dirty = isDirty(item),
                enabled = enabled,
                onEdit = { onEdit(item, it) },
                onRevert = { onRevert(item) },
                onClear = { onClear(item) },
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun TestResultLine(result: ConfigTestResult?, groupDirty: Boolean) {
    val cs = COUITheme.colorScheme
    key(result) {
        val p = rememberEnterProgress(0, baseDelayMs = 0L)
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 14.dp).riseEnter(p, 10f)) {
            if (result != null) {
                val color = if (result.ok) KewuGreenDeep else AdminRed
                Row(
                    Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (result.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = color, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(result.message, color = color, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            if (groupDirty) {
                Text(
                    "本组有未保存的修改，测试连接使用的是已保存的配置",
                    color = cs.onSurfaceSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = if (result != null) 6.dp else 0.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfigRow(
    item: ConfigItem,
    edit: String?,
    dirty: Boolean,
    enabled: Boolean,
    onEdit: (String) -> Unit,
    onRevert: () -> Unit,
    onClear: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(if (dirty) KewuBlue.copy(alpha = 0.035f) else Color.Transparent)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(250.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (item.secret) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Lock, "密钥", tint = cs.onSurfaceSecondary, modifier = Modifier.size(14.dp))
                }
            }
            Text(item.key, color = cs.onSurfaceSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 2.dp))
            if (item.hint.isNotBlank()) {
                Text(item.hint, color = cs.onSurfaceSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            val label = when {
                item.secret && item.value.isNotBlank() -> "当前 ${item.value} · 留空不修改"
                item.secret -> "未设置，输入新值"
                item.value.isBlank() -> "未设置"
                else -> item.label
            }
            TextField(
                value = if (item.secret) edit ?: "" else edit ?: item.value,
                onValueChange = onEdit,
                modifier = Modifier.fillMaxWidth(),
                backgroundMode = TextFieldMode.Rectangle,
                colors = TextFieldDefaults.textFieldColors(backgroundColor = cs.surface),
                label = label,
                useLabelAsPlaceholder = true,
                singleLine = true,
                enabled = enabled,
                showClearButton = !item.secret,
                showPasswordToggle = item.secret,
            )
            if (dirty) {
                Text(
                    if (!item.secret && edit.isNullOrEmpty()) "已清空：保存后删除云端值" else "已修改，保存后生效",
                    color = KewuBlue,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.width(150.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(configSourceLabel(item.source), sourceTone(item.source))
            StatusPill(if (item.restart) "需重启生效" else "即时生效", if (item.restart) StatusTone.WARNING else StatusTone.SUCCESS)
            if (dirty) {
                AdminButton("撤销修改", onRevert, style = AdminButtonStyle.Ghost, compact = true, enabled = enabled)
            } else if (item.source == "db") {
                AdminButton("清除云端值", onClear, style = AdminButtonStyle.DangerGhost, compact = true, enabled = enabled)
            }
        }
    }
}

private fun sourceTone(source: String): StatusTone = when (source) {
    "db" -> StatusTone.INFO
    "unset" -> StatusTone.WARNING
    else -> StatusTone.NEUTRAL
}

/** 未保存修改浮条：液态玻璃，从底部弹簧滑入 / 滑出（只做位移）。 */
@Composable
private fun DirtyBar(
    count: Int,
    restartCount: Int,
    saving: Boolean,
    onRevert: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdrop = LocalAdminBackdrop.current ?: return
    val cs = COUITheme.colorScheme
    val show = count > 0
    var lastCount by remember { mutableIntStateOf(count) }
    var lastRestart by remember { mutableIntStateOf(restartCount) }
    if (show) {
        lastCount = count
        lastRestart = restartCount
    }
    val y by animateDpAsState(
        if (show) 0.dp else 120.dp,
        spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "dirtyBar",
    )
    if (!show && y >= 119.dp) return
    AdminGlassCard(
        backdrop = backdrop,
        modifier = modifier.padding(bottom = 4.dp).offset(y = y).width(620.dp),
        shape = RoundedCornerShape(22.dp),
        blurRadius = 26.dp,
        tintAlpha = 0.50f,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(KewuBlue, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("已修改 $lastCount 项，尚未保存", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (lastRestart > 0) "其中 $lastRestart 项需重启后端后生效" else "保存后即时生效",
                    color = cs.onSurfaceSecondary,
                    fontSize = 12.sp,
                )
            }
            AdminButton("全部撤销", onRevert, style = AdminButtonStyle.Ghost, enabled = !saving)
            Spacer(Modifier.width(8.dp))
            AdminButton("保存修改", onSave, icon = Icons.Outlined.Save, loading = saving)
        }
    }
}
