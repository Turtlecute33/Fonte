package app.fonte

import android.util.Log
import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.Store
import app.fonte.data.TrackedApp
import app.fonte.install.Packages
import app.fonte.source.Check
import app.fonte.source.Ctx
import app.fonte.source.Links
import app.fonte.source.Sources
import app.fonte.transfer.Transfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

/** The tracked apps: add, check, change, remove, import and export. */
class Library(private val store: Store, private val packages: Packages, private val ctx: Ctx) {
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private val checkLock = Mutex()

    /**
     * Checks every app that [skip] does not leave out, [PARALLEL] at a time (one connection per host carries them).
     * Returns at once if a check runs.
     */
    suspend fun checkAll(skip: (TrackedApp) -> Boolean = { false }) {
        if (!checkLock.tryLock()) return
        _checking.value = true
        try {
            withContext(Dispatchers.IO) {
                val limit = Semaphore(PARALLEL)
                coroutineScope { store.apps.filterNot(skip).map { app -> async { limit.withPermit { check(app.id) } } }.awaitAll() }
            }
        } finally {
            _checking.value = false
            checkLock.unlock()
        }
    }

    suspend fun check(id: String) = withContext(Dispatchers.IO) {
        val app = store.app(id) ?: return@withContext
        val now = System.currentTimeMillis()
        try {
            val c = Sources.check(app, ctx)
            Log.d(TAG, "Check ${app.id}: ${if (c is Check.Found) c.release?.tag ?: "no APK" else "not modified"}")
            when (c) {
                Check.Same -> store.updateApp(id) { it.copy(checkedAt = now, error = null) }
                is Check.Found -> store.updateApp(id) {
                    it.copy(
                        latest = c.release,
                        etag = c.etag.takeIf { c.release != null },
                        checkedAt = now,
                        error = if (c.release == null) NO_APK else null,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Check ${app.id} failed", e)
            store.updateApp(id) { it.copy(checkedAt = now, error = userText(e, "Check failed")) }
        }
    }

    /** Resolves [link], reads its latest release, and adds the app. An app that is installed already is adopted. */
    suspend fun add(link: String, prerelease: Boolean, filter: String): TrackedApp = withContext(Dispatchers.IO) {
        val source = Sources.resolve(link, ctx)
        store.app(source.id)?.let { throw IOException("${it.name} is in the list already") }
        val draft = newApp(source, null, null, prerelease, filter, true, null)
        val found = Sources.check(draft, ctx) as? Check.Found
        val release = found?.release ?: throw IOException(NO_APK)
        val app = draft.copy(latest = release, etag = found.etag, checkedAt = System.currentTimeMillis())
        store.update { d -> if (d.apps.any { it.id == app.id }) d else d.copy(apps = d.apps + app) }
        app
    }

    data class Imported(val added: Int, val known: Int, val skipped: List<String>)

    /**
     * Adds the apps of an Obtainium or Fonte export. The callers start [checkAll] at once: each new host gets a request
     * then and at each interval (README Privacy). A source kind in the file makes any host a GitLab or Forgejo source.
     */
    suspend fun import(text: String): Imported = withContext(Dispatchers.IO) {
        val entries = try {
            Transfer.parse(text)
        } catch (e: Exception) {
            throw IOException("Not an Obtainium or Fonte export", e)
        }
        val added = ArrayList<TrackedApp>()
        val skipped = ArrayList<String>()
        var known = 0
        for (e in entries) {
            // No probe: only a link whose host or import hint names a supported kind gives a source.
            val source = e.source ?: (Links.parse(e.url, e.hint) as? Links.Parsed.Known)?.source.takeIf { !e.unsupported }
            if (source == null) {
                skipped += e.name ?: e.url
                continue
            }
            val all = store.apps + added
            if (all.any { it.id == source.id || e.packageName != null && it.packageName == e.packageName }) {
                known++
                continue
            }
            added += newApp(source, e.packageName, e.name, e.prerelease, e.filter, e.autoUpdate, e.cert)
        }
        store.update { d -> d.copy(apps = d.apps + added.filter { a -> d.apps.none { it.id == a.id } }) }
        Imported(added.size, known, skipped)
    }

    fun export(): String = Transfer.export(store.apps)

    /** Fonte is always in its own list, so that it updates itself like the other apps. Adds it when it is not there. */
    fun addSelf() {
        if (store.apps.any { it.id == SELF.id || it.packageName == SELF_PACKAGE }) return
        val app = newApp(SELF, SELF_PACKAGE, null, false, "", true, null)
        store.update { d -> if (d.apps.any { it.id == app.id }) d else d.copy(apps = d.apps + app) }
    }

    /**
     * A new entry. An installed package is adopted: its label, and its signing certificate as the pin. The installed
     * certificate wins over a pin from an import file. A pin with no package name is not kept.
     */
    private fun newApp(
        source: Source,
        pkg: String?,
        name: String?,
        prerelease: Boolean,
        filter: String,
        autoUpdate: Boolean,
        cert: String?,
    ): TrackedApp {
        val packageName = pkg ?: source.path.takeIf { source.kind == SourceKind.FDroid || source.kind == SourceKind.Izzy }
        val installed = packageName?.let(packages::info)
        return TrackedApp(
            id = source.id,
            source = source,
            name = installed?.let { packages.label(it.pkg) } ?: name ?: defaultName(source),
            packageName = packageName,
            prerelease = prerelease,
            apkFilter = filter.trim(),
            autoUpdate = autoUpdate,
            cert = installed?.cert ?: cert?.takeIf { packageName != null },
            addedAt = System.currentTimeMillis(),
        )
    }

    /** Changes the options; a change of release choice drops the ETag, so the next check reads the list again. */
    suspend fun setOptions(id: String, prerelease: Boolean, filter: String, autoUpdate: Boolean) {
        val before = store.app(id) ?: return
        val reselect = before.prerelease != prerelease || before.apkFilter != filter.trim()
        store.updateApp(id) { it.copy(prerelease = prerelease, apkFilter = filter.trim(), autoUpdate = autoUpdate, etag = if (reselect) null else it.etag) }
        if (reselect) check(id)
    }

    fun remove(id: String) = store.update { d -> d.copy(apps = d.apps.filter { it.id != id }) }

    /** Forgets the pinned certificate: the next install pins the certificate of the APK it installs. */
    fun resetPin(id: String) = store.updateApp(id) { it.copy(cert = null) }

    companion object {
        private const val TAG = "FonteLibrary"
        private const val PARALLEL = 8
        const val NO_APK = "No APK for this device in the recent releases"
        private const val GITHUB_FRESH_MS = 3600_000L
        private const val MAX_TEXT = 200

        /**
         * With no token, the automatic check when Fonte comes to the front leaves out GitHub apps checked in the last
         * hour: then each GitHub request, also a 304, uses 1 of 60 requests an hour. Pull-to-refresh and the
         * background job check all apps.
         */
        fun skipOnForeground(app: TrackedApp, hasToken: Boolean, now: Long): Boolean =
            !hasToken && app.source.kind == SourceKind.GitHub && now - app.checkedAt < GITHUB_FRESH_MS

        /** Where Fonte publishes its releases, and the package of the release build. */
        val SELF = Source(SourceKind.GitHub, "github.com", "Turtlecute33/Fonte")
        const val SELF_PACKAGE = "app.fonte"

        fun isSelf(app: TrackedApp) = app.id == SELF.id

        /**
         * User text for an error: Fonte's own message (at most [MAX_TEXT] characters), one fixed text for parser errors
         * (org.json puts the whole input in its message), or [fallback].
         */
        fun userText(e: Throwable, fallback: String): String =
            if (e is JSONException) "The source sent an answer that Fonte cannot read"
            else e.message?.takeIf { it.isNotBlank() }?.take(MAX_TEXT) ?: fallback

        /** Repository name, or for F-Droid packages the last part of the package name ("org.schabi.newpipe" → "Newpipe"). */
        fun defaultName(source: Source): String =
            if (source.kind == SourceKind.FDroid || source.kind == SourceKind.Izzy) {
                source.path.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            } else {
                source.defaultName
            }
    }
}
