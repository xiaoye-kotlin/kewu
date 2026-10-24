package com.kewu.server.services

import com.kewu.server.model.DeviceStatus
import com.kewu.server.model.RecordingDeviceView
import com.kewu.server.model.RecordingDevicesResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 录音设备雷达与状态监控的 Service 层：负责从 Redis 读设备状态、做统计。
// Controller（路由）只调 getDeviceReport，不碰 Redis、不算统计，符合"Controller 只收参数和返回"的分工。
// 全程高容错：Redis 挂、sessionId 空、某条数据脏，都不会让接口崩，统一降级成空雷达。
class RecordingDeviceService(private val redis: RedisService) {

    // 对外唯一入口：拿一份设备监控报告。任何异常路径都返回"空雷达"（totalDevices=0, devices=[]）。
    suspend fun getDeviceReport(sessionId: String): RecordingDevicesResponse {
        val devices = readDevices(sessionId)
        return summarize(devices)
    }

    // 读 Redis 并逐条解析。空 sessionId / 没数据，直接走降级，不浪费一次连接。
    private suspend fun readDevices(sessionId: String): List<DeviceStatus> {
        if (sessionId.isBlank()) return emptyList()
        val raw = redis.getDeviceStatuses(sessionId)
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<DeviceStatus>(raw.size)
        for ((fieldId, json) in raw) {
            out.add(parseOne(fieldId, json))
        }
        return out
    }

    // 单条容错解析：字段缺失、类型不对、整条不是合法 JSON，都兜成安全的 DeviceStatus，绝不抛 NPE。
    // deviceId 优先用 JSON 里带的；没有就用哈希的 field 名兜底，保证每条都有 id。
    private fun parseOne(fieldId: String, json: String): DeviceStatus {
        val safeId = fieldId.ifBlank { "unknown" }
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val deviceId = obj["deviceId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: safeId
            val type = obj["type"]?.jsonPrimitive?.content ?: ""
            val status = obj["status"]?.jsonPrimitive?.content ?: ""
            val qualityScore = obj["qualityScore"]?.jsonPrimitive?.intOrNull ?: 0
            DeviceStatus(deviceId = deviceId, type = type, status = status, qualityScore = qualityScore)
        } catch (t: Throwable) {
            // 这一条坏了不影响整体，deviceId 兜底用哈希 field，其他给空串/0
            DeviceStatus(deviceId = safeId, type = "", status = "", qualityScore = 0)
        }
    }

    // 统计：总数、手机端数量、大屏是否在线、质量分最高的设备。
    private fun summarize(devices: List<DeviceStatus>): RecordingDevicesResponse {
        val total = devices.size
        val phoneCount = devices.count { it.type.equals("phone", ignoreCase = true) }
        val screen = devices.firstOrNull { it.type.equals("screen", ignoreCase = true) }
        // 大屏"在线" = 存在且状态是 online（不区分大小写）；offline/其它都算不在线
        val screenOnline = screen != null && screen.status.equals("online", ignoreCase = true)
        val best = devices.maxByOrNull { it.qualityScore }
        val bestDeviceId = best?.deviceId?.takeIf { it.isNotBlank() }
        val views = devices.map {
            RecordingDeviceView(it.deviceId, it.type, it.status, it.qualityScore)
        }
        return RecordingDevicesResponse(
            totalDevices = total,
            phoneCount = phoneCount,
            screenOnline = screenOnline,
            bestDeviceId = bestDeviceId,
            devices = views
        )
    }
}
