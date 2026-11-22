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

    @Test fun buildNumbers() {
        // Peach Bitcoin: every build has the version name 0.69.0, only the tag tells the build.
        assertTrue(Versions.compare("0.69.0(402)", "0.69.0") > 0)
        assertTrue(Versions.compare("0.69.0(402)", "0.69.0(401)") > 0)
        assertEquals(0, Versions.compare("0.69.0(402)", "0.69.0 (402)"))
        assertTrue(Versions.compare("1.2.3", "1.2.3 (45)") < 0)
        assertTrue(Versions.compare("0.70.0", "0.69.0(402)") > 0)
        assertTrue(Versions.compare("2.0.0-rc1", "2.0.0(5)") < 0)
        // Cromite: the digits of the commit hash are no build number.
        assertEquals(0, Versions.compare("153.0.8010.37-11507ac1061b5ea227806f5e84db5a57df6ccf6a", "153.0.8010.37"))
        assertEquals(0, Versions.compare("1.2.3", "1.2.3-42g1a2b"))
        assertTrue(Versions.higher("0.69.0(402)", "0.69.0(401)"))
    }

    @Test fun higherOnlyWithNumbers() {
        assertTrue(Versions.higher("2.0.1", "1.9.5"))
        assertFalse(Versions.higher("1.9.5", "2.0.1"))
        assertFalse(Versions.higher("2.0", "2.0.0"))
        assertFalse(Versions.higher("nightly", "1.0"))
        assertFalse(Versions.higher("1.0", "stable"))
    }

    @Test fun isPre() {
        assertTrue(Versions.isPre("Nightly v1.100.36 (Chromium 156.0.8078.48)"))
        assertTrue(Versions.isPre("v2.0.0-rc.1"))
        assertTrue(Versions.isPre("1.0-beta2"))
        assertTrue(Versions.isPre("1.0-prerelease"))
        assertFalse(Versions.isPre("Release v1.98.60 (Chromium 156.0.8078.48)"))
        assertFalse(Versions.isPre("v3.1: Preferences for Devices"))
    }

    @Test fun dateTags() {
        assertTrue(Versions.compare("2024-11-01", "2024-10-01") > 0)
        assertTrue(Versions.compare("r2024-11-01", "2024.10.01") > 0)
        assertEquals(0, Versions.compare("2024-11-01", "2024.11.01"))
        assertTrue(Versions.compare("2024-11-01", "2024-11-01-rc1") > 0)
        // No separators: one number, as before.
        assertTrue(Versions.compare("20241101", "20241001") > 0)
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
        // Adopted app with the same version name: the build in the tag decides.
        assertTrue(Versions.hasUpdate(app("v0.69.0(402)"), 401, "0.69.0"))
        // F-Droid: version codes decide.
        assertTrue(Versions.hasUpdate(app("1.1", code = 11), 10, "1.1"))
        assertFalse(Versions.hasUpdate(app("1.1", code = 11), 12, "1.0"))
        // Installed through Fonte: the tag decides while the version code is the one Fonte installed.
        assertFalse(Versions.hasUpdate(app("build-77", installedTag = "build-77", installedCode = 5), 5, "77"))
        assertTrue(Versions.hasUpdate(app("build-78", installedTag = "build-77", installedCode = 5), 5, "77"))
        // Review SOURCES-2: a newer release of an older branch is no update.
        assertFalse(Versions.hasUpdate(app("v1.9.5", installedTag = "v2.0.0", installedCode = 5), 5, "2.0.0"))
        assertTrue(Versions.hasUpdate(app("v2.0.1", installedTag = "v2.0.0", installedCode = 5), 5, "2.0.0"))
    }
}
