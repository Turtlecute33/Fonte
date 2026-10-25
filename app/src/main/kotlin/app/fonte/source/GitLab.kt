package app.fonte.source

import app.fonte.data.Asset
import app.fonte.data.TrackedApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * GitLab releases API (gitlab.com and self-hosted). APKs are release links, or uploads that the release text
 * links to ("/uploads/<hash>/<file>.apk"); an upload URL needs the numeric project ID, read once per run.
 */
object GitLab : SourceApi {
    private val UPLOAD = Regex("""\]\((/uploads/[0-9a-f]+/[^)\s]+)\)""")
    private val APK_LINK = Regex("""\((https://[^)\s]+\.apk)\)""", RegexOption.IGNORE_CASE)
    private val projectIds = ConcurrentHashMap<String, Long>()

    override suspend fun check(app: TrackedApp, ctx: Ctx): Check {
        val s = app.source
        val headers = buildMap {
            put("Accept", "application/json")
            Sources.etagFor(app)?.let { put("If-None-Match", it) }
        }
        val r = ctx.http.get("https://${s.host}/api/v4/projects/${Sources.encode(s.path)}/releases?per_page=${Sources.PAGE}", headers)
        when (r.code) {
            304 -> return Check.Same
            200 -> Unit
            else -> Sources.fail(r, s.label)
        }
        val raws = parse(r.text, s.host) { projectId(app, ctx) }
        return Check.Found(Sources.pick(raws, app, ctx.abis), r.header("ETag"))
    }

    /** [projectId] runs only when a release text links to an upload. */
    suspend fun parse(json: String, host: String, projectId: suspend () -> Long): List<Raw> {
        val arr = JSONArray(json)
        val releases = (0 until arr.length()).map { arr.getJSONObject(it) }
        val id = if (releases.any { UPLOAD.containsMatchIn(it.optString("description")) }) projectId() else null
        return releases.map { o -> raw(o, host, id) }
    }

    private fun raw(o: JSONObject, host: String, projectId: Long?): Raw {
        val tag = o.getString("tag_name")
        val description = o.optString("description")
        val assets = ArrayList<Asset>()
        o.optJSONObject("assets")?.optJSONArray("links")?.let { links ->
            for (i in 0 until links.length()) {
                val l = links.getJSONObject(i)
                val url = l.optString("direct_asset_url").ifEmpty { l.optString("url") }
                if (url.isNotEmpty()) assets += Asset(l.optString("name").ifEmpty { fileName(url) }, url)
            }
        }
        if (projectId != null) UPLOAD.findAll(description).forEach { m ->
            val path = m.groupValues[1]
            assets += Asset(fileName(path), "https://$host/-/project/$projectId$path")
        }
        APK_LINK.findAll(description).forEach { assets += Asset(fileName(it.groupValues[1]), it.groupValues[1]) }
        return Raw(
            tag = tag,
            prerelease = o.optBoolean("upcoming_release") || Versions.isPre(tag),
            date = Sources.date(o.optString("released_at")),
            notes = description,
            page = o.optJSONObject("_links")?.optString("self")?.ifEmpty { null },
            assets = assets.distinctBy { it.url },
        )
    }

    private suspend fun projectId(app: TrackedApp, ctx: Ctx): Long = projectIds[app.id] ?: run {
        val s = app.source
        val r = ctx.http.get("https://${s.host}/api/v4/projects/${Sources.encode(s.path)}", mapOf("Accept" to "application/json"))
        if (r.code != 200) Sources.fail(r, s.label)
        JSONObject(r.text).optLong("id").takeIf { it > 0 }?.also { projectIds[app.id] = it } ?: throw IOException("${s.label} sent no project ID")
    }

    private fun fileName(url: String) = URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), Charsets.UTF_8)
}
