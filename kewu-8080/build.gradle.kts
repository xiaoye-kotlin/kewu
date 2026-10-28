// 课悟 8080 业务后端（队友 2026-10-22 交付的完整源码，Ktor 3.3.3）
// 独立工程只为构建/部署用。2026-10-26 起原 8081 录音服务（../server）已并入本工程 com.kewu.recserver，
// 对外只剩 8080 一个端口
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("com.gradleup.shadow") version "8.3.0"
}

group = "com.kewu"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.3.3")
    implementation("io.ktor:ktor-server-netty:3.3.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
    implementation("io.ktor:ktor-server-auth:3.3.3")
    implementation("io.ktor:ktor-server-auth-jwt:3.3.3")
    implementation("io.ktor:ktor-server-cors:3.3.3")
    implementation("io.ktor:ktor-server-call-logging:3.3.3")
    // 录音存华为云 OBS（原 8081 的依赖，2026-10-26 两个后端合并为一个）
    implementation("com.huaweicloud:esdk-obs-java:3.25.10")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("ch.qos.logback:logback-classic:1.5.15")
    // MySQL 持久化：Exposed DSL + 驱动 + Hikari 连接池（0.61.0 是最后的 0.x，包名是经典的 exposed.sql.*）
    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    // 学情雷达缓存 + 防击穿分布式锁（与 8081 同一个 Redis 服务，不同 key 命名空间）
    implementation("io.lettuce:lettuce-core:6.4.0.RELEASE")
    // 调华为云 MaaS（盘古大模型）做语义提取
    implementation("io.ktor:ktor-client-core:3.3.3")
    implementation("io.ktor:ktor-client-cio:3.3.3")
}

tasks.shadowJar {
    archiveBaseName.set("kewu-8080")
    archiveVersion.set("")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "com.kewu.ApplicationKt")
    }
}
