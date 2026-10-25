package app.fonte.source

import app.fonte.data.Asset
import app.fonte.data.TrackedApp
import org.json.JSONArray

/** Forgejo and Gitea releases API (Codeberg and self-hosted). */
object Forgejo : SourceApi {
    override suspend fun check(app: TrackedApp, ctx: Ctx): Check {
        val s = app.source
        val headers = buildMap {
            put("Accept", "application/json")
            Sources.etagFor(app)?.let { put("If-None-Match", it) }
        }
        val r = ctx.http.get("https://${s.host}/api/v1/repos/${s.path}/releases?limit=${Sources.PAGE}", headers)
        when (r.code) {
            304 -> return Check.Same
            200 -> Unit
            else -> Sources.fail(r, s.label)
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
                    Asset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size"))
                },
            )
        }
    }
}
