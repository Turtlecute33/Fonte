package app.fonte.source

import app.fonte.data.Asset

/**
 * Picks the APKs of a release that fit this device: the user's filter first, then the name. A name that names only
 * ABIs this device cannot run is out. The device's main ABI ranks first, then universal builds, then other ABIs
 * the device runs; debug builds rank last.
 */
object Apks {
    private val ABI_WORDS = listOf(
        "arm64-v8a" to listOf("arm64-v8a", "arm64v8a", "arm64", "aarch64", "armv8a", "armv8"),
        "armeabi-v7a" to listOf("armeabi-v7a", "armeabiv7a", "armeabi", "armv7a", "armv7", "arm32", "arm"),
        "x86_64" to listOf("x86_64", "x86-64", "x64", "amd64"),
        "x86" to listOf("x86", "i686", "i386"),
    )
    private val WORDS = ABI_WORDS.associate { (abi, words) -> abi to words.map { Regex("(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])") } }
    private val UNIVERSAL = Regex("(?<![a-z0-9])(universal|all|fat|noarch)(?![a-z0-9])")
    private val DEBUG = Regex("(?<![a-z0-9])debug(?![a-z0-9])")

    /** The ABIs a file name names. Longer names win first, so "x86_64" does not count as "x86". */
    fun abisIn(fileName: String): Set<String> {
        var rest = fileName.lowercase()
        val found = LinkedHashSet<String>()
        for ((abi, regexes) in WORDS) {
            for (r in regexes) {
                if (r.containsMatchIn(rest)) {
                    found += abi
                    rest = r.replace(rest, " ")
                }
            }
        }
        return found
    }

    fun rank(assets: List<Asset>, filter: String, abis: List<String>): List<Asset> {
        val match = filterOf(filter)
        return assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) && match(it.name) }
            .mapNotNull { asset -> score(asset.name, abis)?.let { asset to it } }
            .sortedByDescending { it.second } // stable: equal scores keep the source's order
            .map { it.first }
    }

    private fun score(name: String, abis: List<String>): Int? {
        val named = abisIn(name)
        var score = when {
            named.isEmpty() -> if (UNIVERSAL.containsMatchIn(name.lowercase())) 25 else 20
            abis.firstOrNull() in named -> 30
            named.any { it in abis } -> 10
            else -> return null
        }
        if (DEBUG.containsMatchIn(name.lowercase())) score -= 15
        return score
    }

    /** A regex (case-insensitive); "!" in front inverts it. A bad regex is plain text. */
    fun filterOf(filter: String): (String) -> Boolean {
        val f = filter.trim()
        if (f.isEmpty()) return { true }
        val invert = f.startsWith("!")
        val body = f.removePrefix("!").trim()
        if (body.isEmpty()) return { true }
        val regex = runCatching { Regex(body, RegexOption.IGNORE_CASE) }.getOrElse { Regex(Regex.escape(body), RegexOption.IGNORE_CASE) }
        return { regex.containsMatchIn(it) != invert }
    }
}
