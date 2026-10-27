package app.fonte

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import app.fonte.data.Store
import app.fonte.data.Token
import app.fonte.data.TrackedApp
import app.fonte.data.Vault
import app.fonte.install.Installer
import app.fonte.install.Packages
import app.fonte.net.Http
import app.fonte.source.Ctx
import app.fonte.source.Versions
import app.fonte.work.Notifier
import app.fonte.work.UpdateJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the app list: the tracked app, its installed state, and a running install. */
data class Row(val app: TrackedApp, val installed: Packages.Info?, val task: Installer.Task?) {
    val update: Boolean = installed != null && Versions.hasUpdate(app, installed.versionCode, installed.versionName)
}

/** App-wide objects. Heavy parts (the HTTP engine, the Keystore) start on first use, off the main thread. */
class FonteApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: Store
        private set
    lateinit var token: Token
        private set
    lateinit var packages: Packages
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var installer: Installer
        private set
    lateinit var library: Library
        private set
    lateinit var rows: StateFlow<List<Row>>
        private set

    private val _ready = MutableStateFlow(false)

    /** True after the list is read; the splash screen stays until then. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        store = Store(filesDir, scope)
        token = Token(filesDir, Vault())
        packages = Packages(this)
        notifier = Notifier(this)
        // Built on first use, off the main thread: the engine loads the platform's network stack.
        val http = lazy { Http(this) }
        val ctx = Ctx(http, token::get, Build.SUPPORTED_ABIS.toList())
        installer = Installer(this, http, token::get, store, packages, notifier, scope)
        library = Library(store, packages, ctx)
        // Off the main thread: each check result and each download step builds and sorts the list again.
        rows = combine(store.state, packages.installed, installer.tasks) { data, installed, tasks ->
            data.apps.map { Row(it, it.packageName?.let(installed::get), tasks[it.id]) }
                .sortedWith(compareBy<Row>({ !it.update }, { it.installed == null }, { it.app.name.lowercase() }))
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

        scope.launch(Dispatchers.IO) {
            store.load()
            library.addSelf()
            val tracked = trackedPackages().toSet()
            packages.refresh(tracked)
            _ready.value = true
            // A new or removed app: read the installed packages again. The set that was just read does not count.
            launch {
                store.state.map { d -> d.apps.mapNotNull { it.packageName }.toSet() }.distinctUntilChanged().dropWhile { it == tracked }
                    .collect { packages.refresh(it) }
            }
            notifier.setUp()
            installer.cleanUp()
            UpdateJob.schedule(this@FonteApp, store.settings, replace = false)
        }
        scope.launch {
            store.state.map { it.settings }.distinctUntilChanged().drop(1).collect { UpdateJob.schedule(this@FonteApp, it, replace = true) }
        }
        // The updates notification follows the list: an update that the user installs in the app leaves it.
        // Only changes after the start count: the first list can come before the installed versions.
        scope.launch {
            ready.first { it }
            rows.map { r -> r.filter { it.update && it.task == null }.map { it.app.name } }
                .distinctUntilChanged().drop(1).collect(notifier::refreshUpdates)
        }
        listenToPackages()
    }

    private fun trackedPackages() = store.apps.mapNotNull { it.packageName }

    /** Installs and removals by anyone (the user, another store) change the list at once. */
    private fun listenToPackages() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (pkg in trackedPackages()) scope.launch(Dispatchers.IO) { packages.refresh(pkg) }
            }
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
    }

    /** An activity came to the front: show the installed state as it is now, and check if the last check is old. */
    fun onForeground() {
        installer.visible = true
        scope.launch {
            // After the start: its read of the installed packages is new, so this one is skipped.
            ready.first { it }
            withContext(Dispatchers.IO) { packages.refreshIfOld(trackedPackages()) }
            val now = System.currentTimeMillis()
            val hasToken = token.isSet.value
            val skip = { a: TrackedApp -> Library.skipOnForeground(a, hasToken, now) }
            // Only the apps that this check reads count for the age: a skipped app does not start a check of the others.
            val oldest = store.apps.filterNot(skip).minOfOrNull { it.checkedAt } ?: return@launch
            if (now - oldest > STALE_MS) library.checkAll(skip)
        }
    }

    fun onBackground() {
        installer.visible = false
    }

    /**
     * The periodic job: check, install what may install itself, and notify about the rest. On a metered [network]
     * with [Settings.unmeteredOnly][app.fonte.data.Settings.unmeteredOnly], nothing downloads: a later run installs.
     */
    suspend fun runBackground(network: Network?) {
        store.awaitLoaded()
        library.checkAll()
        val settings = store.settings
        val updates = withContext(Dispatchers.IO) {
            store.apps.filter { app ->
                val info = app.packageName?.let(packages::info) ?: return@filter false
                Versions.hasUpdate(app, info.versionCode, info.versionName)
            }
        }.sortedBy { it.packageName == packageName } // Last: the install of Fonte itself ends this process.
        val install = settings.autoInstall && (!settings.unmeteredOnly || withContext(Dispatchers.IO) { unmetered(network) })
        val left = updates.filter { app ->
            !(install && app.autoUpdate && installer.install(app.id, background = true))
        }
        notifier.updatesAvailable(left.map { it.name })
    }

    private fun unmetered(network: Network?): Boolean =
        getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private companion object {
        const val STALE_MS = 30 * 60_000L
    }
}

val Context.app: FonteApp get() = applicationContext as FonteApp
