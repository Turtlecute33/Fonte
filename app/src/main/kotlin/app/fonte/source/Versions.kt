package app.fonte.source

import app.fonte.data.TrackedApp
import kotlin.math.max

object Versions {
    /** Is [latest][TrackedApp.latest] newer than what is installed ([code], [name])? */
    fun hasUpdate(app: TrackedApp, code: Long, name: String): Boolean {
        val latest = app.latest ?: return false
        // F-Droid repos give version codes: an exact answer.
        latest.code?.let { return code < it }
        // Installed through Fonte and not changed since: the release tag is exact.
        if (app.installedTag != null && app.installedCode == code) return latest.tag != app.installedTag
        return compare(latest.version, name) > 0
    }

    /**
     * Above zero when [a] is newer than [b]. Compares the first run of dotted numbers ("v1.10.2-beta" → 1.10.2), or a
     * first date with hyphens ("r2024-11-01" → 2024.11.1); when those are equal, a final release is newer than a
     * pre-release. Versions with no numbers that differ count as newer: the installer then finds the same version
     * code and records it, so this happens once.
     */
    fun compare(a: String, b: String): Int {
        val na = numbers(a)
        val nb = numbers(b)
        if (na == null || nb == null) return if (normal(a) == normal(b)) 0 else 1
        for (i in 0 until max(na.size, nb.size)) {
            val c = na.getOrElse(i) { 0 }.compareTo(nb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        val pa = isPre(a)
        val pb = isPre(b)
        return when {
            pa == pb -> 0
            pa -> -1
            else -> 1
        }
    }

    /** A YYYY-M-D date first: in "2024-11-01" the plain dotted run is only "2024". A suffix such as "-rc1" stays out. */
    private val NUMBERS = Regex("""\d{4}-\d{1,2}-\d{1,2}(?!\d)|\d+(?:\.\d+)*""")
    private val PRE = Regex("""(?i)(?<![a-z])(alpha|beta|rc|pre|prerelease|preview|dev|nightly|snapshot|canary)(?![a-z])""")

    /** True when [s] names a pre-release channel as a full word ("beta2", "rc.1"); "Preferences" and "Devices" do not. */
    fun isPre(s: String) = PRE.containsMatchIn(s)

    private fun numbers(s: String): List<Long>? =
        NUMBERS.find(s)?.value?.split('.', '-')?.map { it.toLongOrNull() ?: Long.MAX_VALUE }

    private fun normal(s: String) = s.trim().lowercase().removePrefix("v")

    /** The version to show for a tag: "v1.2.3" → "1.2.3"; other tags stay as they are. */
    fun fromTag(tag: String): String = if (tag.length > 1 && (tag[0] == 'v' || tag[0] == 'V') && tag[1].isDigit()) tag.substring(1) else tag
}
