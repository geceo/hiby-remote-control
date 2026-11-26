plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is optional: when these environment variables are set (CI
// secrets), the release APK is signed with that key; otherwise it falls back to
// the debug key so that the build always produces an installable APK.
val releaseStoreFile: String? = System.getenv("HIBY_KEYSTORE_FILE")
val releaseStorePassword: String? = System.getenv("HIBY_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("HIBY_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("HIBY_KEY_PASSWORD")
val hasReleaseKey = !releaseStoreFile.isNullOrBlank() && file(releaseStoreFile!!).exists()

android {
    namespace = "io.github.hibyremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hibyremote"
        minSdk = 33
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Dependency versions are pinned on purpose; upgrades are done deliberately.
        disable += "GradleDependency"
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
