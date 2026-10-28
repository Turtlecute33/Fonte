package app.fonte.source

import app.fonte.data.Asset
import app.fonte.data.TrackedApp
import app.fonte.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * GitHub releases API. Each request sends the stored ETag; an unchanged list answers 304 with no body. With a token,
 * a 304 does not count against the rate limit. The token goes with each check, also of public repositories, so
 * GitHub links all checks to the account (the token sheet tells the user). GitHub refuses an expired or revoked
 * token (401) also for public repositories: then the request goes again without it, and later requests send no token
 * until the user replaces it.
 * GitHub publishes a SHA-256 digest for each asset; the download is checked against it.
 * A repository can publish more pre-releases than [Sources.PAGE] between two stable releases (Brave: nightly and beta
 * builds). When the page holds only pre-releases and the app takes none, the release that GitHub marks as latest
 * (never a draft or a pre-release) comes from one more request.
 * A release whose title names a pre-release channel is a pre-release also without the flag: Brave does not mark all
 * of its "Nightly" releases.
 */
object GitHub : SourceApi {
    @Volatile private var blockedUntil = 0L

    override suspend fun check(app: TrackedApp, ctx: Ctx): Check {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) throw limitError(blockedUntil)
        var token = ctx.githubToken()
        var r = releases(app, ctx, token)
        if (r.code == 401 && token != null) {
            ctx.tokenRefused(token)
            token = null
            r = releases(app, ctx, null)
        }
        when (r.code) {
            304 -> return Check.Same
            200 -> Unit
            else -> fail(r, token, now)
        }
        val raws = parse(r.text)
        var release = Sources.pick(raws, app, ctx.abis)
        if (release == null && !app.prerelease && raws.size >= Sources.PAGE && raws.all { it.prerelease }) {
            val latest = releases(app, ctx, token, latest = true)
            // 404: the repository has no stable release.
            if (latest.code == 200) release = Sources.pick(listOf(raw(JSONObject(latest.text))), app, ctx.abis)
            else if (latest.code != 404) fail(latest, token, now)
        }
        // The ETag is the one of the list: while the list does not change, the release from /latest holds too.
        return Check.Found(release, r.header("ETag"))
    }

    private fun fail(r: Http.Response, token: String?, now: Long): Nothing = when (r.code) {
        // GitHub answers 404, not 403, for a private repository that the request may not read.
        404 -> throw IOException(
            if (token == null) "Not found on GitHub. A private repository needs a token in Settings."
            else "Not found on GitHub. For a private repository, the token needs read access to Contents.",
        )
        403, 429 -> if (r.header("x-ratelimit-remaining") == "0" || r.header("retry-after") != null) {
            blockedUntil = r.header("x-ratelimit-reset")?.toLongOrNull()?.times(1000)
                ?: (now + (r.header("retry-after")?.toLongOrNull() ?: 900) * 1000)
            throw limitError(blockedUntil)
        } else {
            Sources.fail(r, "GitHub")
        }
        else -> Sources.fail(r, "GitHub")
    }

    /** The newest [Sources.PAGE] releases, with the stored ETag; with [latest], only the release that GitHub marks as latest. */
    private suspend fun releases(app: TrackedApp, ctx: Ctx, token: String?, latest: Boolean = false): Http.Response {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            token?.let { put(Http.AUTHORIZATION, "Bearer $it") }
            if (!latest) Sources.etagFor(app)?.let { put("If-None-Match", it) }
        }
        val path = if (latest) "releases/latest" else "releases?per_page=${Sources.PAGE}"
        return ctx.http.get("https://api.github.com/repos/${app.source.path}/$path", headers)
    }

    fun parse(json: String): List<Raw> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }.map(::raw)
    }

    private fun raw(o: JSONObject): Raw {
        val assets = o.optJSONArray("assets") ?: JSONArray()
        return Raw(
            tag = o.getString("tag_name"),
            prerelease = o.optBoolean("prerelease") || Versions.isPre(o.optString("name")),
            date = Sources.date(o.optString("published_at")),
            notes = o.optString("body"),
            page = o.optString("html_url").ifEmpty { null },
            assets = (0 until assets.length()).map { assets.getJSONObject(it) }.map { a ->
                Asset(
                    name = a.getString("name"),
                    url = a.getString("browser_download_url"),
                    size = a.optLong("size"),
                    sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
                    api = a.optString("url").ifEmpty { null },
                )
            },
        )
    }

    private fun limitError(until: Long) = IOException(
        "GitHub limit reached until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))}. A token in Settings raises it.",
    )
}
