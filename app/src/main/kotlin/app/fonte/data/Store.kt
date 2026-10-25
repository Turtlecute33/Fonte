package app.fonte.data

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app list and settings in one JSON file, `apps.json`, in the app's private storage (file-based encryption).
 * The data is small, so each change writes the whole file, off the main thread.
 */
class Store(dir: File, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(dir, "apps.json"))
    private val writeLock = Mutex()
    private val pending = AtomicBoolean(false)
    private val loaded = CompletableDeferred<Unit>()
    private val _state = MutableStateFlow(Data())
    val state: StateFlow<Data> = _state.asStateFlow()

    val apps: List<TrackedApp> get() = _state.value.apps
    val settings: Settings get() = _state.value.settings

    fun app(id: String): TrackedApp? = apps.firstOrNull { it.id == id }

    /** Waits until [load] is done. Receivers and the background job call this before they change data. */
    suspend fun awaitLoaded() = loaded.await()

    /** Reads the file. Call once, off the main thread. */
    fun load() {
        try {
            val text = file.readFully().decodeToString()
            _state.value = runCatching { decode(JSONObject(text)) }.getOrElse {
                // Keep a damaged file aside, never overwrite it: the user can still send it for repair.
                Log.w(TAG, "apps.json unreadable, kept as $UNREADABLE")
                file.baseFile.renameTo(File(file.baseFile.parentFile, UNREADABLE))
                Data()
            }
        } catch (_: FileNotFoundException) {
        } finally {
            loaded.complete(Unit)
        }
    }

    /** Applies [change] now and writes the new state in the background. Changes close together go into one write. */
    fun update(change: (Data) -> Data) {
        _state.update(change)
        if (pending.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                delay(COALESCE_MS)
                pending.set(false)
                save()
            }
        }
    }

    fun updateApp(id: String, change: (TrackedApp) -> TrackedApp) =
        update { d -> d.copy(apps = d.apps.map { if (it.id == id) change(it) else it }) }

    fun updateSettings(change: (Settings) -> Settings) = update { it.copy(settings = change(it.settings)) }

    private suspend fun save() = writeLock.withLock {
        val bytes = encode(_state.value).toString().toByteArray()
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "apps.json write failed: ${e.javaClass.simpleName}")
        }
    }

    internal companion object {
        const val TAG = "FonteStore"
        /** 2: GitHub assets keep their API URL. */
        const val VERSION = 2
        const val COALESCE_MS = 250L
        const val UNREADABLE = "apps.json.unreadable"

        fun encode(d: Data): JSONObject = JSONObject()
            .put("version", VERSION)
            .put("apps", JSONArray(d.apps.map { it.json() }))
            .put("settings", d.settings.json())

        fun decode(o: JSONObject): Data {
            val apps = o.list("apps") { app() }
            return Data(
                // Version 1 kept no API URL for GitHub assets: without the ETag, the next check reads the releases again.
                apps = if (o.optInt("version", 1) < 2) apps.map { if (it.source.kind == SourceKind.GitHub) it.copy(etag = null) else it } else apps,
                settings = o.optJSONObject("settings")?.settings() ?: Settings(),
            )
        }

        fun <T> JSONObject.list(name: String, item: JSONObject.() -> T): List<T> {
            val a = optJSONArray(name) ?: return emptyList()
            return List(a.length()) { a.getJSONObject(it).item() }
        }

        fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

        private fun JSONObject.long(name: String): Long? = if (isNull(name)) null else optLong(name)

        private fun TrackedApp.json() = JSONObject()
            .put("kind", source.kind.name).put("host", source.host).put("path", source.path)
            .put("name", name).put("package", packageName).put("prerelease", prerelease).put("filter", apkFilter)
            .put("autoUpdate", autoUpdate).put("cert", cert).put("installedTag", installedTag).put("installedCode", installedCode)
            .put("latest", latest?.json()).put("etag", etag).put("checkedAt", checkedAt).put("error", error).put("addedAt", addedAt)

        private fun JSONObject.app(): TrackedApp {
            val source = Source(SourceKind.valueOf(getString("kind")), getString("host"), getString("path"))
            return TrackedApp(
                id = source.id,
                source = source,
                name = getString("name"),
                packageName = str("package"),
                prerelease = optBoolean("prerelease"),
                apkFilter = optString("filter"),
                autoUpdate = optBoolean("autoUpdate", true),
                cert = str("cert"),
                installedTag = str("installedTag"),
                installedCode = long("installedCode"),
                latest = optJSONObject("latest")?.release(),
                etag = str("etag"),
                checkedAt = optLong("checkedAt"),
                error = str("error"),
                addedAt = optLong("addedAt"),
            )
        }

        private fun Release.json() = JSONObject().put("tag", tag).put("version", version).put("code", code).put("date", date)
            .put("notes", notes).put("page", page)
            .put("assets", JSONArray(assets.map { JSONObject().put("name", it.name).put("url", it.url).put("size", it.size).put("sha256", it.sha256).put("api", it.api) }))

        private fun JSONObject.release() = Release(
            tag = getString("tag"),
            version = getString("version"),
            code = long("code"),
            date = long("date"),
            notes = optString("notes"),
            page = str("page"),
            assets = list("assets") { Asset(getString("name"), getString("url"), optLong("size"), str("sha256"), str("api")) },
        )

        private fun Settings.json() = JSONObject().put("background", background).put("intervalHours", intervalHours)
            .put("autoInstall", autoInstall).put("unmeteredOnly", unmeteredOnly)

        private fun JSONObject.settings(): Settings {
            val d = Settings()
            return Settings(
                background = optBoolean("background", d.background),
                intervalHours = optInt("intervalHours", d.intervalHours),
                autoInstall = optBoolean("autoInstall", d.autoInstall),
                unmeteredOnly = optBoolean("unmeteredOnly", d.unmeteredOnly),
            )
        }
    }
}
