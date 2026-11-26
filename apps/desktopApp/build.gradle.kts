import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

// The package version must be numeric MAJOR.MINOR.BUILD (MSI requirement). CI passes
// VERSION_NAME (the tag without "v" on releases); anything else falls back to 1.0.<run number>.
val appVersion: String = System.getenv("VERSION_NAME")
    ?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }
    ?: "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

compose.desktop {
    application {
        mainClass = "io.github.hibyremote.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "hiby-remote"
            packageVersion = appVersion
            description = "Remote control for HiBy players over Wi-Fi (HiByLink)"
            vendor = "HiBy Remote contributors"
            copyright = "HiBy Remote contributors"
            // Result of :desktopApp:suggestRuntimeModules (java.prefs backs the settings store).
            modules("java.instrument", "java.prefs", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menu = true
                shortcut = true
                perUserInstall = true
                // Stable: lets a new MSI upgrade the installed one instead of installing side by side.
                upgradeUuid = "6f1c2c1e-3b0e-4d57-9a0b-7d8c5a2f4e19"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
                shortcut = true
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                debMaintainer = "hiby-remote@users.noreply.github.com"
            }
        }
    }
}

// jpackage writes the build host's (Ubuntu) package names into the .deb
// dependencies; make them installable on Debian too (see the script).
val fixDebDepends by tasks.registering(Exec::class) {
    description = "Rewrites the .deb Depends field for Debian compatibility"
    val debDir = layout.buildDirectory.dir("compose/binaries/main/deb")
    commandLine("bash", "-c", "for f in \"$1\"/*.deb; do \"$0\" \"\$f\"; done",
        file("packaging/fix-deb-depends.sh").absolutePath, debDir.get().asFile.absolutePath)
}
tasks.matching { it.name == "packageDeb" }.configureEach { finalizedBy(fixDebDepends) }
