package app.fonte

import app.fonte.data.Data
import app.fonte.data.Release
import app.fonte.data.Asset
import app.fonte.data.Settings
import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.Store
import app.fonte.data.TrackedApp
import app.fonte.install.Packages
import app.fonte.install.ZipAbis
import app.fonte.transfer.Transfer
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferTest {
    @Test fun obtainiumSchemaExport() {
        val json = """{"schemaVersion":2,"apps":[
          {"id":"com.aurora.store","url":"https://gitlab.com/AuroraOSS/AuroraStore","author":"A","name":"Aurora Store","additionalSettings":"{\"includePrereleases\":true,\"apkFilterRegEx\":\"hw\",\"invertAPKFilter\":true}","overrideSource":null},
          {"id":"4f2a9b1c3d5e","url":"https://git.example.org/t/app","author":"t","name":"App","additionalSettings":{"includePrereleases":false},"overrideSource":"Codeberg"}
        ],"settings":{}}"""
        val e = Transfer.parse(json)
        assertEquals(2, e.size)
        assertEquals("com.aurora.store", e[0].packageName)
        assertTrue(e[0].prerelease)
        assertEquals("!hw", e[0].filter)
        assertNull(e[1].packageName)
        assertEquals(SourceKind.Forgejo, e[1].hint)
        assertFalse(e[1].unsupported)
        assertTrue(Transfer.parse("""[{"url":"https://www.apkmirror.com/apk/x/y/","overrideSource":"APKMirror"}]""").single().unsupported)
    }

    @Test fun obtainiumLegacyArray() {
        val e = Transfer.parse("""[{"id":"a.b","url":"https://github.com/o/r","name":"R","additionalSettings":"{}"}]""")
        assertEquals("https://github.com/o/r", e.single().url)
    }

    private val source = Source(SourceKind.GitHub, "github.com", "o/r")
    private val tracked = TrackedApp(
        id = source.id, source = source, name = "R", packageName = "a.b", prerelease = true, apkFilter = "x", autoUpdate = false,
        cert = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", installedTag = "v1", installedCode = 3,
        latest = Release("v2", "2", null, 1L, "notes", "https://p", listOf(Asset("a.apk", "https://x/a.apk", 9, "ff", "https://api.github.com/x/1"))),
        etag = "W/\"e\"", checkedAt = 5, error = "boom", addedAt = 7,
    )

    @Test fun fonteExportRoundTrip() {
        val e = Transfer.parse(Transfer.export(listOf(tracked))).single()
        assertEquals(source, e.source)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", e.cert)
        assertFalse(e.autoUpdate)
        assertEquals("x", e.filter)
    }

    private val a = "a".repeat(64)

    @Test fun fonteImportChecksSourcesAndPins() {
        fun entry(kind: String, host: String, path: String, cert: String = "") =
            """{"kind":"$kind","host":"$host","path":"$path","url":"","cert":"$cert"}"""
        val e = Transfer.parse(
            """{"fonte":1,"apps":[${listOf(
                entry("GitHub", "github.com", "o/r", a.uppercase()),
                entry("GitLab", "git.example.org", "team/app", ""),
                entry("GitHub", "evil.example", "o/r"),
                entry("Forgejo", "github.com@evil.example", "o/r"),
                entry("GitHub", "github.com", "../user"),
                entry("FDroid", "evil.example", "a.b"),
                entry("Izzy", "apt.izzysoft.de", "a.b", "zz"),
            ).joinToString(",")}]}""",
        )
        assertEquals(Source(SourceKind.GitHub, "github.com", "o/r"), e[0].source)
        assertEquals(a, e[0].cert)
        assertEquals(Source(SourceKind.GitLab, "git.example.org", "team/app"), e[1].source)
        assertNull(e[1].cert)
        for (i in 2..5) assertNull(e[i].source, "entry $i")
        assertEquals(Source(SourceKind.Izzy, "apt.izzysoft.de", "a.b"), e[6].source)
        assertNull(e[6].cert)
    }

    @Test fun storeRoundTrip() {
        val d = Data(listOf(tracked), Settings(background = false, intervalHours = 12, autoInstall = false, unmeteredOnly = true))
        assertEquals(d, Store.decode(JSONObject(Store.encode(d).toString())))
    }

    @Test fun storeVersion1ReadsGitHubReleasesAgain() {
        val fdroid = Source(SourceKind.FDroid, "f-droid.org", "a.b").let { tracked.copy(id = it.id, source = it) }
        val v1 = Store.encode(Data(listOf(tracked, fdroid))).put("version", 1)
        val apps = Store.decode(JSONObject(v1.toString())).apps
        assertNull(apps[0].etag)
        assertEquals(tracked.etag, apps[1].etag)
    }

    @Test fun pins() {
        assertTrue(Packages.matches("aa", setOf("bb"), setOf("aa", "bb")))
        assertFalse(Packages.matches("aa", setOf("bb"), setOf("bb")))
        assertTrue(Packages.matches("aa,bb", setOf("aa", "bb"), setOf("aa", "bb")))
        assertFalse(Packages.matches("aa,bb", setOf("aa"), setOf("aa", "bb")))
        assertFalse(Packages.matches("", setOf("aa"), setOf("aa")))
    }

    @Test fun zipTail() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for (n in listOf("AndroidManifest.xml", "lib/arm64-v8a/libx.so", "lib/x86_64/libx.so", "classes.dex")) {
                    z.putNextEntry(ZipEntry(n))
                    z.write(ByteArray(3000) { it.toByte() })
                    z.closeEntry()
                }
            }
        }.toByteArray()
        val tail = bytes.copyOfRange(bytes.size - 1000, bytes.size)
        assertEquals(setOf("arm64-v8a", "x86_64"), ZipAbis.fromTail(tail, bytes.size.toLong()))
        assertNull(ZipAbis.fromTail(bytes.copyOfRange(bytes.size - 100, bytes.size), bytes.size.toLong()))
        assertEquals(12345L, ZipAbis.totalFromContentRange("bytes 100-199/12345"))
    }
}
