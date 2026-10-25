package app.fonte.source

import app.fonte.data.Asset
import app.fonte.data.TrackedApp
import app.fonte.net.Http
import org.json.JSONArray
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * GitHub releases API. Each request sends the stored ETag; an unchanged list answers 304 with no body. With a token,
 * a 304 does not count against the rate limit. GitHub publishes a SHA-256 digest for each asset; the download is
 * checked against it.
 */
object GitHub : SourceApi {
    @Volatile private var blockedUntil = 0L

    override suspend fun check(app: TrackedApp, ctx: Ctx): Check {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) throw limitError(blockedUntil)
        val token = ctx.githubToken()
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            token?.let { put(Http.AUTHORIZATION, "Bearer $it") }
            Sources.etagFor(app)?.let { put("If-None-Match", it) }
        }
        val r = ctx.http.get("https://api.github.com/repos/${app.source.path}/releases?per_page=${Sources.PAGE}", headers)
        when (r.code) {
            304 -> return Check.Same
            200 -> Unit
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
        return Check.Found(Sources.pick(parse(r.text), app, ctx.abis), r.header("ETag"))
    }

    fun parse(json: String): List<Raw> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }.map { o ->
            val assets = o.optJSONArray("assets") ?: JSONArray()
            Raw(
                tag = o.getString("tag_name"),
                prerelease = o.optBoolean("prerelease"),
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
    }

    private fun limitError(until: Long) = IOException(
        "GitHub limit reached until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))}. A token in Settings raises it.",
    )
}
