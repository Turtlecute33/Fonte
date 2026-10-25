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
        cert = "aa,bb", installedTag = "v1", installedCode = 3,
        latest = Release("v2", "2", null, 1L, "notes", "https://p", listOf(Asset("a.apk", "https://x/a.apk", 9, "ff"))),
        etag = "W/\"e\"", checkedAt = 5, error = "boom", addedAt = 7,
    )

    @Test fun fonteExportRoundTrip() {
        val e = Transfer.parse(Transfer.export(listOf(tracked))).single()
        assertEquals(source, e.source)
        assertEquals("aa,bb", e.cert)
        assertFalse(e.autoUpdate)
        assertEquals("x", e.filter)
    }

    @Test fun storeRoundTrip() {
        val d = Data(listOf(tracked), Settings(background = false, intervalHours = 12, autoInstall = false, unmeteredOnly = true))
        assertEquals(d, Store.decode(JSONObject(Store.encode(d).toString())))
    }

    @Test fun pins() {
        assertTrue(Packages.matches("aa", setOf("bb"), setOf("aa", "bb")))
        assertFalse(Packages.matches("aa", setOf("bb"), setOf("bb")))
        assertTrue(Packages.matches("aa,bb", setOf("aa", "bb"), setOf("aa", "bb")))
        assertFalse(Packages.matches("aa,bb", setOf("aa"), setOf("aa", "bb")))
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
