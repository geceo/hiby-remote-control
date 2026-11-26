import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        // All shared code lives in src/jvmShared and is compiled once per target
        // (both targets are JVM, so the JDK API is available; see docs/ARCHITECTURE.md).
        // commonMain stays empty and only carries the dependencies.
        val commonMain by getting {
            dependencies {
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api(compose.materialIconsExtended)
                api(compose.ui)
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        val androidMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
        }
        val desktopMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
            }
        }
        val desktopTest by getting {
            kotlin.srcDir("src/jvmSharedTest/kotlin")
            dependencies {
                implementation("junit:junit:4.13.2")
            }
        }
    }
}

android {
    namespace = "io.github.hibyremote.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
