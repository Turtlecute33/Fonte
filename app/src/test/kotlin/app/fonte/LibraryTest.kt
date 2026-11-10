package app.fonte

import app.fonte.data.Asset
import app.fonte.data.Release
import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.TrackedApp
import org.json.JSONException
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    private fun app(kind: SourceKind, checkedAt: Long) = Source(kind, "host", "o/r").let { TrackedApp(it.id, it, "x", checkedAt = checkedAt) }

    @Test fun foregroundSkipsOnlyFreshGitHubAppsWithoutToken() {
        val now = 10 * 3600_000L
        val fresh = now - 40 * 60_000L
        val stale = now - 61 * 60_000L
        assertTrue(Library.skipOnForeground(app(SourceKind.GitHub, fresh), hasToken = false, now))
        assertFalse(Library.skipOnForeground(app(SourceKind.GitHub, stale), hasToken = false, now))
        assertFalse(Library.skipOnForeground(app(SourceKind.GitHub, fresh), hasToken = true, now))
        assertFalse(Library.skipOnForeground(app(SourceKind.GitLab, fresh), hasToken = false, now))
        assertFalse(Library.skipOnForeground(app(SourceKind.FDroid, fresh), hasToken = false, now))
    }

    @Test fun checkKeepsTheReasonOfARefusedInstall() {
        fun release(tag: String) = Release(tag, tag, assets = listOf(Asset("a.apk", "https://host/a.apk")))
        val failed = app(SourceKind.GitHub, 0).copy(installError = "Connection lost")
        val refused = failed.copy(installError = "Refused", refusedTag = "v2")
        // An error that a new download can pass goes with the next check.
        assertNull(Library.installErrorAfter(failed, release("v2")))
        // A refusal stays while its release is the latest one, and goes with a new release or with no release.
        assertEquals("Refused", Library.installErrorAfter(refused, release("v2")))
        assertNull(Library.installErrorAfter(refused, release("v3")))
        assertNull(Library.installErrorAfter(refused, null))
    }

    @Test fun userTextHidesParserInputAndCapsLength() {
        assertEquals("The source sent an answer that Fonte cannot read", Library.userText(JSONException("x".repeat(10_000)), "Check failed"))
        assertEquals(200, Library.userText(IOException("a".repeat(500)), "f").length)
        assertEquals("f", Library.userText(IOException(null as String?), "f"))
        assertEquals("f", Library.userText(IOException(" "), "f"))
        assertEquals("Not found on GitHub", Library.userText(IOException("Not found on GitHub"), "f"))
    }
}
