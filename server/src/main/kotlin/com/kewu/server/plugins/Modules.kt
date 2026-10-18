package com.kewu.server.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import com.kewu.server.routes.classroomRoutes
import com.kewu.server.routes.healthRoutes
import com.kewu.server.routes.recordingRoutes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json

// 装一下内容协商，前端/多端传来的 JSON 才能自动序列化
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; isLenient = true; ignoreUnknownKeys = true; encodeDefaults = true })
    }
}

// 请求日志 + 默认响应头，排错和跨域友好一点
fun Application.configureMonitoring() {
    install(CallLogging)
    install(DefaultHeaders)
}

// 别让异常堆栈直接漏给前端，统一包一层
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "unknown error")))
        }
    }
}

// 开发阶段放开跨域，真上线再收紧来源
fun Application.configureHttp() {
    install(io.ktor.server.plugins.cors.routing.CORS) {
        anyHost()
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Options)
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
        allowHeader(io.ktor.http.HttpHeaders.Authorization)
        allowNonSimpleContentTypes = true
    }
}

// 路由总装：健康检查、录音上传、课堂缓存三块
fun Application.configureRouting() {
    routing {
        healthRoutes()
        recordingRoutes()
        classroomRoutes()
    }
}
