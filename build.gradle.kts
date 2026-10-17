import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "de.findesk"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.61.0")

    // SQLite JDBC with encryption (SQLite3MultipleCiphers fork of xerial's driver, same org.sqlite package;
    // never add org.xerial:sqlite-jdbc next to it) and a small pool, since every keyed open runs the KDF
    implementation("io.github.willena:sqlite-jdbc:3.53.4.0")
    implementation("com.zaxxer:HikariCP:7.1.0")

    // PDF text extraction and report export
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    // OCR (needs a native Tesseract install, e.g. `brew install tesseract tesseract-lang`)
    implementation("net.sourceforge.tess4j:tess4j:5.20.0")

    // FinTS/HBCI online banking
    implementation("com.github.hbci4j:hbci4j-core:4.1.17")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.exposed:exposed-core:0.61.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "de.findesk.MainKt"

        nativeDistributions {
            // From `./gradlew suggestRuntimeModules`, plus modules only loaded at runtime:
            // TLS cipher suites for bank connections, JMX for HikariCP, XML for JAXB (HBCI4Java), extra charsets for FinTS
            modules("java.instrument", "java.naming", "java.prefs", "java.smartcardio", "java.sql", "java.management", "jdk.unsupported", "java.xml", "jdk.crypto.ec", "jdk.charsets")

            packageName = "FinDesk"
            packageVersion = "1.0.0"
            description = "Your Financial Workspace - Modern banking and document management"
            vendor = "FinDesk"
            copyright = "© 2026 FinDesk. All rights reserved."

            // Declared at this level (not inside the per-OS blocks): the plugin registers
            // `package<Format>` tasks only for the formats whose OS matches the build host, so
            // `packageDistributionForCurrentOS` resolves to DMG on macOS, MSI/EXE on Windows and
            // DEB/RPM on Linux. Formats listed inside an OS block are ignored for other hosts.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)

            // The installer icon must be in the native format of each OS: jpackage only accepts
            // .icns on macOS and .ico on Windows. Given anything else it warns
            // ("... is not an ICNS/ICO file and will not be used") and silently falls back to the
            // JDK's JavaApp icon (Duke), so a PNG here would ship the wrong icon. Linux takes .png.
            // icon.icns/icon.ico are generated from FinDesk-icon.svg (see CONTRIBUTING.md).
            macOS {
                bundleID = "de.findesk.app"
                iconFile.set(project.file("src/main/resources/icons/icon.icns"))
            }

            // Windows
            windows {
                dirChooser = true
                perUserInstall = true
                menu = true
                menuGroup = "FinDesk"
                shortcut = true
                iconFile.set(project.file("src/main/resources/icons/icon.ico"))
            }

            // Linux
            linux {
                debMaintainer = "info@findesk.de"
                menuGroup = "Office"
                appCategory = "Office"
                packageName = "findesk"
                iconFile.set(project.file("src/main/resources/icon-512.png"))
            }
        }

        // JVM arguments
        jvmArgs += listOf(
            "-Xmx1024m",
            "-Dfile.encoding=UTF-8",
            "-Xdock:name=FinDesk",
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
    // Headless UI screenshots: ./gradlew test --tests '*ScreenshotTest' -Pscreenshots=<out-dir> [-PscreenshotData=<FINDESK_HOME>]
    providers.gradleProperty("screenshots").orNull?.let { systemProperty("findesk.screenshots", it) }
    providers.gradleProperty("screenshotData").orNull?.let { systemProperty("findesk.screenshotData", it) }
    if (providers.gradleProperty("screenshots").isPresent) outputs.upToDateWhen { false }
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
