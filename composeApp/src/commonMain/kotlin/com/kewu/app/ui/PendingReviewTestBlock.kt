package com.kewu.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuApi
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 作业模块视角拓展的联调块: 跨课程待批阅聚合
// 挂在教师端「控制台」页, 学生端看不到
@Composable
fun PendingReviewTestBlock() {
    val client = remember { HttpClient() }
    val scope = rememberCoroutineScope()
    val base = KewuApi.BASE_URL

    var teacherId = remember { mutableStateOf("tea001") }
    var result = remember { mutableStateOf("点下面的按钮试试") }
    var expanded by remember { mutableStateOf(false) }

    suspend fun get(path: String): String = try {
        val resp = try { client.get(base + path) } catch (e: ResponseException) { e.response }
        renderPending(resp.bodyAsText())
    } catch (e: Exception) {
        "出错: ${e.message}"
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起待批阅联调 ▲" else "跨课程待批阅（作业模块拓展）▼")
        }

        if (!expanded) return@Column

        Text("作业模块 · 视角拓展（后端联调）", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

        OutlinedTextField(
            teacherId.value, { teacherId.value = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("老师账号") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch { result.value = get("/api/teacher/pending-review?teacherId=${teacherId.value}") }
            }) { Text("我的待批阅") }
            Button(onClick = {
                scope.launch { result.value = get("/api/assignments/pending-review") }
            }) { Text("全量(所有课)") }
        }

        Text("结果:", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(result.value, fontSize = 11.sp)
        Text(
            "优先级=该先改谁；紧急=优先级≥70；理由里会写明为什么排前面。",
            fontSize = 11.sp
        )
    }
}

// 把跨课程聚合的返回写成人能一眼看懂的样子
private fun renderPending(raw: String): String {
    val obj = try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        return raw.take(300)
    }
    val code = obj["code"]?.jsonPrimitive?.content ?: "?"
    val msg = obj["message"]?.jsonPrimitive?.content.orEmpty()
    if (code != "0") return "code=$code  $msg"
    val d = obj["data"]?.jsonObject ?: return "code=$code 但没数据"

    fun str(k: String) = d[k]?.jsonPrimitive?.content ?: "-"
    val sb = StringBuilder()
    sb.appendLine("待批阅 ${str("total")} 份 | ${str("courseCount")} 门课 | ${str("assignmentCount")} 道作业 | ${str("studentCount")} 名学生")
    sb.appendLine("紧急 ${str("urgentCount")} | 疑似雷同 ${str("highRiskCount")} | 最久积压 ${str("oldestWaitHours")}h | 平均 ${str("avgWaitHours")}h")
    sb.appendLine("范围: ${str("scope")}  老师: ${str("teacherName").ifBlank { "不限" }}")
    sb.appendLine()

    val groups = d["groups"]?.jsonArray ?: return sb.toString()
    if (groups.isEmpty()) {
        sb.appendLine("（当前没有待批阅，很干净）")
        return sb.toString()
    }
    sb.appendLine("— 按课程 —")
    groups.forEach { g ->
        val go = g.jsonObject
        fun gs(k: String) = go[k]?.jsonPrimitive?.content ?: "-"
        sb.appendLine("《${gs("courseName")}》 待批 ${gs("pendingCount")} · 紧急 ${gs("urgentCount")} · 最久 ${gs("oldestWaitHours")}h")
        go["assignments"]?.jsonArray?.forEach { a ->
            val ao = a.jsonObject
            fun as_(k: String) = ao[k]?.jsonPrimitive?.content ?: "-"
            sb.appendLine("   · 《${as_("title")}》 待批 ${as_("pendingCount")} 紧急 ${as_("urgentCount")} 截止 ${as_("deadline")}")
        }
    }

    sb.appendLine()
    sb.appendLine("— 建议先改（按优先级）—")
    d["items"]?.jsonArray?.take(5)?.forEach { i ->
        val io = i.jsonObject
        fun is_(k: String) = io[k]?.jsonPrimitive?.content ?: "-"
        val urgent = if (is_("urgent") == "true") "🔴" else "  "
        sb.appendLine("$urgent ${is_("priority")}分 ${is_("studentName")} · 《${is_("assignmentTitle")}》")
        sb.appendLine("     ${is_("courseName")} | 等 ${is_("waitHours")}h | ${is_("status")} | AI建议 ${is_("aiSuggestedScore")}")
        io["reasons"]?.jsonArray?.forEach { r ->
            sb.appendLine("     - ${r.jsonPrimitive.content}")
        }
    }
    return sb.toString()
}
