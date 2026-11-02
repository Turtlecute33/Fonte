package app.fonte

import app.fonte.data.SourceKind
import app.fonte.install.RepoIndex
import app.fonte.source.Links
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** entry.jar files as F-Droid and IzzyOnDroid published them in October 2026, and the Guardian Project in November 2026. */
class RepoIndexTest {
    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    @Test fun acceptsTheRepoSignatures() {
        val fdroid = RepoIndex.readEntry(fixture("fdroid-entry.jar"), RepoIndex.REPO_CERTS.getValue(SourceKind.FDroid))
        assertEquals("/index-v2.json", fdroid.name)
        assertEquals(64, fdroid.sha256.length)
        val izzy = RepoIndex.readEntry(fixture("izzy-entry.jar"), RepoIndex.REPO_CERTS.getValue(SourceKind.Izzy))
        assertEquals("98073592c8df10ba291d3e2b3da8e1beda31d6ec788bdb7dbc01c4e8bb76d14e", izzy.sha256)
        assertEquals(14456805, izzy.size)
    }

    @Test fun acceptsAnotherRepoWithTheKeyFromItsLink() {
        val repo = Links.parse("https://guardianproject.info/fdroid/repo?fingerprint=B7C2EEFD8DAC7806AF67DFCD92EB18126BC08312A7F2D6F3862E46013C7A6135") as Links.Parsed.Choose
        val index = RepoIndex.readEntry(fixture("guardian-entry.jar"), repo.key)
        assertEquals("ee4f781ff49e8d6e240dd0be556aa32f93468fc51b70e36cf9757b86e66d3614", index.sha256)
        assertEquals(693310, index.size)
        assertFailsWith<SecurityException> { RepoIndex.readEntry(fixture("guardian-entry.jar"), RepoIndex.REPO_CERTS.getValue(SourceKind.FDroid)) }
    }

    @Test fun refusesAnotherRepoKey() {
        assertFailsWith<SecurityException> {
            RepoIndex.readEntry(fixture("fdroid-entry.jar"), RepoIndex.REPO_CERTS.getValue(SourceKind.Izzy))
        }
    }

    @Test fun refusesAChangedEntry() {
        val changed = File.createTempFile("entry", ".jar")
        try {
            ZipFile(fixture("izzy-entry.jar")).use { zip ->
                ZipOutputStream(changed.outputStream()).use { out ->
                    for (e in zip.entries()) {
                        out.putNextEntry(java.util.zip.ZipEntry(e.name))
                        val bytes = zip.getInputStream(e).readBytes()
                        out.write(if (e.name == "entry.json") bytes.decodeToString().replace("98073592", "00000000").toByteArray() else bytes)
                        out.closeEntry()
                    }
                }
            }
            assertFailsWith<SecurityException> { RepoIndex.readEntry(changed, RepoIndex.REPO_CERTS.getValue(SourceKind.Izzy)) }
        } finally {
            changed.delete()
        }
    }
}
