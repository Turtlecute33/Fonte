package app.fonte

import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.TrackedApp
import org.json.JSONException
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun userTextHidesParserInputAndCapsLength() {
        assertEquals("The source sent an answer that Fonte cannot read", Library.userText(JSONException("x".repeat(10_000)), "Check failed"))
        assertEquals(200, Library.userText(IOException("a".repeat(500)), "f").length)
        assertEquals("f", Library.userText(IOException(null as String?), "f"))
        assertEquals("f", Library.userText(IOException(" "), "f"))
        assertEquals("Not found on GitHub", Library.userText(IOException("Not found on GitHub"), "f"))
    }
}
