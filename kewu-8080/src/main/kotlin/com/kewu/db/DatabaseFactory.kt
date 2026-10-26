package com.kewu.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

// 共用的 JSON 实例：宽松、忽略未知键，模型加字段老数据照样读
val kewuJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// 数据库连接 + 建表 + 首次启动灌种子数据
// 连接配置走环境变量，没配就用本机默认（开发机直连）
object DatabaseFactory {
    @Volatile
    var connected: Boolean = false
        private set

    fun init() {
        val url = System.getenv("KEWU_DB_URL") ?: "jdbc:mysql://127.0.0.1:3306/kewu?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai&useSSL=false"
        val user = System.getenv("KEWU_DB_USER") ?: "root"
        val pass = System.getenv("KEWU_DB_PASSWORD") ?: "kewu123456"

        val config = HikariConfig().apply {
            jdbcUrl = url
            username = user
            password = pass
            driverClassName = "com.mysql.cj.jdbc.Driver"
            maximumPoolSize = 10
            minimumIdle = 2
            idleTimeout = 60_000
            connectionTimeout = 10_000
            maxLifetime = 600_000
            isAutoCommit = false
        }
        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.create(*allTables)
            // 老库升级：SchemaUtils.create 只会建缺失的表，不会给已存在的表补列。
            // 不补的话 insert 会直接报 Unknown column，所以这里显式补齐新增列。
            // 列已存在时这个方法返回空列表，重复启动无副作用。
            SchemaUtils.addMissingColumnsStatements(*allTables).forEach { exec(it) }
        }
        // 2026-10-26 用户要求全部用真实数据：不再灌演示课程 / 作业 / 提交 / 签到（SeedData 保留源码仅供本地开发参考）
        connected = true
    }

    // 表是空的才灌种子：重启不会重复插，老数据不丢
    private fun seedIfEmpty() {
        transaction {
            if (Users.selectAll().empty()) {
                SeedData.run()
            }
            // 题目系统的联调作业单独确保存在（线上库非空时 seedIfEmpty 不会跑）
            SeedData.ensureDemoAssignment()
        }
    }
}
