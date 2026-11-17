import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 复用主工程已缓存的 KMP 插件；总控端只启用 JVM Desktop 目标。
    kotlin("multiplatform") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "com.kewu"
version = "1.0.19"

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                    freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
                }
            }
        }
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-okhttp:2.3.12")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // OSHI：跨平台操作系统/硬件信息（CPU 型号/负载、物理内存、磁盘、系统运行时长、
                // 进程 CPU/内存、网络流量、TCP 连接数），取代慢且易失败的 PowerShell 采样（§81）。
                implementation("com.github.oshi:oshi-core:7.8.0")
                // OSHI 依赖 slf4j-api；桌面端不需要日志输出，绑定 nop 避免启动时打
                // "No SLF4J providers were found" 警告。
                implementation("org.slf4j:slf4j-nop:2.0.20")
                // 与学生端 / 教师端完全一致的 coui 视觉体系。
                implementation("io.github.suqi8.coui.kmp:coui-blur:1.1.0")
                implementation("io.github.suqi8.coui.kmp:coui-ui:1.1.0")
                implementation("io.github.suqi8.coui.kmp:coui-preference:1.1.0")
                implementation("io.github.suqi8.coui.kmp:coui-icons:1.1.0")
                implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("junit:junit:4.13.2")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.kewu.admin.MainKt"
        // 正式打包**禁用 ProGuard**：ProGuard 优化会改坏 okio 的合成方法签名
        // （运行时 VerifyError: Bad return type in okio/Okio.source），导致 OkHttp 一建连就崩、
        // 所有网络请求失败。依赖原样打包即可，proguard-rules.pro 保留仅作历史说明。
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "kewu-admin"
            packageVersion = "1.0.19"
            description = "课悟平台总控端"
            vendor = "Kewu"
        }
    }
}
