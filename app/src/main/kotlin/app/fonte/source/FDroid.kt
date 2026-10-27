package app.fonte.source

import app.fonte.data.Asset
import app.fonte.data.Release
import app.fonte.data.SourceKind
import app.fonte.data.TrackedApp
import org.json.JSONObject

/**
 * F-Droid and IzzyOnDroid through their small per-package API, not the multi-megabyte index. The repo's suggested
 * version is the stable one; with pre-releases on, the highest version code. Apps with one build per ABI list one
 * version code per build: all of them are candidates, and the installer picks the one that fits.
 * The API has no signature: Fonte trusts its HTTPS answer. The pin and the version code protect the APK, so a hostile
 * server can only hide updates; only a first install checks the signed index ([app.fonte.install.RepoIndex]).
 */
object FDroid : SourceApi {
    fun base(kind: SourceKind) = if (kind == SourceKind.Izzy) "https://apt.izzysoft.de/fdroid" else "https://f-droid.org"

    override suspend fun check(app: TrackedApp, ctx: Ctx): Check {
        val s = app.source
        val headers = buildMap {
            put("Accept", "application/json")
            Sources.etagFor(app)?.let { put("If-None-Match", it) }
        }
        val r = ctx.http.get("${base(s.kind)}/api/v1/packages/${s.path}", headers)
        when (r.code) {
            304 -> return Check.Same
            200 -> Unit
            else -> Sources.fail(r, s.kind.label)
        }
        return Check.Found(parse(r.text, s.path, base(s.kind), app.prerelease, s.webUrl), r.header("ETag"))
    }

    fun parse(json: String, pkg: String, base: String, prerelease: Boolean, page: String?): Release? {
        val o = JSONObject(json)
        val list = o.optJSONArray("packages") ?: return null
        val versions = (0 until list.length()).map { list.getJSONObject(it) }
            .map { it.optString("versionName") to it.optLong("versionCode") }
            .filter { it.second > 0 }
        if (versions.isEmpty()) return null
        val suggested = o.optLong("suggestedVersionCode")
        val chosen = (if (prerelease) null else versions.firstOrNull { it.second == suggested }) ?: versions.maxBy { it.second }
        val group = versions.filter { it.first == chosen.first }.sortedByDescending { it.second }
        val ordered = listOf(chosen) + (group - chosen)
        return Release(
            tag = chosen.first,
            version = chosen.first,
            code = group.minOf { it.second },
            page = page,
            assets = ordered.map { (_, code) -> Asset("${pkg}_$code.apk", "$base/repo/${pkg}_$code.apk") },
        )
    }
}
