package app.fonte.data

enum class SourceKind(val label: String) {
    GitHub("GitHub"),
    GitLab("GitLab"),
    Forgejo("Forgejo"),
    FDroid("F-Droid"),
    Izzy("IzzyOnDroid"),
}

/** Where an app comes from. [path] is "owner/repo" on code hosts and the package name on F-Droid repos. */
data class Source(val kind: SourceKind, val host: String, val path: String) {
    val id: String get() = "${kind.name}:$host/$path".lowercase()

    val webUrl: String
        get() = when (kind) {
            SourceKind.FDroid -> "https://$host/packages/$path/"
            SourceKind.Izzy -> "https://apt.izzysoft.de/fdroid/index/apk/$path"
            else -> "https://$host/$path"
        }

    /** "GitHub", "Codeberg", "F-Droid", or the host of a self-hosted forge. */
    val label: String
        get() = when {
            kind == SourceKind.Forgejo && host == "codeberg.org" -> "Codeberg"
            kind == SourceKind.GitHub || kind == SourceKind.FDroid || kind == SourceKind.Izzy -> kind.label
            host == "gitlab.com" -> "GitLab"
            else -> host
        }

    /** A name to show before the app is installed: the repository name or the package name. */
    val defaultName: String get() = path.substringAfterLast('/')
}

/**
 * A downloadable APK. [sha256] is set when the source publishes a digest (GitHub). [api] is the GitHub API URL of the
 * file: with a token it also downloads from private repositories.
 */
data class Asset(val name: String, val url: String, val size: Long = 0, val sha256: String? = null, val api: String? = null)

data class Release(
    /** The tag (code hosts) or version name (F-Droid repos) that identifies the release. */
    val tag: String,
    /** The version to show and to compare with the installed version name. */
    val version: String,
    /** F-Droid repos only: the lowest version code of this version (ABI builds have one code each). */
    val code: Long? = null,
    val date: Long? = null,
    val notes: String = "",
    val page: String? = null,
    /** APKs that fit this device, best first. Never empty. */
    val assets: List<Asset>,
)

data class TrackedApp(
    /** [Source.id]. */
    val id: String,
    val source: Source,
    val name: String,
    /** Known after the first install, from an import, or from the F-Droid source. */
    val packageName: String? = null,
    val prerelease: Boolean = false,
    /** Regex an APK name must match; a leading "!" means it must not match. Empty: automatic choice. */
    val apkFilter: String = "",
    val autoUpdate: Boolean = true,
    /** SHA-256 of the signing certificate, pinned at the first install or adoption. */
    val cert: String? = null,
    /** Release tag and version code of the last install through Fonte: exact update checks after that. */
    val installedTag: String? = null,
    val installedCode: Long? = null,
    val latest: Release? = null,
    val etag: String? = null,
    val checkedAt: Long = 0,
    val error: String? = null,
    val addedAt: Long = 0,
)

data class Settings(
    val background: Boolean = true,
    val intervalHours: Int = 6,
    val autoInstall: Boolean = true,
    /** Automatic installs download only on an unmetered network; checks use any network. A stored choice stays. */
    val unmeteredOnly: Boolean = true,
)

data class Data(val apps: List<TrackedApp> = emptyList(), val settings: Settings = Settings())
