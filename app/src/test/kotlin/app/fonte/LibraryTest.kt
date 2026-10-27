package app.fonte

import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.TrackedApp
import org.junit.Test
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
}
