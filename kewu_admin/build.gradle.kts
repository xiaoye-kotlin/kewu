import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 复用主工程已缓存的 KMP 插件；总控端只启用 JVM Desktop 目标。
    kotlin("multiplatform") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "com.kewu"
version = "1.0.0"

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
                // 与学生端 / 教师端完全一致的 coui 视觉体系。
                implementation("io.github.suqi8.coui.kmp:coui-blur:1.1.0")
                implementation("io.github.suqi8.coui.kmp:coui-ui:1.1.0")
                implementation("io.github.suqi8.coui.kmp:coui-preference:1.1.0")
                implementation("io.github.suqi8.coui.kmp:coui-icons:1.1.0")
                implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.kewu.admin.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "kewu-admin"
            packageVersion = "1.0.0"
            description = "课悟平台总控端"
            vendor = "Kewu"
        }
    }
}
