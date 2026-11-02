import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("org.jetbrains.compose") version "1.8.0"
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.jaudiotagger)
                implementation(libs.gson)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.demonlab.lune.desktop.MainKt"

        jvmArgs += listOf(
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=25",
            "-XX:G1HeapRegionSize=1m",
            "-XX:CICompilerCount=2",
            "-XX:+UseStringDeduplication",
            "-Xms24m",
            "-Xmx160m"
        )

        nativeDistributions {
            targetFormats(
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb,
                TargetFormat.AppImage
            )
            packageName = "Lune"
            packageVersion = "1.5.4"
            description = "Lune Ultra-Lightweight Multiplatform Offline Music Player (Windows & Linux)"
            vendor = "DemonLab"

            linux {
                modules("java.desktop", "java.logging", "jdk.unsupported")
            }
            windows {
                menuGroup = "Lune"
                upgradeUuid = "8f4b211a-90d4-432b-b95c-71a0e92c3481"
            }
        }
    }
}
