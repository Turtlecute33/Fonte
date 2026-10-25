package app.fonte.source

/** Release notes as plain text: no HTML or images, link text kept, at most [MAX] characters. Nothing is rendered as HTML. */
object Notes {
    private const val MAX = 6000

    /** Text after this is not read: a hostile release text cannot make the patterns below slow. */
    private const val READ = 4 * MAX
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    // No opening bracket inside: a failed match stops at the next one, so long runs stay linear.
    private val IMAGE = Regex("""!\[[^\[\]]*]\([^()]*\)""")
    private val LINK = Regex("""\[([^\[\]]+)]\([^()]*\)""")
    private val TAG = Regex("<[^<>]+>")
    private val EMPHASIS = Regex("""(\*\*|__|~~|`)""")
    private val HEADING = Regex("""(?m)^#{1,6}\s*""")
    // Spaces and tabs only: \s would also take line breaks and make long runs of blank lines slow.
    private val BULLET = Regex("""(?m)^([ \t]*)[*+-][ \t]+""")
    private val BLANKS = Regex("""\n{3,}""")

    fun plain(markdown: String?): String {
        if (markdown.isNullOrBlank()) return ""
        var s = markdown.take(READ).replace("\r\n", "\n")
        s = COMMENT.replace(s, "")
        s = IMAGE.replace(s, "")
        s = LINK.replace(s, "$1")
        s = TAG.replace(s, "")
        s = EMPHASIS.replace(s, "")
        s = HEADING.replace(s, "")
        s = BULLET.replace(s, "$1• ")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        s = BLANKS.replace(s, "\n\n").trim()
        return if (s.length > MAX || markdown.length > READ) s.take(MAX).trimEnd() + "…" else s
    }
}
