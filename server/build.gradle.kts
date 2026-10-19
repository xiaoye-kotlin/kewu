plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
    application
}

group = "com.kewu"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor 全家桶，用 bom 锁版本，避免各模块版本对不上
    implementation(platform("io.ktor:ktor-bom:${libs.versions.ktor.get()}"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-default-headers")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-cors")

    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.kotlinxSerialization.get()}")

    // Redis 客户端，异步非阻塞，跟 Ktor 的协程更搭
    implementation("io.lettuce:lettuce-core:${libs.versions.lettuce.get()}")
    // 华为云 OBS SDK，生产环境把录音存对象存储
    implementation("com.huaweicloud:esdk-obs-java:${libs.versions.obs.get()}")

    testImplementation("io.ktor:ktor-server-tests")
    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("com.kewu.server.ApplicationKt")
}
