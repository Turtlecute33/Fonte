package app.fonte.source

import app.fonte.data.Source
import app.fonte.data.SourceKind
import java.net.URI
import java.net.URLDecoder

/** Reads a link that the user pasted or shared into a [Source]. */
object Links {
    /**
     * A source; a host that may run Forgejo/Gitea or GitLab and needs a probe ([Sources.resolve]); or an F-Droid repo
     * link that names no app, so that the user must choose one.
     */
    sealed interface Parsed {
        data class Known(val source: Source) : Parsed
        data class Probe(val host: String, val segments: List<String>) : Parsed
        data class Choose(val host: String, val path: String, val key: String) : Parsed {
            /** The same as [Source.repoAddress] of its apps. */
            val address: String get() = "https://$host" + if (path.isEmpty()) "" else "/$path"

            fun source(pkg: String) = Source(SourceKind.Repo, host, if (path.isEmpty()) pkg else "$path/$pkg", key)
        }
    }

    private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""^[a-z0-9.-]+\.[a-z]{2,}/\S+""", RegexOption.IGNORE_CASE)
    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    /** Owner, repository and group names; "." and ".." are no names (they would move the API path). */
    private val NAME = Regex("""^(?!\.\.?$)[A-Za-z0-9_.-]+$""")
    private val REPO_SCHEME = Regex("fdroidrepos://", RegexOption.IGNORE_CASE)
    private val WWW_HOSTS = setOf("github.com", "gitlab.com", "codeberg.org", "f-droid.org", "fdroid.link")

    fun isPackageName(s: String) = PACKAGE.matches(s)

    /** The first web link in [text] (a browser shares "page title + link"). */
    fun firstUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ';')

    /**
     * [text] may hold other words around the link (a shared page title). [hint] is a source kind from an import,
     * for self-hosted forges. Null when there is no link of a supported kind.
     */
    fun parse(text: String, hint: SourceKind? = null): Parsed? {
        // The F-Droid client's scheme for repo links. fdroidrepo:// (no TLS) stays out.
        val https = text.replace(REPO_SCHEME, "https://")
        val raw = firstUrl(https) ?: https.trim().takeIf { BARE.containsMatchIn(it) }?.let { "https://$it" } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        // "www." goes only from the known hosts: a self-hosted forge can serve only on www. (Review NETWORK-6).
        val host = uri.host?.lowercase()?.let { h -> h.removePrefix("www.").takeIf { it in WWW_HOSTS } ?: h } ?: return null
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, Charsets.UTF_8) }
        // fdroid.link/#<repo link>, the share page of the F-Droid client.
        if (host == "fdroid.link") return uri.fragment?.let { repoLink(it.replace(REPO_SCHEME, "https://")) }
        // A fingerprint makes any link a repo link, also on hosts that run a forge (gitlab.com/…/-/raw/…/repo).
        if (uri.rawQuery?.contains("fingerprint=", ignoreCase = true) == true) return repoLink(raw)

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

    /**
     * An F-Droid repo link with its fingerprint, the SHA-256 of the repo's signing certificate, as the F-Droid client
     * shares it: "https://host/fdroid/repo?fingerprint=…". Fonte adds "#package" for one app ([Source.webUrl]).
     * The host stays as it is ("www." too); no port, no user, no other parameter. Like all sources, the repo is read
     * over https, also from an http link.
     */
    private fun repoLink(link: String): Parsed? {
        val uri = runCatching { URI(link) }.getOrNull()?.takeIf { it.port == -1 && it.rawUserInfo == null } ?: return null
        val host = uri.host?.lowercase() ?: return null
        val params = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }
        val key = params.singleOrNull()?.takeIf { it.first == "fingerprint" }?.second?.lowercase()?.takeIf(Source.KEY::matches) ?: return null
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, Charsets.UTF_8) }
        if (segments.any { !NAME.matches(it) }) return null
        val choose = Parsed.Choose(host, segments.joinToString("/"), key)
        return uri.rawFragment?.takeIf(::isPackageName)?.let { Parsed.Known(choose.source(it)) } ?: choose
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
