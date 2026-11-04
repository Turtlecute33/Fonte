package app.fonte.install

import android.util.JsonReader
import app.fonte.data.SourceKind
import app.fonte.net.Http
import app.fonte.source.FDroid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * The signed index (v2) of an F-Droid repo. entry.jar must carry the repo's signature, and the index must match the
 * digest and size in entry.jar. F-Droid and IzzyOnDroid: only a first install reads it (F-Droid: about 70 MB,
 * compressed on the wire), because nothing is pinned yet; the APK's SHA-256 must be one of the package's files.
 * Other repos: each check reads entry.jar, and the index when its digest changes; the APK digest comes from it.
 * The files stay in the cache until the next app start.
 * Fonte keeps no entry.json timestamp, so an older signed entry.jar also passes: a hostile server can offer an older
 * version that an older index lists, or hide updates.
 */
class RepoIndex(http: Lazy<Http>, private val dir: File) {
    private val http: Http by http
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val entries = ConcurrentHashMap<String, Pair<Long, Index>>()

    class Index(val name: String, val sha256: String, val size: Long)

    /**
     * One APK of a package. [beta]: in a release channel, such as "Beta". [abis]: empty when it has no native code.
     * [minSdk]: 0 when the index does not tell it.
     */
    class Version(
        val name: String,
        val code: Long,
        val file: String,
        val sha256: String,
        val size: Long,
        val added: Long?,
        val abis: List<String>,
        val beta: Boolean,
        val minSdk: Int,
    )

    /** The SHA-256 digests of [pkg]'s APKs in the signed index of the [kind] repo. [progress] follows the index download. */
    suspend fun apkDigests(kind: SourceKind, pkg: String, progress: (done: Long, total: Long) -> Unit): Set<String> {
        val address = FDroid.base(kind) + "/repo"
        val index = entry(address, REPO_CERTS.getValue(kind), kind.label)
        return read(address, index, kind.label, progress) { file -> versions(file, pkg).mapTo(HashSet()) { it.sha256 } }
    }

