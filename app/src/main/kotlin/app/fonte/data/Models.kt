package app.fonte.data

enum class SourceKind(val label: String) {
    GitHub("GitHub"),
    GitLab("GitLab"),
    Forgejo("Forgejo"),
    FDroid("F-Droid"),
    Izzy("IzzyOnDroid"),
    Repo("F-Droid repo"),
}

/**
 * Where an app comes from. [path] is "owner/repo" on code hosts, the package name on F-Droid and IzzyOnDroid, and
 * the repo path and then the package name on other F-Droid repos ("fdroid/repo/org.example.app"). [key] is the
 * SHA-256 of the repo's signing certificate: other F-Droid repos must have one, and only they have one.
 */
data class Source(val kind: SourceKind, val host: String, val path: String, val key: String? = null) {
    init {
        require((kind == SourceKind.Repo) == (key != null) && (key == null || KEY.matches(key))) { "Bad repo key" }
    }

    val id: String get() = "${kind.name}:$host/$path".lowercase()

    /** For other F-Droid repos: the repo link with the fingerprint, as the F-Droid client shares it, and "#package". */
    val webUrl: String
        get() = when (kind) {
            SourceKind.FDroid -> "https://$host/packages/$path/"
            SourceKind.Izzy -> "https://apt.izzysoft.de/fdroid/index/apk/$path"
            SourceKind.Repo -> "$repoAddress?fingerprint=${key?.uppercase()}#$packageName"
            else -> "https://$host/$path"
        }

    /** The package name, on F-Droid repos only. */
    val packageName: String?
        get() = when (kind) {
            SourceKind.FDroid, SourceKind.Izzy -> path
            SourceKind.Repo -> path.substringAfterLast('/')
            else -> null
        }

    /** The address of an F-Droid repo, with no "/" at the end: the files are below it. */
    val repoAddress: String get() = "https://$host" + path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "/$it" }

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

    companion object {
        val KEY = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * A downloadable APK. [sha256] is set when the source publishes a digest (GitHub), and always for other F-Droid repos:
 * there it comes from the signed index. [api] is the GitHub API URL of the file: with a token it also downloads from
 * private repositories.
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
    /** The release tag that an install refused or sent to the user: background runs do not download it again. */
    val refusedTag: String? = null,
    /**
     * Why the last install failed. A check removes it, but not while the latest release is the refused one
     * ([refusedTag]): then a new release, an install or a pin reset removes it.
     */
    val installError: String? = null,
) {
    /** The text to show on the entry: a failed install first, then a failed check. */
    val problem: String? get() = installError ?: error
}

data class Settings(
    val background: Boolean = true,
    val intervalHours: Int = 6,
    val autoInstall: Boolean = true,
    /** Automatic installs download only on an unmetered network; checks use any network. A stored choice stays. */
    val unmeteredOnly: Boolean = true,
)

data class Data(val apps: List<TrackedApp> = emptyList(), val settings: Settings = Settings())
