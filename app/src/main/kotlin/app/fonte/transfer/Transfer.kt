package app.fonte.transfer

import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.Store.Companion.list
import app.fonte.data.Store.Companion.str
import app.fonte.data.TrackedApp
import app.fonte.source.Links
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Reads app lists exported by Obtainium (any version) or by Fonte, and writes Fonte's own export. */
object Transfer {
    data class Entry(
        val url: String,
        /** Set for Fonte exports; Obtainium entries are resolved from [url] and [hint]. */
        val source: Source? = null,
        val hint: SourceKind? = null,
        val packageName: String? = null,
        val name: String? = null,
        val prerelease: Boolean = false,
        val filter: String = "",
        val autoUpdate: Boolean = true,
        val cert: String? = null,
        /** An Obtainium source that Fonte does not read (APKMirror, HTML pages, …). */
        val unsupported: Boolean = false,
    )

    private const val FORMAT = "fonte"

    /** Throws [org.json.JSONException] when [text] is no app list. */
    fun parse(text: String): List<Entry> = when (val root = JSONTokener(text).nextValue()) {
        is JSONArray -> obtainium(root)
        is JSONObject -> when {
            root.has(FORMAT) -> fonte(root)
            root.has("apps") -> obtainium(root.getJSONArray("apps"))
            else -> throw org.json.JSONException("No apps in this file")
        }
        else -> throw org.json.JSONException("No apps in this file")
    }

    private fun obtainium(apps: JSONArray): List<Entry> = (0 until apps.length()).mapNotNull { apps.optJSONObject(it) }.mapNotNull { o ->
        val url = o.optString("url").ifEmpty { return@mapNotNull null }
        // Older exports keep the settings as a JSON string, newer ones as an object.
        val raw = o.opt("additionalSettings")
        val settings = raw as? JSONObject ?: (raw as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val regex = settings.optString("apkFilterRegEx").trim()
        val override = o.optString("overrideSource").takeIf { it.isNotEmpty() && it != "null" }
        val hint = when (override) {
            "GitHub" -> SourceKind.GitHub
            "GitLab" -> SourceKind.GitLab
            "Codeberg" -> SourceKind.Forgejo
            "FDroid" -> SourceKind.FDroid
            "IzzyOnDroid" -> SourceKind.Izzy
            else -> null
        }
        Entry(
            url = url,
            hint = hint,
            unsupported = override != null && hint == null,
            packageName = o.optString("id").takeIf(Links::isPackageName),
            name = o.optString("name").ifBlank { null },
            prerelease = settings.optBoolean("includePrereleases"),
            filter = if (regex.isNotEmpty() && settings.optBoolean("invertAPKFilter")) "!$regex" else regex,
        )
    }

    private fun fonte(root: JSONObject): List<Entry> = root.list("apps") {
        val source = runCatching { Source(SourceKind.valueOf(getString("kind")), getString("host"), getString("path")) }.getOrNull()
        Entry(
            url = source?.webUrl ?: optString("url"),
            source = source,
            packageName = str("package"),
            name = str("name"),
            prerelease = optBoolean("prerelease"),
            filter = optString("filter"),
            autoUpdate = optBoolean("autoUpdate", true),
            cert = str("cert"),
        )
    }

    /** The list, settings and pinned certificates; no token, no release data. */
    fun export(apps: List<TrackedApp>): String = JSONObject()
        .put(FORMAT, 1)
        .put(
            "apps",
            JSONArray(
                apps.map {
                    JSONObject().put("kind", it.source.kind.name).put("host", it.source.host).put("path", it.source.path)
                        .put("url", it.source.webUrl).put("package", it.packageName).put("name", it.name)
                        .put("prerelease", it.prerelease).put("filter", it.apkFilter).put("autoUpdate", it.autoUpdate).put("cert", it.cert)
                },
            ),
        )
        .toString(2)
}
