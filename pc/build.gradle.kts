import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

// The TIFF reader, JPEG writer, metadata, zip reading, dates and naming are the phone app's own
// files, compiled in here rather than copied, so the two versions can't drift apart.
val core = "../android/app/src/main/java/com/barion/filmscans/core"
sourceSets {
    main { kotlin.srcDir(core) }
    test {
        kotlin.srcDir("../android/app/src/test/java/com/barion/filmscans/core")
        resources.srcDir("../android/app/src/test/resources")
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.apache.commons:commons-imaging:1.0.0-alpha6")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// 1.0.<build number> from the workflow; Windows installers need three plain numbers.
val appVersion = providers.environmentVariable("APP_VERSION").orElse("1.0.0").get()

compose.desktop {
    application {
        mainClass = "com.barion.filmscans.pc.MainKt"
        jvmArgs += listOf("-Xmx3g")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Et Tu Tiff"
            packageVersion = appVersion
            description = "Film scans: TIFFs to JPEGs, lab downloads unzipped, scan dates kept"
            vendor = "Barion"
            includeAllModules = true
            windows {
                // Installs for this Windows user only: no administrator needed.
                perUserInstall = true
                menu = true
                menuGroup = "Et Tu Tiff"
                shortcut = true
                dirChooser = false
                upgradeUuid = "05e0df52-79cc-48dd-b69c-d979f884a4b7"
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}
