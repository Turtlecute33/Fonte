package app.fonte

import app.fonte.data.Asset
import app.fonte.data.Release
import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.TrackedApp
import app.fonte.source.Versions
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionsTest {
    @Test fun compare() {
        assertTrue(Versions.compare("v1.10.0", "1.9.9") > 0)
        assertEquals(0, Versions.compare("v1.7.3", "1.7.3"))
        assertEquals(0, Versions.compare("v2", "2.0"))
        assertEquals(0, Versions.compare("1.7.3", "1.7.3-fdroid"))
        assertTrue(Versions.compare("2.0.0", "2.0.0-beta1") > 0)
        assertTrue(Versions.compare("2.0.0-rc1", "2.0.0") < 0)
        assertTrue(Versions.compare("1.2.3", "1.2.4") < 0)
        assertTrue(Versions.compare("nightly", "stable") > 0)
    }

    @Test fun fromTag() {
        assertEquals("1.2.3", Versions.fromTag("v1.2.3"))
        assertEquals("release-1", Versions.fromTag("release-1"))
        assertEquals("v", Versions.fromTag("v"))
    }

    private val source = Source(SourceKind.GitHub, "github.com", "o/r")
    private fun app(latestTag: String, code: Long? = null, installedTag: String? = null, installedCode: Long? = null) = TrackedApp(
        id = source.id, source = source, name = "r",
        latest = Release(latestTag, Versions.fromTag(latestTag), code, assets = listOf(Asset("a.apk", "https://x/a.apk"))),
        installedTag = installedTag, installedCode = installedCode,
    )

    @Test fun hasUpdate() {
        assertTrue(Versions.hasUpdate(app("v1.1"), 5, "1.0"))
        assertFalse(Versions.hasUpdate(app("v1.1"), 5, "1.1"))
        // F-Droid: version codes decide.
        assertTrue(Versions.hasUpdate(app("1.1", code = 11), 10, "1.1"))
        assertFalse(Versions.hasUpdate(app("1.1", code = 11), 12, "1.0"))
        // Installed through Fonte: the tag decides while the version code is the one Fonte installed.
        assertFalse(Versions.hasUpdate(app("build-77", installedTag = "build-77", installedCode = 5), 5, "77"))
        assertTrue(Versions.hasUpdate(app("build-78", installedTag = "build-77", installedCode = 5), 5, "77"))
    }
}
