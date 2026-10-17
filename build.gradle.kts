import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

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

// jpackage resolves the icon while it builds the *app image*, and the DMG/MSI/EXE/DEB/RPM only copy
// the result, so the app image is the one place to check. Given an icon that is not in the native
// format of the OS, it warns ("... is not an ICNS/ICO file and will not be used") and silently
// substitutes the JDK's JavaApp icon (Duke) — a build that succeeds with the wrong icon, which is
// what the 2026-10-17 packaging audit found. Nothing at compile time catches that, so this compares
// the packaged artifact against the icon files committed in the repository.
//   ./gradlew createDistributable verifyInstallerIcon
val iconIcns = layout.projectDirectory.file("src/main/resources/icons/icon.icns").asFile
val iconIco = layout.projectDirectory.file("src/main/resources/icons/icon.ico").asFile
val iconPng = layout.projectDirectory.file("src/main/resources/icon-512.png").asFile
val packagedAppDir = layout.buildDirectory.dir("compose/binaries/main/app")

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

/** The image payloads an `.ico` declares — the exact bytes an icon resource has to contain. */
fun icoImagePayloads(file: File): List<ByteArray> {
    val bytes = file.readBytes()
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return (0 until header.getShort(4).toInt()).map { i ->
        val entry = 6 + i * 16
        val size = header.getInt(entry + 8)
        val offset = header.getInt(entry + 12)
        bytes.copyOfRange(offset, offset + size)
    }
}

/** [String.indexOf] without allocating a string for the haystack (the launcher is over 100 MB). */
fun ByteArray.containsBytes(needle: ByteArray): Boolean {
    outer@ for (i in 0..size - needle.size) {
        if (this[i] != needle[0]) continue
        for (j in 1 until needle.size) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}

tasks.register("verifyInstallerIcon") {
    group = "distribution"
    description = "Fails if the packaged app image carries jpackage's Duke fallback instead of the repository's icon"
    dependsOn("createDistributable")
    inputs.files(iconIcns, iconIco, iconPng)
    doLast {
        val app = packagedAppDir.get().asFile
        val bundles = app.listFiles().orEmpty().filter { it.isDirectory }
        check(bundles.isNotEmpty()) { "No app image under $app — run createDistributable first" }
        val launchers = bundles.flatMap { it.listFiles().orEmpty().toList() }.filter { it.extension == "exe" }

        // What is present decides how the icon has to look, so the check works on any host
        if (bundles.any { it.name.endsWith(".app") }) {
            val icns = bundles.flatMap { File(it, "Contents/Resources").listFiles().orEmpty().toList() }.filter { it.extension == "icns" }
            check(icns.size == 1) { "Expected one .icns in the app image, found ${icns.map { it.path }}" }
            val packaged = icns.single()
            check(sha256(packaged) == sha256(iconIcns)) { "The app image ships ${packaged.path} (${sha256(packaged)}), not ${iconIcns.name} (${sha256(iconIcns)})" }
            println("Icon OK: ${packaged.name} in the app image matches ${iconIcns.name}")
        } else if (launchers.isNotEmpty()) {
            check(launchers.size == 1) { "Expected one launcher in the app image, found ${launchers.map { it.path }}" }
            val launcher = launchers.single()
            val bytes = launcher.readBytes()
            val images = icoImagePayloads(iconIco)
            val missing = images.filterNot { bytes.containsBytes(it) }
            check(missing.isEmpty()) {
                "${launcher.name} does not embed ${missing.size} of the ${images.size} ${iconIco.name} images (sizes ${missing.map { it.size }}) — jpackage fell back to the JDK icon"
            }
            println("Icon OK: ${launcher.name} embeds all ${images.size} images of ${iconIco.name}")
        } else {
            val png = bundles.flatMap { bundle ->
                File(bundle, "lib").listFiles().orEmpty().toList() + listOfNotNull(File(bundle, "${bundle.name}.png").takeIf(File::isFile))
            }.filter { it.extension == "png" }
            check(png.size == 1) { "Expected one .png in the app image, found ${png.map { it.path }}" }
            val packaged = png.single()
            check(sha256(packaged) == sha256(iconPng)) { "The app image ships ${packaged.path} (${sha256(packaged)}), not ${iconPng.name} (${sha256(iconPng)})" }
            println("Icon OK: ${packaged.name} in the app image matches ${iconPng.name}")
        }
    }
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
