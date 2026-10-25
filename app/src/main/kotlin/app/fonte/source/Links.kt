package app.fonte.source

import app.fonte.data.Source
import app.fonte.data.SourceKind
import java.net.URI
import java.net.URLDecoder

/** Reads a link that the user pasted or shared into a [Source]. */
object Links {
    /** Either a source, or a host that may run Forgejo/Gitea or GitLab and needs a probe ([Sources.resolve]). */
    sealed interface Parsed {
        data class Known(val source: Source) : Parsed
        data class Probe(val host: String, val segments: List<String>) : Parsed
    }

    private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""^[a-z0-9.-]+\.[a-z]{2,}/\S+""", RegexOption.IGNORE_CASE)
    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val NAME = Regex("""^[A-Za-z0-9_.-]+$""")

    fun isPackageName(s: String) = PACKAGE.matches(s)

    /** The first web link in [text] (a browser shares "page title + link"). */
    fun firstUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ';')

    /**
     * [text] may hold other words around the link (a shared page title). [hint] is a source kind from an import,
     * for self-hosted forges. Null when there is no link of a supported kind.
     */
    fun parse(text: String, hint: SourceKind? = null): Parsed? {
        val raw = firstUrl(text) ?: text.trim().takeIf { BARE.containsMatchIn(it) }?.let { "https://$it" } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, Charsets.UTF_8) }

        return when {
            host == "github.com" -> repo(SourceKind.GitHub, host, segments)
            host == "f-droid.org" || hint == SourceKind.FDroid && host.endsWith("f-droid.org") ->
                segments.lastOrNull(::isPackageName)?.let { Parsed.Known(Source(SourceKind.FDroid, "f-droid.org", it)) }
            host.endsWith("izzysoft.de") || host.endsWith("izzyondroid.org") || hint == SourceKind.Izzy ->
                segments.lastOrNull(::isPackageName)?.let { Parsed.Known(Source(SourceKind.Izzy, "apt.izzysoft.de", it)) }
            host == "gitlab.com" || hint == SourceKind.GitLab -> gitlab(host, segments)
            host == "codeberg.org" || hint == SourceKind.Forgejo -> repo(SourceKind.Forgejo, host, segments)
            segments.size >= 2 -> Parsed.Probe(host, segments)
            else -> null
        }
    }

    /** owner/repo: the first two segments; a ".git" suffix goes. */
    fun repo(kind: SourceKind, host: String, segments: List<String>): Parsed.Known? {
        if (segments.size < 2) return null
        val owner = segments[0]
        val name = segments[1].removeSuffix(".git")
        if (!NAME.matches(owner) || !NAME.matches(name)) return null
        return Parsed.Known(Source(kind, host, "$owner/$name"))
    }

    /** GitLab paths have groups and subgroups; the project path ends at the "-" segment. */
    fun gitlab(host: String, segments: List<String>): Parsed.Known? {
        val path = segments.takeWhile { it != "-" }.map { it.removeSuffix(".git") }
        if (path.size < 2 || path.any { !NAME.matches(it) }) return null
        return Parsed.Known(Source(SourceKind.GitLab, host, path.joinToString("/")))
    }
}
