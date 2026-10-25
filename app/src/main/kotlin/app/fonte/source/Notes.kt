package app.fonte.source

/** Release notes as plain text: no HTML or images, link text kept, at most [MAX] characters. Nothing is rendered as HTML. */
object Notes {
    private const val MAX = 6000
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val TAG = Regex("<[^>]+>")
    private val EMPHASIS = Regex("""(\*\*|__|~~|`)""")
    private val HEADING = Regex("""(?m)^#{1,6}\s*""")
    private val BULLET = Regex("""(?m)^(\s*)[*+-]\s+""")
    private val BLANKS = Regex("""\n{3,}""")

    fun plain(markdown: String?): String {
        if (markdown.isNullOrBlank()) return ""
        var s = markdown.replace("\r\n", "\n")
        s = COMMENT.replace(s, "")
        s = IMAGE.replace(s, "")
        s = LINK.replace(s, "$1")
        s = TAG.replace(s, "")
        s = EMPHASIS.replace(s, "")
        s = HEADING.replace(s, "")
        s = BULLET.replace(s, "$1• ")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        s = BLANKS.replace(s, "\n\n").trim()
        return if (s.length > MAX) s.take(MAX).trimEnd() + "…" else s
    }
}
