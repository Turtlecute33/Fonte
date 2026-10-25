package app.fonte

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        rows = combine(store.state, packages.installed, installer.tasks) { data, installed, tasks ->
            data.apps.map { Row(it, it.packageName?.let(installed::get), tasks[it.id]) }
                .sortedWith(compareBy<Row>({ !it.update }, { it.installed == null }, { it.app.name.lowercase() }))
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        scope.launch(Dispatchers.IO) {
            store.load()
            packages.refresh(trackedPackages())
            _ready.value = true
            notifier.setUp()
            installer.cleanUp()
            UpdateJob.schedule(this@FonteApp, store.settings, replace = false)
        }
        // A new or removed app: read the installed packages again.
        scope.launch {
            store.state.map { d -> d.apps.mapNotNull { it.packageName }.toSet() }.distinctUntilChanged().drop(1).collect {
                withContext(Dispatchers.IO) { packages.refresh(it) }
            }
        }
        scope.launch {
            store.state.map { it.settings }.distinctUntilChanged().drop(1).collect { UpdateJob.schedule(this@FonteApp, it, replace = true) }
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
            store.awaitLoaded()
            withContext(Dispatchers.IO) { packages.refresh(trackedPackages()) }
            val oldest = store.apps.minOfOrNull { it.checkedAt } ?: return@launch
            if (System.currentTimeMillis() - oldest > STALE_MS) library.checkAll()
        }
    }

    fun onBackground() {
        installer.visible = false
    }

    /** The periodic job: check, install what may install itself, and notify about the rest. */
    suspend fun runBackground() {
        store.awaitLoaded()
        library.checkAll()
        val settings = store.settings
        val updates = withContext(Dispatchers.IO) {
            store.apps.filter { app ->
                val info = app.packageName?.let(packages::info) ?: return@filter false
                Versions.hasUpdate(app, info.versionCode, info.versionName)
            }
        }
        val left = updates.filter { app ->
            !(settings.autoInstall && app.autoUpdate && installer.install(app.id, background = true))
        }
        notifier.updatesAvailable(left.map { it.name })
    }

    private companion object {
        const val STALE_MS = 30 * 60_000L
    }
}

val Context.app: FonteApp get() = applicationContext as FonteApp
