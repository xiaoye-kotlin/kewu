package com.kewu

import com.kewu.db.DatabaseFactory
import com.kewu.plugins.*
import com.kewu.recserver.installRecServices
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

// 程序入口, 把各个模块装起来
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        module()
    }.start(wait = true)
}

// 把序列化、CORS、鉴权、路由几个配置挂到 Application 上
fun Application.module() {
    // 数据库先行：连不上就直接崩，绝不静默跑一份空数据糊弄前端
    DatabaseFactory.init()
    // 录音子系统（原 8081）的服务要在路由注册前装好，见 installRecServices 注释
    installRecServices()
    configureSerialization()
    configureCors()
    configureAuthentication()
    configureCallLogging()
    configureRouting()
}
