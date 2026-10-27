package app.fonte.source

import app.fonte.data.Asset
import app.fonte.data.Release
import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.TrackedApp
import app.fonte.net.Http
import app.fonte.net.HttpGet
import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime

/**
 * What a source needs: the HTTP client, the GitHub token (read off the main thread), and the device's ABIs.
 * [tokenRefused] gets the token that GitHub refused.
 */
class Ctx(http: Lazy<HttpGet>, val githubToken: () -> String?, val abis: List<String>, val tokenRefused: (String) -> Unit = {}) {
    val http: HttpGet by http
}

sealed interface Check {
    /** The source answered "not modified" to the stored ETag: [TrackedApp.latest] still holds. */
    data object Same : Check

    /** [release] is null when no recent release has an APK for this device. */
    data class Found(val release: Release?, val etag: String?) : Check
}

interface SourceApi {
    suspend fun check(app: TrackedApp, ctx: Ctx): Check
}

/** A release as a code host lists it, before the APK choice. */
data class Raw(
    val tag: String,
    val prerelease: Boolean,
    val date: Long?,
    val notes: String?,
    val page: String?,
    val assets: List<Asset>,
)

object Sources {
    /** Releases per request: one request covers repositories that publish other files between APK releases. */
    const val PAGE = 10

    fun api(kind: SourceKind): SourceApi = when (kind) {
        SourceKind.GitHub -> GitHub
        SourceKind.GitLab -> GitLab
        SourceKind.Forgejo -> Forgejo
        SourceKind.FDroid, SourceKind.Izzy -> FDroid
    }

    /** The newest release in [raws] (newest first) that the app's settings allow and that has an APK for this device. */
    fun pick(raws: List<Raw>, app: TrackedApp, abis: List<String>): Release? {
        for (r in raws) {
            if (r.prerelease && !app.prerelease) continue
            val apks = Apks.rank(r.assets, app.apkFilter, abis)
            if (apks.isNotEmpty()) {
                return Release(tag = r.tag, version = Versions.fromTag(r.tag), date = r.date, notes = Notes.plain(r.notes), page = r.page, assets = apks)
            }
        }
        return null
    }

    /** The source behind a link. A link to an unknown host is probed for a Forgejo/Gitea API, then a GitLab API. */
    suspend fun resolve(text: String, ctx: Ctx, hint: SourceKind? = null): Source {
        val parsed = Links.parse(text, hint) ?: throw IOException("Not a GitHub, GitLab, Codeberg, Forgejo, F-Droid or IzzyOnDroid link")
        if (parsed is Links.Parsed.Known) return parsed.source
        parsed as Links.Parsed.Probe
        var failure: IOException? = null
        suspend fun probe(url: String, field: String): Boolean = try {
            val r = ctx.http.get(url, mapOf("Accept" to "application/json"), maxBytes = 1 shl 20)
            r.code == 200 && r.text.contains("\"$field\"")
        } catch (e: IOException) {
            failure = failure ?: e
            false
        }
        Links.repo(SourceKind.Forgejo, parsed.host, parsed.segments)?.source?.let {
            if (probe("https://${it.host}/api/v1/repos/${it.path}", "full_name")) return it
        }
        Links.gitlab(parsed.host, parsed.segments)?.source?.let {
            if (probe("https://${it.host}/api/v4/projects/${encode(it.path)}", "path_with_namespace")) return it
        }
        throw failure ?: IOException("${parsed.host} runs no GitLab, Forgejo or Gitea that Fonte can read")
    }

    /** The stored ETag, sent back as If-None-Match. A source can give each phone its own ETag and so link its checks. */
    fun etagFor(app: TrackedApp): String? = app.etag?.takeIf { app.latest != null }

    fun date(iso: String?): Long? = if (iso.isNullOrEmpty()) null else runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

    fun encode(path: String): String = URLEncoder.encode(path, Charsets.UTF_8)

    fun fail(r: Http.Response, source: String): Nothing = throw IOException(
        when (r.code) {
            404 -> "Not found on $source"
            401 -> "$source refused the token"
            403, 429 -> "$source refused the request (HTTP ${r.code})"
            in 500..599 -> "$source is unavailable (HTTP ${r.code})"
            else -> "$source answered HTTP ${r.code}"
        },
    )
}
