package de.findesk.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/**
 * Separate data sets (e.g. "Personal" and "Business"). A profile is a data directory: the default profile is the
 * root itself, so data from before profiles existed stays where it is; others live in `profiles/<slug>/`.
 * Profiles never share a database, documents or HBCI passports (passports are keyed by bank id).
 * The list and the profile to open at the next start are kept in `profiles.properties` in the root.
 */
class ProfileStore(private val rootDir: File = AppPaths.rootDir) {
    /** [slug] null is the default profile. */
    data class Profile(val slug: String?, val name: String, val dir: File) {
        val isDefault: Boolean get() = slug == null
    }

    companion object {
        private const val FILE = "profiles.properties"
        private const val KEY_ACTIVE = "active"
        private const val NAME_PREFIX = "name."
        private const val DEFAULT_KEY = "default"
        const val DEFAULT_NAME = "Personal"
        const val MAX_NAME_LENGTH = 40
        private val SLUG = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun profileDir(rootDir: File, slug: String): File {
            require(SLUG.matches(slug) && slug != DEFAULT_KEY) { "Invalid profile id: $slug" }
            return File(File(rootDir, "profiles"), slug)
        }
    }

    fun list(): List<Profile> {
        val props = load()
        val others = props.stringPropertyNames()
            .filter { it.startsWith(NAME_PREFIX) && it != NAME_PREFIX + DEFAULT_KEY }
            .map { it.removePrefix(NAME_PREFIX) }
            .filter { SLUG.matches(it) && profileDir(rootDir, it).isDirectory }
            .map { Profile(it, props.getProperty(NAME_PREFIX + it), profileDir(rootDir, it)) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.slug }))
        return listOf(defaultProfile(props)) + others
    }

    /** The profile to open; the default one if the stored profile's folder is gone. */
    fun active(): Profile {
        val slug = load().getProperty(KEY_ACTIVE)?.takeIf { it.isNotEmpty() }
        return list().firstOrNull { it.slug == slug } ?: list().first()
    }

    fun create(name: String): Profile {
        val clean = validName(name)
        val props = load()
        val base = clean.lowercase()
            .replace('ä', 'a').replace('ö', 'o').replace('ü', 'u').replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "profile" }
            .take(MAX_NAME_LENGTH).trim('-')
        val taken = props.stringPropertyNames().map { it.removePrefix(NAME_PREFIX) }.toSet() + DEFAULT_KEY
        val slug = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
            .first { it !in taken && !profileDir(rootDir, it).exists() }
        val dir = profileDir(rootDir, slug).apply { mkdirs() }
        props.setProperty(NAME_PREFIX + slug, clean)
        save(props)
        return Profile(slug, clean, dir)
    }

    fun rename(slug: String?, name: String) {
        val clean = validName(name)
        val props = load()
        if (slug != null) require(props.containsKey(NAME_PREFIX + slug)) { "Unknown profile: $slug" }
        props.setProperty(NAME_PREFIX + (slug ?: DEFAULT_KEY), clean)
        save(props)
    }

    /** Takes effect at the next start. */
    fun setActive(slug: String?) {
        val props = load()
        if (slug != null) require(list().any { it.slug == slug }) { "Unknown profile: $slug" }
        props.setProperty(KEY_ACTIVE, slug.orEmpty())
        save(props)
    }

    /**
     * Moves the profile's folder to `profiles-deleted/` (nothing is erased) and returns where it went.
     * The default profile and the one currently open cannot be deleted.
     */
    fun delete(slug: String, openSlug: String? = AppPaths.activeProfile): File {
        val props = load()
        require(props.containsKey(NAME_PREFIX + slug)) { "Unknown profile: $slug" }
        require(slug != openSlug) { "The open profile can't be deleted" }
        val source = profileDir(rootDir, slug)
        val target = File(File(rootDir, "profiles-deleted"), "$slug-${LocalDateTime.now().format(STAMP)}")
        if (source.exists()) {
            target.parentFile.mkdirs()
            Files.move(source.toPath(), target.toPath())
        }
        props.remove(NAME_PREFIX + slug)
        if (props.getProperty(KEY_ACTIVE) == slug) props.setProperty(KEY_ACTIVE, "")
        save(props)
        return target
    }

    private fun defaultProfile(props: Properties) = Profile(null, props.getProperty(NAME_PREFIX + DEFAULT_KEY) ?: DEFAULT_NAME, rootDir)

    private fun validName(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name is required" }
        require(clean.length <= MAX_NAME_LENGTH) { "Name is too long (max. $MAX_NAME_LENGTH characters)" }
        return clean
    }

    private fun load(): Properties = Properties().apply {
        val file = File(rootDir, FILE)
        if (file.exists()) file.reader(Charsets.UTF_8).use { load(it) }
    }

    /** Written to a temp file and moved into place, so a crash never leaves a half-written list. */
    private fun save(props: Properties) {
        rootDir.mkdirs()
        val tmp = File.createTempFile("profiles", ".tmp", rootDir)
        try {
            tmp.writer(Charsets.UTF_8).use { props.store(it, "FinDesk profiles") }
            Files.move(tmp.toPath(), File(rootDir, FILE).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }
}
