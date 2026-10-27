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
import java.util.jar.JarFile

/**
 * The signed index (v2) of F-Droid and IzzyOnDroid. A first install from these repos has no pinned certificate yet,
 * so the APK's SHA-256 must be one of the package's files in the index. entry.jar must carry the repo's signature
 * (the key that the F-Droid client ships), and the index must match the digest and size in entry.jar.
 * The index is large (F-Droid: about 70 MB, compressed on the wire), so only first installs read it. The file stays
 * in the cache until the next app start, for more first installs.
 * Fonte keeps no entry.json timestamp, so an older signed entry.jar also passes: a hostile server can offer an older
 * version that an older index lists.
 */
class RepoIndex(http: Lazy<Http>, private val dir: File) {
    private val http: Http by http
    private val lock = Mutex()

    class Index(val name: String, val sha256: String, val size: Long)

    /** The SHA-256 digests of [pkg]'s APKs in the signed index of the [kind] repo. */
    suspend fun apkDigests(kind: SourceKind, pkg: String): Set<String> = lock.withLock {
        val repo = FDroid.base(kind) + "/repo"
        dir.mkdirs()
        val r = http.get("$repo/entry.jar", maxBytes = 1 shl 20)
        if (r.code != 200) throw IOException("${kind.label} index: HTTP ${r.code}")
        val jar = File(dir, "${kind.name}-entry.jar")
        val index = try {
            jar.writeBytes(r.body)
            readEntry(jar, REPO_CERTS.getValue(kind))
        } catch (e: SecurityException) {
            throw IOException("Refused: the ${kind.label} index has a bad signature", e)
        } finally {
            jar.delete()
        }
        val file = File(dir, "${kind.name}-${index.sha256}.json")
        if (!file.exists()) {
            dir.listFiles { f -> f.name.startsWith("${kind.name}-") }?.forEach { it.delete() }
            val part = File(dir, "${kind.name}.part")
            val d = try {
                http.download(repo + index.name, part, index.size) { _, _ -> }
            } catch (e: Exception) {
                part.delete()
                throw e
            }
            if (d.size != index.size || d.sha256 != index.sha256) {
                part.delete()
                throw IOException("Refused: the ${kind.label} index does not match its signature")
            }
            if (!part.renameTo(file)) throw IOException("Could not keep the ${kind.label} index")
        }
        digests(file, pkg)
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
        private val NAME = Regex("""^/[A-Za-z0-9._-]+\.json$""")

        /**
         * Reads entry.json from [jar]. Its signature must verify and come from [cert]. A changed entry.json throws
         * [SecurityException] while it is read.
         */
        fun readEntry(jar: File, cert: String): Index = JarFile(jar, true).use { j ->
            val e = j.getJarEntry("entry.json") ?: throw IOException("No entry.json in entry.jar")
            val bytes = j.getInputStream(e).use { it.readBytes() }
            // Known only after the whole entry is read; null when the entry is not signed.
            val signers = e.certificates?.map { Packages.sha256(it.encoded) }.orEmpty()
            if (cert !in signers) throw SecurityException("entry.jar is not signed with the repo key")
            val o = JSONObject(bytes.decodeToString()).getJSONObject("index")
            val index = Index(o.getString("name"), o.getString("sha256").lowercase(), o.getLong("size"))
            if (!NAME.matches(index.name) || index.sha256.length != 64 || index.size !in 1..MAX_INDEX) {
                throw IOException("entry.jar names no valid index")
            }
            index
        }

        /** packages → [pkg] → versions → each → file → sha256. Stops after [pkg]; skips everything else. */
        private fun digests(file: File, pkg: String): Set<String> {
            val found = HashSet<String>()
            JsonReader(file.bufferedReader(bufferSize = 64 shl 10)).use { r ->
                r.fields { top ->
                    if (top != "packages") return@fields r.skipValue()
                    r.fields { name ->
                        if (name != pkg) return@fields r.skipValue()
                        r.fields { part ->
                            if (part != "versions") return@fields r.skipValue()
                            r.fields { _ ->
                                r.fields { key ->
                                    if (key != "file") return@fields r.skipValue()
                                    r.fields { f -> if (f == "sha256") found += r.nextString().lowercase() else r.skipValue() }
                                }
                            }
                        }
                        return found
                    }
                }
            }
            return found
        }

        private inline fun JsonReader.fields(each: (String) -> Unit) {
            beginObject()
            while (hasNext()) each(nextName())
            endObject()
        }
    }
}
