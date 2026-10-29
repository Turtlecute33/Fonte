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
            .filter { it.name.length <= MAX_NAME && it.name.endsWith(".apk", ignoreCase = true) && match(it.name) }
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

    /**
     * A regex (case-insensitive); "!" in front inverts it. A bad regex is plain text, and so is a [slow] one: the filter
     * can come from an import file, and nothing can stop a match that runs.
     */
    fun filterOf(filter: String): (String) -> Boolean {
        val f = filter.trim()
        if (f.isEmpty()) return { true }
        val invert = f.startsWith("!")
        val body = f.removePrefix("!").trim()
        if (body.isEmpty()) return { true }
        val regex = runCatching { check(!slow(body)); Regex(body, RegexOption.IGNORE_CASE) }
            .getOrElse { Regex(Regex.escape(body), RegexOption.IGNORE_CASE) }
        return { regex.containsMatchIn(it) != invert }
    }

    private const val MAX_NAME = 200
    private const val MAX_FILTER = 200
    private const val MAX_REPEATS = 4
    private const val MAX_CHOICES = 6
    private const val REPEAT = "*+{"
    private val LOOKAROUND = listOf("(?=", "(?!", "(?<=", "(?<!")

    /**
     * True for a regex that can backtrack for minutes: longer than [MAX_FILTER], a backreference, more than
     * [MAX_REPEATS] repeats, more than [MAX_CHOICES] repeats, "?" and "|" together, or a repeated group with a repeat,
     * a "?" or a "|" in it ("(a+)+", "((a+))+", "(a|a)*"). Also true for text that this check cannot read: "\Q", "\c",
     * "(?#", the "x" flag, a "[" in a class and a "]" at the start of a class. A check of the text, not a proof: it does
     * not find all slow patterns.
     */
    internal fun slow(p: String): Boolean {
        if (p.length > MAX_FILTER) return true
        // For each open group: true when a repeat, "?" or "|" is in it. Null for a lookaround, which matches no text.
        val groups = ArrayList<Boolean?>()
        fun mark() {
            if (groups.lastOrNull() == false) groups[groups.lastIndex] = true
        }
        var repeats = 0
        var choices = 0
        var inClass = false
        var i = 0
        while (i < p.length) {
            val c = p[i]
            val next = p.getOrElse(i + 1) { ' ' }
            when {
                c == '\\' -> if (next in '1'..'9' || next in "ckQ") return true else i++
                inClass -> if (c == '[') return true else inClass = c != ']'
                c == '[' -> if (next == ']' || p.startsWith("^]", i + 1)) return true else inClass = true
                c == '(' -> {
                    groups += if (LOOKAROUND.any { p.startsWith(it, i) }) null else false
                    if (next == '?') {
                        i++ // "(?:", "(?i)" or a lookaround: this "?" is not a repeat
                        if (p.getOrNull(i + 1) == '#' || 'x' in p.drop(i + 1).takeWhile { it.isLetter() || it == '-' }) return true
                    }
                }
                c == ')' -> if (groups.removeLastOrNull() == true) {
                    if (next in REPEAT) return true
                    mark()
                }
                c in REPEAT || c == '?' || c == '|' -> {
                    if (c in REPEAT && ++repeats > MAX_REPEATS) return true
                    if (++choices > MAX_CHOICES) return true
                    mark()
                }
            }
            i++
        }
        return false
    }
}
