import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "de.findesk"
version = "1.9.0"

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

    // macOS Keychain access for a PIN the user chooses to remember (also pulled in by tess4j; pinned here explicitly)
    implementation("net.java.dev.jna:jna:5.19.1")

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
            // TLS cipher suites for bank connections, JMX for HikariCP, XML for JAXB (HBCI4Java), extra charsets for FinTS,
            // and locale data: java.base only carries English, so without jdk.localedata the packaged app showed English
            // month names and "€0.00" in German and French (./gradlew run, on a full JDK, never did)
            modules("java.instrument", "java.naming", "java.prefs", "java.smartcardio", "java.sql", "java.management", "jdk.unsupported", "java.xml", "jdk.crypto.ec", "jdk.charsets", "jdk.localedata")

            packageName = "FinDesk"
            packageVersion = "1.9.0"
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
                // The languages FinDesk is translated into, so macOS can use them for the system-provided parts of the
                // UI. The JDK's own app-menu items (About, Preferences…, Hide, Quit) stay English regardless.
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleAllowMixedLocalizations</key>
                        <true/>
                        <key>CFBundleLocalizations</key>
                        <array>
                            <string>en</string>
                            <string>de</string>
                            <string>fr</string>
                        </array>
                    """.trimIndent()
                }
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
        )
        // -Xdock: is macOS-only; any other JVM refuses to start with it.
        if (System.getProperty("os.name").lowercase().contains("mac")) jvmArgs += "-Xdock:name=FinDesk"
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
    // Keep tests away from the real OS credential store, like the user.home backstop above
    systemProperty("findesk.credentialStore", "none")
    // One test JVM on purpose: with maxParallelForks the Linux CI JVM crashed natively in Tesseract
    // (SIGSEGV in libstdc++, PR #37), likely from a different native-library load order per fork.
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

// The DMG's *volume* icon (/.VolumeIcon.icns, shown in Finder for the mounted disk) is the half of the
// icon that Compose cannot set: it passes --icon only when it builds the app image, and jpackage builds
// the DMG from that image with its own JavaApp.icns (Duke) as the volume icon. There is no DSL option for
// it and the plugin wipes jpackage's --resource-dir, so the finished DMG is patched instead: converted
// to read-write, mounted, the icon replaced, and compressed again. jpackage already sets the volume's
// custom-icon flag, so only the file changes. See https://github.com/MaMissaoui/FinDesk/issues/3.
val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg")
val isMacHost = System.getProperty("os.name").lowercase().contains("mac")

fun runTool(vararg command: String): String {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
    return output.trim()
}

/** [runTool] without the exit-code check, for probes whose "no" is an answer: null when the tool is missing. */
fun runToolOrNull(vararg command: String): Pair<Int, String>? = try {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    // trimEnd, not trim: porcelain's first line starts with the index status, and a leading space is meaningful
    process.waitFor() to output.trimEnd()
} catch (_: IOException) {
    null // e.g. git is not installed
}

/** Mounts [dmg] at a fresh directory, runs [action] on the mount point and always detaches it again. */
fun <T> withMountedDmg(dmg: File, readOnly: Boolean, action: (File) -> T): T {
    val mount = layout.buildDirectory.dir("tmp/dmg-mount").get().asFile.apply {
        deleteRecursively()
        mkdirs()
    }
    runTool("hdiutil", "attach", "-nobrowse", "-noautoopen", if (readOnly) "-readonly" else "-readwrite", "-mountpoint", mount.path, dmg.path)
    try {
        return action(mount)
    } finally {
        runTool("hdiutil", "detach", mount.path, "-force")
    }
}

fun singleDmg(): File {
    val dmgs = dmgDir.get().asFile.listFiles().orEmpty().filter { it.extension == "dmg" }
    check(dmgs.size == 1) { "Expected one DMG in ${dmgDir.get().asFile}, found ${dmgs.map { it.name }}" }
    return dmgs.single()
}

val setDmgVolumeIcon = tasks.register("setDmgVolumeIcon") {
    group = "distribution"
    description = "Replaces jpackage's default (Duke) volume icon in the packaged DMG with the FinDesk icon"
    onlyIf { isMacHost }
    inputs.file(iconIcns)
    doLast {
        val dmg = singleDmg()
        val tmp = layout.buildDirectory.dir("tmp/dmg-icon").get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val writable = File(tmp, "rw.dmg")
        runTool("hdiutil", "convert", dmg.path, "-format", "UDRW", "-o", writable.path)
        withMountedDmg(writable, readOnly = false) { mount ->
            val volumeIcon = File(mount, ".VolumeIcon.icns")
            check(volumeIcon.isFile) { "${dmg.name} has no .VolumeIcon.icns; jpackage's DMG layout changed" }
            Files.copy(iconIcns.toPath(), volumeIcon.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val compressed = File(tmp, "out.dmg")
        runTool("hdiutil", "convert", writable.path, "-format", "UDZO", "-imagekey", "zlib-level=9", "-o", compressed.path)
        Files.move(compressed.toPath(), dmg.toPath(), StandardCopyOption.REPLACE_EXISTING)
        tmp.deleteRecursively()
        println("Volume icon set: ${dmg.name} now shows ${iconIcns.name}")
    }
}

tasks.matching { it.name == "packageDmg" }.configureEach { finalizedBy(setDmgVolumeIcon) }

tasks.register("verifyInstallerIcon") {
    group = "distribution"
    description = "Fails if the packaged app image (or a built DMG's volume) carries jpackage's Duke fallback instead of the repository's icon"
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
            // The DMG carries its own copy as the volume icon (see setDmgVolumeIcon), checked when one was built
            dmgDir.get().asFile.listFiles().orEmpty().filter { it.extension == "dmg" }.forEach { dmg ->
                val volumeIcon = withMountedDmg(dmg, readOnly = true) { mount -> sha256(File(mount, ".VolumeIcon.icns")) }
                check(volumeIcon == sha256(iconIcns)) { "${dmg.name} has volume icon $volumeIcon, not ${iconIcns.name} (${sha256(iconIcns)})" }
                println("Icon OK: the volume icon of ${dmg.name} matches ${iconIcns.name}")
            }
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

// Refuses to install a work-in-progress build: `install-mac.command` / `./gradlew installMacApp`
// otherwise package whatever the checkout holds, so a feature branch with uncommitted, unreviewed
// changes silently became the user's real app. installMacApp depends on this, and
// createDistributable/verifyInstallerIcon wait for it, so a failing check builds nothing.
//   ./gradlew checkInstallSource [-Pforce]
tasks.register("checkInstallSource") {
    group = "distribution"
    description = "Fails when this checkout has uncommitted changes or its HEAD is not a release tag (pass -Pforce to install anyway)"
    doLast {
        val force = providers.gradleProperty("force").isPresent
        // git is not required: a source zip has no checkout to verify, and installing from one was always allowed
        val status = runToolOrNull("git", "status", "--porcelain")?.takeIf { it.first == 0 }
        val source: String
        if (status == null) {
            println("Warning: not a git checkout (or git is not installed), so FinDesk cannot tell whether this is a released build.")
            source = "an unversioned checkout"
        } else {
            // Porcelain lines are "XY <path>"; name the first five so the failure is actionable
            val changed = status.second.lines().map { it.drop(3).trim() }.filter { it.isNotEmpty() }
            if (changed.isNotEmpty() && !force) {
                error("This checkout has uncommitted changes (${changed.take(5).joinToString(", ")}). Install from a clean checkout of a release, or pass -Pforce to install it anyway.")
            }
            val described = runToolOrNull("git", "describe", "--tags", "--exact-match", "--match", "v*", "HEAD") ?: (1 to "")
            val tag = described.second.takeIf { described.first == 0 && it.isNotEmpty() }
            val sha = runTool("git", "rev-parse", "--short", "HEAD")
            val branch = runTool("git", "rev-parse", "--abbrev-ref", "HEAD").let { if (it == "HEAD") "detached HEAD" else it }
            if (tag == null && !force) {
                error("HEAD ($sha on $branch) is not a release. Check out a release first, e.g. git checkout v$version, or pass -Pforce.")
            }
            source = tag ?: "$branch@$sha"
        }
        if (force) println("Warning: -Pforce is set, installing FinDesk $version from $source even if it is not a clean release.")
        println("Installing FinDesk $version from $source")
    }
}

// Decide before anything is packaged, so a failed check leaves the build directory untouched
tasks.matching { it.name == "createDistributable" }.configureEach { mustRunAfter("checkInstallSource") }
tasks.matching { it.name == "verifyInstallerIcon" }.configureEach { mustRunAfter("checkInstallSource") }

// One-step local install on macOS: builds the self-contained FinDesk.app (it carries its own Java runtime) and
// puts it into /Applications. A previous FinDesk.app is moved to the Trash, not deleted, so it can be dragged
// back. `install-mac.command` runs this on a double-click in Finder.
//   ./gradlew installMacApp [-PinstallDir=<folder>]    (default /Applications)
tasks.register("installMacApp") {
    group = "distribution"
    description = "Builds FinDesk.app and installs it into /Applications (a previous version goes to the Trash)"
    dependsOn("createDistributable", "verifyInstallerIcon", "checkInstallSource")
    val installDir = providers.gradleProperty("installDir").orElse("/Applications")
    doLast {
        check(isMacHost) { "installMacApp builds a macOS app bundle and only runs on macOS" }
        val built = packagedAppDir.get().asFile.resolve("FinDesk.app")
        check(built.isDirectory) { "No app bundle at $built" }
        runTool("codesign", "--verify", "--deep", "--strict", built.path)
        val version = runTool("defaults", "read", File(built, "Contents/Info.plist").path, "CFBundleShortVersionString")

        val target = File(installDir.get(), "FinDesk.app")
        // Replacing the bundle under a running app would crash it or leave it half old, half new
        val running = ProcessHandle.allProcesses().anyMatch { it.info().command().orElse("").startsWith("${target.path}/") }
        check(!running) { "FinDesk is running from $target. Quit it, then install again." }
        if (target.exists()) {
            val previous = runCatching { runTool("defaults", "read", File(target, "Contents/Info.plist").path, "CFBundleShortVersionString") }.getOrDefault("unknown")
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"))
            val trashed = File(System.getProperty("user.home"), ".Trash/FinDesk $previous (replaced $stamp).app")
            // mv, not Files.move: it also handles an install folder on another volume
            runTool("mv", target.path, trashed.path)
            println("Previous FinDesk $previous moved to the Trash as \"${trashed.name}\"")
        }
        // ditto keeps the bundle's signature, permissions and extended attributes intact
        runTool("ditto", built.path, target.path)
        runTool("codesign", "--verify", "--deep", "--strict", target.path)
        println("Installed FinDesk $version to $target")
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
