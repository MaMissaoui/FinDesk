import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "de.findesk"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.61.0")

    // SQLite JDBC
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // PDF text extraction and report export
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    // OCR (needs a native Tesseract install, e.g. `brew install tesseract tesseract-lang`)
    implementation("net.sourceforge.tess4j:tess4j:5.20.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.exposed:exposed-core:0.61.0")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

compose.desktop {
    application {
        mainClass = "de.findesk.MainKt"

        nativeDistributions {
            packageName = "FinDesk"
            packageVersion = "1.0.0"
            description = "Your Financial Workspace - Modern banking and document management"
            vendor = "FinDesk"
            copyright = "© 2026 FinDesk. All rights reserved."

            // macOS
            macOS {
                bundleID = "de.findesk.app"
                targetFormats(TargetFormat.Dmg)
            }

            // Windows
            windows {
                dirChooser = true
                perUserInstall = true
                menu = true
                menuGroup = "FinDesk"
                shortcut = true
                targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            }

            // Linux
            linux {
                debMaintainer = "info@findesk.de"
                menuGroup = "Office"
                appCategory = "Office"
                packageName = "findesk"
                targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            }
        }

        // JVM arguments
        jvmArgs += listOf(
            "-Xmx1024m",
            "-Dfile.encoding=UTF-8",
        )
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Keep tests away from the real ~/.findesk data directory
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { testHome.mkdirs() }
    systemProperty("user.home", testHome.absolutePath)
}

ktlint {
    version.set("1.8.0")
}

// Task to build current platform distribution
tasks.register("buildDistribution") {
    group = "distribution"
    description = "Builds distribution package for current platform"
    dependsOn("package")
}

// Print build info
tasks.register("buildInfo") {
    group = "help"
    description = "Shows build information"
    doLast {
        val os = System.getProperty("os.name").lowercase()
        val platform = when {
            os.contains("mac") -> "macOS"
            os.contains("win") -> "Windows"
            os.contains("linux") -> "Linux"
            else -> "Unknown"
        }

        println(
            """
            |FinDesk Build Information
            |========================
            |Version: $version
            |Platform: $platform
            |
            |Build Commands:
            |  ./gradlew run                - Run the application
            |  ./gradlew build              - Build the project
            |  ./gradlew test               - Run tests
            |  ./gradlew package            - Build installer for current platform
            |  ./gradlew createDistributable - Create portable app bundle
            |
            |Platform Formats:
            |  macOS:   DMG installer
            |  Windows: MSI installer, EXE portable
            |  Linux:   DEB, RPM packages
            """.trimMargin(),
        )
    }
}
