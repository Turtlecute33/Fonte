package app.fonte.source

import app.fonte.data.TrackedApp
import kotlin.math.max

object Versions {
    /** Is [latest][TrackedApp.latest] newer than what is installed ([code], [name])? */
    fun hasUpdate(app: TrackedApp, code: Long, name: String): Boolean {
        val latest = app.latest ?: return false
        // F-Droid repos give version codes: an exact answer.
        latest.code?.let { return code < it }
        // Installed through Fonte and not changed since: the release tag is exact. A release of an older branch is no
        // update (Review SOURCES-2).
        if (app.installedTag != null && app.installedCode == code) {
            return latest.tag != app.installedTag && compare(latest.version, fromTag(app.installedTag)) >= 0
        }
        return compare(latest.version, name) > 0
    }

    /**
     * Above zero when [a] is newer than [b]. Compares the first run of dotted numbers ("v1.10.2-beta" → 1.10.2), or a
     * first date with hyphens ("r2024-11-01" → 2024.11.1); when those are equal, a final release is newer than a
     * pre-release, then the numbers after the version that touch no letter decide: the build in "0.69.0(402)" is newer
     * than "0.69.0". Versions with no numbers that differ count as newer: the installer then finds the same version
     * code and records it, so this happens once.
     */
    fun compare(a: String, b: String): Int {
        val ma = NUMBERS.find(a)
        val mb = NUMBERS.find(b)
        if (ma == null || mb == null) return if (normal(a) == normal(b)) 0 else 1
        val c = order(parts(ma.value), parts(mb.value))
        if (c != 0) return c
        val pa = isPre(a)
        val pb = isPre(b)
        if (pa != pb) return if (pa) -1 else 1
        return order(builds(a, ma.range.last + 1), builds(b, mb.range.last + 1))
    }

    private fun order(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until max(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** True when [a] is newer than [b] and both have a version number: then the order is certain. */
    fun higher(a: String, b: String): Boolean = numbers(a) != null && numbers(b) != null && compare(a, b) > 0

    /** A YYYY-M-D date first: in "2024-11-01" the plain dotted run is only "2024". A suffix such as "-rc1" stays out. */
    private val NUMBERS = Regex("""\d{4}-\d{1,2}-\d{1,2}(?!\d)|\d+(?:\.\d+)*""")
    private val PRE = Regex("""(?i)(?<![a-z])(alpha|beta|rc|pre|prerelease|preview|dev|nightly|snapshot|canary)(?![a-z])""")

    /** True when [s] names a pre-release channel as a full word ("beta2", "rc.1"); "Preferences" and "Devices" do not. */
    fun isPre(s: String) = PRE.containsMatchIn(s)

    /** A number with no letter or digit next to it: "(402)" and "-2" count, the digits of a commit hash do not. */
    private val BUILD = Regex("""(?<![\p{L}\d])\d+(?![\p{L}\d])""")

    private fun numbers(s: String): List<Long>? = NUMBERS.find(s)?.value?.let(::parts)

    private fun parts(run: String): List<Long> = run.split('.', '-').map { it.toLongOrNull() ?: Long.MAX_VALUE }

    private fun builds(s: String, from: Int): List<Long> =
        BUILD.findAll(s, from).map { it.value.toLongOrNull() ?: Long.MAX_VALUE }.toList()

    private fun normal(s: String) = s.trim().lowercase().removePrefix("v")

    /** The version to show for a tag: "v1.2.3" → "1.2.3"; other tags stay as they are. */
    fun fromTag(tag: String): String = if (tag.length > 1 && (tag[0] == 'v' || tag[0] == 'V') && tag[1].isDigit()) tag.substring(1) else tag
}
