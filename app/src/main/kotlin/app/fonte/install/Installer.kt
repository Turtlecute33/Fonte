package app.fonte.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.fonte.data.Asset
import app.fonte.data.Release
import app.fonte.data.Store
import app.fonte.net.Http
import app.fonte.work.DownloadJob
import app.fonte.work.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.channels.Channels
import java.util.zip.ZipFile

/**
 * Download, checks, install. Before a session opens, the APK must:
 * - match the SHA-256 digest that the source publishes (GitHub);
 * - be a valid signed APK for this device's ABIs;
 * - have the package name the app had before;
 * - be signed with the pinned certificate (pinned at the first install or adoption; key rotation is followed);
 * - at a first install from F-Droid or IzzyOnDroid (nothing pinned yet), be in the repo's signed index.
 *
 * Sessions ask for no user action and for update ownership: later updates of apps Fonte installed need no prompt,
 * and other installers need the user's consent to update them. In the background the commit waits until the app
 * is not in use (Android 14 install constraints). The result arrives in [InstallReceiver].
 */
class Installer(
    private val context: Context,
    http: Lazy<Http>,
    /** The GitHub token, read off the main thread: with it, GitHub APKs download through the API (private repositories). */
    private val githubToken: () -> String?,
    private val store: Store,
    private val packages: Packages,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) {
    sealed interface Task {
        data class Downloading(val done: Long, val total: Long) : Task
        data object Installing : Task

        /** Background install: committed, waits until the app is not in use. */
        data object Waiting : Task
    }

    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    val tasks: StateFlow<Map<String, Task>> = _tasks.asStateFlow()

    private val _confirmations = Channel<Intent>(Channel.UNLIMITED)

    /** System install prompts for the visible activity to start. */
    val confirmations: Flow<Intent> = _confirmations.receiveAsFlow()

    /** Short messages for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** True while an activity of the app is started: prompts open at once instead of as notifications. */
    @Volatile var visible = false

    private val http: Http by http
    private val downloads = Semaphore(MAX_PARALLEL)
    private val installer get() = context.packageManager.packageInstaller
    private val dir get() = File(context.cacheDir, "apk").apply { mkdirs() }
    private val index = RepoIndex(http, File(context.cacheDir, "index"))

    /**
     * Installs from the UI, in a [DownloadJob] when possible: then the download goes on when the user leaves Fonte.
     * A second tap while a task runs does nothing.
     */
    fun start(appId: String) {
        if (appId in _tasks.value) return
        val size = store.app(appId)?.latest?.assets?.firstOrNull()?.size ?: 0
        if (!DownloadJob.schedule(context, appId, size)) scope.launch { install(appId, background = false) }
    }

    /**
     * Downloads, checks and commits the latest release of [appId]. True when the install was committed or nothing
     * was needed; false on an error (stored in the app's error field).
     */
    suspend fun install(appId: String, background: Boolean): Boolean {
        synchronized(_tasks) {
            if (appId in _tasks.value) return true
            _tasks.update { it + (appId to Task.Downloading(0, 0)) }
        }
        val file = File(dir, "${Packages.sha256(appId.toByteArray()).take(16)}.apk")
        var committed = false
        return try {
            withContext(Dispatchers.IO) { committed = run(appId, file, background) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Install failed: ${e.javaClass.simpleName}")
            val text = e.message ?: "Install failed"
            store.updateApp(appId) { it.copy(error = text) }
            if (!background) messages.tryEmit("${store.app(appId)?.name ?: "App"}: $text")
            false
        } finally {
            file.delete()
            if (!committed) clear(appId)
        }
    }

    /** True when a session was committed; its result clears the task. */
    private suspend fun run(appId: String, file: File, background: Boolean): Boolean {
        val app = store.app(appId) ?: return false
        val release = app.latest ?: throw IOException("No release to install")
        val archive = fetch(appId, release, file)

        val pkg = archive.packageName
        if (app.packageName != null && app.packageName != pkg) throw IOException("The APK is $pkg, not ${app.packageName}")
        val installed = packages.info(pkg)
        val pin = app.cert ?: installed?.cert
        if (pin != null && !Packages.matches(pin, archive.current, archive.lineage)) {
            throw IOException("Refused: the APK is signed with a different key than the pinned one")
        }
        // Nothing pinned: a first install. F-Droid and IzzyOnDroid sign their index, so it vouches for the file.
        val kind = app.source.kind
        if (pin == null && kind in RepoIndex.REPO_CERTS && archive.sha256 !in index.apkDigests(kind, pkg)) {
            throw IOException("Refused: the APK is not in the signed ${kind.label} index")
        }
        if (installed != null && archive.versionCode <= installed.versionCode) {
            // Same version (or older): nothing to install. Remember the release, so that it does not count as an update again.
            val label = packages.label(pkg)
            record(appId, pkg, release.tag, installed.versionCode, pin ?: Packages.pin(archive.current), label)
            if (!background) messages.tryEmit("${label ?: app.name} is up to date")
            return false
        }
        // The pin follows a key rotation: from now on the new certificate is the one to match.
        val newPin = Packages.pin(archive.current)
        // Only the installer of record or the update owner may wait until the app is not in use. Other apps need
        // the user anyway: their prompt goes out as a notification at once.
        val own = context.packageName
        val waits = background && installed != null && (installed.installer == own || installed.updateOwner == own)
        // Installing Fonte itself ends this process: the other downloads and installs go first.
        if (pkg == own) _tasks.first { t -> t.none { (id, task) -> id != appId && task !is Task.Waiting } }
        // Before the commit: its result may arrive at once and clear the task.
        _tasks.update { it + (appId to if (waits) Task.Waiting else Task.Installing) }
        val sessionId = commit(file, archive, release.tag, appId, newPin, background, waits)
        Log.i(TAG, "Committed session $sessionId for $pkg ${archive.versionName}")
        return true
    }

    /** Downloads the first candidate APK that fits this device and reads it. */
    private suspend fun fetch(appId: String, release: Release, file: File): Archive {
        val candidates = if (release.assets.size > 1 && release.code != null) byAbi(release.assets) else release.assets.take(1)
        var lastError: IOException? = null
        for (asset in candidates) {
            // The API URL needs the token; it works for private repositories, the browser URL does not.
            val api = asset.api?.takeIf { runCatching { URI(it).host }.getOrNull() == GITHUB_API }
            val auth = api?.let { a -> githubToken()?.let { a to it } }
            val url = auth?.first ?: asset.url
            val headers = auth?.let { mapOf("Accept" to "application/octet-stream", Http.AUTHORIZATION to "Bearer ${it.second}") }.orEmpty()
            val d = downloads.withPermit {
                _tasks.update { it + (appId to Task.Downloading(0, asset.size)) }
                var shown = -1L
                http.download(url, file, MAX_APK, headers) { done, total ->
                    val size = if (total > 0) total else asset.size
                    // One update per percent (or per MB without a size): the list redraws no more often than that.
                    val step = if (size > 0) done * 100 / size else done shr 20
                    if (step != shown) {
                        shown = step
                        _tasks.update { if (appId in it) it + (appId to Task.Downloading(done, size)) else it }
                    }
                }
            }
            if (asset.sha256 != null && !asset.sha256.equals(d.sha256, ignoreCase = true)) {
                throw IOException("Refused: the download does not match the published SHA-256")
            }
            _tasks.update { it + (appId to Task.Installing) }
            val archive = read(file, d.sha256) ?: throw IOException("Not a valid signed APK")
            if (fits(file)) return archive
            lastError = IOException("No build for this device's processor (${Build.SUPPORTED_ABIS.first()})")
        }
        throw lastError ?: IOException("No APK to install")
    }

    /**
     * F-Droid lists one version code per ABI build. Reads the end of each file (a few hundred KB) to see which ABIs it
     * carries, and orders the builds: this device's main ABI, then no native code, then other ABIs it runs.
     */
    private suspend fun byAbi(assets: List<Asset>): List<Asset> = coroutineScope {
        val abis = Build.SUPPORTED_ABIS.toList()
        val probed = assets.map { a ->
            async {
                val found = runCatching {
                    val r = http.get(a.url, mapOf("Range" to "bytes=-$TAIL"), maxBytes = TAIL + 1024)
                    if (r.code == 206) ZipAbis.fromTail(r.body, ZipAbis.totalFromContentRange(r.header("Content-Range")) ?: -1) else null
                }.getOrNull()
                a to found
            }
        }.awaitAll()
        probed.mapNotNull { (a, found) ->
            val rank = when {
                found == null -> 3
                found.isEmpty() -> 1
                abis.first() in found -> 0
                found.any { it in abis } -> 2
                else -> return@mapNotNull null
            }
            a to rank
        }.sortedBy { it.second }.map { it.first }.ifEmpty { assets }
    }

    private class Archive(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val current: Set<String>,
        val lineage: Set<String>,
        val sha256: String,
    )

    private fun read(file: File, sha256: String): Archive? {
        val pi = context.packageManager.getPackageArchiveInfo(
            file.path,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        ) ?: return null
        val si = pi.signingInfo ?: return null
        val current = Packages.current(si)
        if (current.isEmpty()) return null
        return Archive(pi.packageName, pi.longVersionCode, pi.versionName.orEmpty(), current, Packages.lineage(si), sha256)
    }

    /** True when the APK has no native code, or native code for one of this device's ABIs. */
    private fun fits(file: File): Boolean = ZipFile(file).use { zip ->
        val abis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }
            .mapNotNull { it.split('/').getOrNull(1) }.toSet()
        abis.isEmpty() || abis.any { it in Build.SUPPORTED_ABIS }
    }

    private fun commit(file: File, archive: Archive, tag: String, appId: String, pin: String, background: Boolean, waits: Boolean): Int {
        val pi = installer
        // An older session for this app (a background install that still waits) gives way to this one.
        pi.mySessions.filter { it.appPackageName == archive.packageName }.forEach { runCatching { pi.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(archive.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            setRequestUpdateOwnership(true)
        }
        val id = pi.createSession(params)
        try {
            val sender = statusSender(id, appId, tag, archive.versionCode, pin, background)
            pi.openSession(id).use { session ->
                val size = file.length()
                FileInputStream(file).channel.use { input ->
                    session.openWrite("base.apk", 0, size).use { out ->
                        // A kernel copy (sendfile) when the session hands out a file descriptor.
                        val target = (out as? FileOutputStream)?.channel ?: Channels.newChannel(out)
                        var done = 0L
                        while (done < size) done += input.transferTo(done, size - done, target)
                        session.fsync(out)
                    }
                }
                if (!waits) session.commit(sender)
            }
            if (waits) {
                val constraints = PackageInstaller.InstallConstraints.Builder()
                    .setAppNotForegroundRequired()
                    .setAppNotInteractingRequired()
                    .build()
                pi.commitSessionAfterInstallConstraintsAreMet(id, sender, constraints, WAIT_MS)
            }
        } catch (e: Exception) {
            runCatching { pi.abandonSession(id) }
            throw e
        }
        return id
    }

    private fun statusSender(sessionId: Int, appId: String, tag: String, code: Long, pin: String, background: Boolean): IntentSender {
        val intent = Intent(context, InstallReceiver::class.java)
            .putExtra(EXTRA_APP, appId)
            .putExtra(EXTRA_TAG, tag)
            .putExtra(EXTRA_CODE, code)
            .putExtra(EXTRA_CERT, pin)
            .putExtra(EXTRA_BACKGROUND, background)
        // Mutable: the system adds the status extras. Explicit, so no other app can receive it.
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
    }

    /** A session result from [InstallReceiver]. */
    suspend fun onStatus(intent: Intent) {
        val appId = intent.getStringExtra(EXTRA_APP) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val background = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        store.awaitLoaded()
        val name = store.app(appId)?.name ?: "App"
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                if (!isSystemActivity(confirm)) return
                if (visible) {
                    _tasks.update { it + (appId to Task.Installing) }
                    _confirmations.trySend(confirm)
                } else {
                    // The session waits for the user's tap on the notification; the list shows the update meanwhile.
                    clear(appId)
                    notifier.confirm(sessionId, name, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                notifier.cancelConfirm(sessionId)
                val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val info = pkg?.let { withContext(Dispatchers.IO) { packages.refresh(it) } }
                val label = pkg?.let { withContext(Dispatchers.IO) { packages.label(it) } }
                record(
                    appId,
                    pkg,
                    intent.getStringExtra(EXTRA_TAG),
                    intent.getLongExtra(EXTRA_CODE, 0).takeIf { it > 0 } ?: info?.versionCode,
                    intent.getStringExtra(EXTRA_CERT),
                    label,
                )
                clear(appId)
                if (background) notifier.installed(name, info?.versionName)
            }
            else -> {
                notifier.cancelConfirm(sessionId)
                clear(appId)
                // The user said no, or a background install waited too long: not an error.
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED || status == PackageInstaller.STATUS_FAILURE_TIMEOUT) return
                val text = failure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                store.updateApp(appId) { it.copy(error = text) }
                if (visible) messages.tryEmit("$name: $text")
            }
        }
    }

    private fun record(appId: String, pkg: String?, tag: String?, code: Long?, cert: String?, label: String?) =
        store.updateApp(appId) {
            it.copy(
                packageName = pkg ?: it.packageName,
                installedTag = tag ?: it.installedTag,
                installedCode = code ?: it.installedCode,
                cert = cert ?: it.cert,
                name = label ?: it.name,
                error = null,
            )
        }

    private fun clear(appId: String) = _tasks.update { it - appId }

    /** The install prompt must be a system activity: the intent comes from the system, but check before starting it. */
    private fun isSystemActivity(intent: Intent): Boolean {
        val info = context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)) ?: return false
        return info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    /** Removes APKs that a killed process left behind, and the indexes of the last run. */
    fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        index.clear()
    }

    private fun failure(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked by the system"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with the installed app (other signing key?)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not compatible with this device"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid APK"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
        else -> message?.takeIf { it.isNotBlank() } ?: "Install failed"
    }

    companion object {
        private const val TAG = "FonteInstaller"
        private const val MAX_PARALLEL = 3
        private const val MAX_APK = 2L shl 30
        private const val TAIL = 256 shl 10
        private const val WAIT_MS = 24 * 3600_000L
        private const val GITHUB_API = "api.github.com"
        const val EXTRA_APP = "app.fonte.extra.APP"
        const val EXTRA_TAG = "app.fonte.extra.TAG"
        const val EXTRA_CODE = "app.fonte.extra.CODE"
        const val EXTRA_CERT = "app.fonte.extra.CERT"
        const val EXTRA_BACKGROUND = "app.fonte.extra.BACKGROUND"
    }
}