    /**
     * The index that entry.jar of the repo at [address] names, checked with [cert]. The checks of one repo share one
     * result for 60 s: one download for all its apps (review PERF-3). The key has [cert], so a result that one key
     * verified is never given for another key.
     */
    suspend fun entry(address: String, cert: String, label: String): Index {
        val key = "e$address $cert"
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            entries[key]?.takeIf { System.nanoTime() - it.first < ENTRY_TTL_NS }?.second
                ?: fetchEntry(address, cert, label).also { entries[key] = System.nanoTime() to it }
        }
    }

    private suspend fun fetchEntry(address: String, cert: String, label: String): Index {
        val r = http.get("$address/entry.jar", maxBytes = 1 shl 20)
        if (r.code != 200) throw IOException("$label index: HTTP ${r.code}")
        dir.mkdirs()
        // A file for each call: the checks of one repo run at the same time.
        val jar = File.createTempFile("entry", ".jar", dir)
        return try {
            jar.writeBytes(r.body)
            readEntry(jar, cert)
        } catch (e: SecurityException) {
            throw IOException("Refused: the $label index has a bad signature", e)
        } finally {
            jar.delete()
        }
    }

    /**
     * Gives the file of [index] to [parse], and downloads it first if the cache does not have it. One repo at a time:
     * a new index replaces the old file, so no reader of the old file may run then.
     */
    suspend fun <T> read(address: String, index: Index, label: String, progress: (Long, Long) -> Unit = { _, _ -> }, parse: (File) -> T): T {
        val prefix = "r" + Packages.sha256(address.toByteArray()).take(16)
        return locks.computeIfAbsent(prefix) { Mutex() }.withLock {
            val file = File(dir, "$prefix-${index.sha256}.json")
            if (!file.exists()) {
                dir.listFiles { f -> f.name.startsWith("$prefix-") }?.forEach { it.delete() }
                val part = File(dir, "$prefix.part")
                val d = try {
                    // The length from entry.jar: a compressed answer does not tell the decoded length.
                    http.download(address + index.name, part, index.size) { done, _ -> progress(done, index.size) }
                } catch (e: Exception) {
                    part.delete()
                    throw e
                }
                if (d.size != index.size || d.sha256 != index.sha256) {
                    part.delete()
                    throw IOException("Refused: the $label index does not match its signature")
                }
                if (!part.renameTo(file)) throw IOException("Could not keep the $label index")
            }
            parse(file)
        }
    }

    /** Deletes the indexes that an earlier run kept. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** The SHA-256 of each repo's signing certificate, as the F-Droid client ships them. */
        val REPO_CERTS = mapOf(
            SourceKind.FDroid to "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab",
            SourceKind.Izzy to "3bf0d6abfeae2f401707b6d966be743bf0eee49c2561b9ba39073711f628937a",
        )
        private const val MAX_INDEX = 512L shl 20
        private const val ENTRY_TTL_NS = 60_000_000_000L
        private const val MAX_NAME = 100
        private val NAME = Regex("""^/[A-Za-z0-9._-]+\.json$""")
        private val APK = Regex("""^/[A-Za-z0-9._+~-]+\.apk$""")
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /**
         * Reads entry.json from [jar]. Its signature must verify and come from [cert]. A changed entry.json throws
         * [SecurityException] while it is read.
         */
        fun readEntry(jar: File, cert: String): Index = JarFile(jar, true).use { j ->
            val e = j.getJarEntry("entry.json") ?: throw IOException("No entry.json in entry.jar")
            val bytes = j.getInputStream(e).use { it.readBytes() }
            // Known only after the whole entry is read; null when the entry is not signed.
            // Exactly one signer with a path of one certificate, as the F-Droid client requires: the platform builds
            // a path only from the names in the certificates, so a forged path can carry the repo certificate (review INSTALL-1).
            val signer = e.codeSigners?.singleOrNull()?.signerCertPath?.certificates?.singleOrNull()
                ?: throw SecurityException("entry.jar must have one signer with one certificate")
            if (Packages.sha256(signer.encoded) != cert) throw SecurityException("entry.jar is not signed with the repo key")
            val o = JSONObject(bytes.decodeToString()).getJSONObject("index")
            val index = Index(o.getString("name"), o.getString("sha256").lowercase(), o.getLong("size"))
            if (!NAME.matches(index.name) || index.sha256.length != 64 || index.size !in 1..MAX_INDEX) {
                throw IOException("entry.jar names no valid index")
            }
            index
        }

        /** packages → [pkg] → versions. Stops after [pkg]; skips everything else. A version with a bad file is left out. */
        fun versions(file: File, pkg: String): List<Version> {
            val found = ArrayList<Version>()
            reader(file).use { r ->
                r.fields { top ->
                    if (top != "packages") return@fields r.skipValue()
                    r.fields { name ->
                        if (name != pkg) return@fields r.skipValue()
                        r.fields { part ->
                            if (part != "versions") return@fields r.skipValue()
                            r.fields { _ -> r.version()?.let(found::add) }
                        }
                        return found
                    }
                }
            }
            return found
        }

        /** The package names and names (English if there is one) of all apps in the index. */
        fun apps(file: File): Map<String, String> {
            val found = HashMap<String, String>()
            reader(file).use { r ->
                r.fields { top ->
                    if (top != "packages") return@fields r.skipValue()
                    r.fields { pkg ->
                        var name: String? = null
                        r.fields { part ->
                            if (part != "metadata") return@fields r.skipValue()
                            r.fields { key -> if (key == "name") name = r.localized() else r.skipValue() }
                        }
                        found[pkg] = name?.trim()?.take(MAX_NAME)?.ifEmpty { null } ?: pkg
                    }
                }
            }
            return found
        }

        private fun reader(file: File) = JsonReader(file.bufferedReader(bufferSize = 64 shl 10))

        private fun JsonReader.version(): Version? {
            var name = ""
            var code = 0L
            var path = ""
            var sha256 = ""
            var size = 0L
            var added: Long? = null
            var abis = emptyList<String>()
            var beta = false
            var minSdk = 0
            fields { key ->
                when (key) {
                    "added" -> added = nextLong()
                    "file" -> fields { f ->
                        when (f) {
                            "name" -> path = nextString()
                            "sha256" -> sha256 = nextString().lowercase()
                            "size" -> size = nextLong()
                            else -> skipValue()
                        }
                    }
                    "manifest" -> fields { m ->
                        when (m) {
                            "versionName" -> name = nextString()
                            "versionCode" -> code = nextLong()
                            "nativecode" -> abis = strings()
                            "usesSdk" -> fields { u -> if (u == "minSdkVersion") minSdk = nextInt() else skipValue() }
                            else -> skipValue()
                        }
                    }
                    "releaseChannels" -> beta = strings().isNotEmpty()
                    else -> skipValue()
                }
            }
            return if (code > 0 && APK.matches(path) && SHA256.matches(sha256)) Version(name, code, path, sha256, size, added, abis, beta, minSdk) else null
        }

        /** A localized text: en-US, then en, then the first one. */
        private fun JsonReader.localized(): String? {
            val texts = HashMap<String, String>()
            fields { lang -> texts[lang] = nextString() }
            return texts["en-US"] ?: texts["en"] ?: texts.values.firstOrNull()
        }

        private fun JsonReader.strings(): List<String> {
            val list = ArrayList<String>()
            beginArray()
            while (hasNext()) list += nextString()
            endArray()
            return list
        }

        private inline fun JsonReader.fields(each: (String) -> Unit) {
            beginObject()
            while (hasNext()) each(nextName())
            endObject()
        }
    }
}
