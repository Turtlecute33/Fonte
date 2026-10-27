package app.fonte.install

import app.fonte.data.TrackedApp

/** What Fonte reads from a downloaded APK. [current] and [lineage] are certificate digests ([Packages.current]). */
class Archive(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val current: Set<String>,
    val lineage: Set<String>,
    val sha256: String,
)

/**
 * The install gates of [Installer], in order: package name, pinned certificate, signed repo index (first install
 * from F-Droid or IzzyOnDroid), version code. No Android calls, so that tests can run each gate.
 */
object Gate {
    sealed interface Decision {
        /** Install, and pin [pin]: the certificate of the APK, so that the pin follows a key rotation. */
        data class Install(val pin: String) : Decision

        /** The installed version is the same or newer: install nothing, and remember the release with [pin]. */
        data class UpToDate(val pin: String) : Decision

        /** Do not install; [reason] is the user text. */
        data class Refuse(val reason: String) : Decision
    }

    /**
     * Decides for [archive] of [app]. [installed] is the installed package of the APK's name, or null.
     * [indexDigests] downloads the signed repo index and runs only when nothing is pinned and the source has one.
     */
    suspend fun decide(
        app: TrackedApp,
        archive: Archive,
        installed: Packages.Info?,
        indexDigests: suspend () -> Set<String>,
    ): Decision {
        val pkg = archive.packageName
        if (app.packageName != null && app.packageName != pkg) return Decision.Refuse("The APK is $pkg, not ${app.packageName}")
        val pin = app.cert ?: installed?.cert
        if (pin != null && !Packages.matches(pin, archive.current, archive.lineage)) {
            return Decision.Refuse("Refused: the APK is signed with a different key than the pinned one")
        }
        // Nothing pinned: a first install. F-Droid and IzzyOnDroid sign their index, so it vouches for the file.
        val kind = app.source.kind
        if (pin == null && kind in RepoIndex.REPO_CERTS && archive.sha256 !in indexDigests()) {
            return Decision.Refuse("Refused: the APK is not in the signed ${kind.label} index")
        }
        // Same version (or older): nothing to install.
        if (installed != null && archive.versionCode <= installed.versionCode) {
            return Decision.UpToDate(pin ?: Packages.pin(archive.current))
        }
        return Decision.Install(Packages.pin(archive.current))
    }
}
