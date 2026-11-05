plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.jaudiotagger)
            }
        }
        val desktopMain by getting
    }
}
