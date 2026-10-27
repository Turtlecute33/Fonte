package app.fonte

import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.TrackedApp
import app.fonte.install.Archive
import app.fonte.install.Gate
import app.fonte.install.Gate.Decision
import app.fonte.install.Packages
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The install gates in their order: package name, pin, signed index, version code. Made-up digests. */
class GateTest {
    private val old = "a".repeat(64)
    private val new = "b".repeat(64)
    private val other = "c".repeat(64)
    private val file = "d".repeat(64)

    private fun app(kind: SourceKind = SourceKind.GitHub, pkg: String? = "org.example.app", cert: String? = null) =
        Source(kind, "host", if (kind == SourceKind.GitHub) "o/r" else "org.example.app")
            .let { TrackedApp(it.id, it, "x", packageName = pkg, cert = cert) }

    private fun archive(pkg: String = "org.example.app", code: Long = 2, current: String = old, lineage: Set<String> = setOf(current)) =
        Archive(pkg, code, "2", setOf(current), lineage, file)

    private fun installed(code: Long = 1, cert: String = old) = Packages.Info("org.example.app", "1", code, cert, null, null)

    /** An index that must not be read. */
    private val noIndex: suspend () -> Set<String> = { error("index read") }

    @Test fun refusesAnotherPackageBeforeAnyOtherGate() = runTest {
        val d = Gate.decide(app(SourceKind.FDroid), archive(pkg = "org.example.evil"), null, noIndex)
        assertEquals(Decision.Refuse("The APK is org.example.evil, not org.example.app"), d)
    }

    @Test fun refusesAnotherKey() = runTest {
        assertIs<Decision.Refuse>(Gate.decide(app(cert = old), archive(current = other), null, noIndex))
        // No pin in the list: the installed certificate is the pin.
        assertIs<Decision.Refuse>(Gate.decide(app(), archive(current = other), installed(cert = old), noIndex))
        // A stored pin wins over the installed certificate.
        assertIs<Decision.Refuse>(Gate.decide(app(cert = other), archive(current = old), installed(cert = old), noIndex))
    }

    @Test fun followsAKeyRotation() = runTest {
        val d = Gate.decide(app(cert = old), archive(current = new, lineage = setOf(new, old)), installed(), noIndex)
        // From now on the new certificate is the pin.
        assertEquals(Decision.Install(new), d)
    }

    @Test fun firstRepoInstallNeedsTheSignedIndex() = runTest {
        for (kind in listOf(SourceKind.FDroid, SourceKind.Izzy)) {
            val refused = Gate.decide(app(kind), archive(), null) { setOf(other) }
            assertIs<Decision.Refuse>(refused)
            assertTrue("signed ${kind.label} index" in refused.reason)
            assertEquals(Decision.Install(old), Gate.decide(app(kind), archive(), null) { setOf(other, file) })
        }
    }

    @Test fun indexIsReadOnlyWhenNothingIsPinned() = runTest {
        // Pinned: the pin vouches for the file.
        assertEquals(Decision.Install(old), Gate.decide(app(SourceKind.FDroid, cert = old), archive(), null, noIndex))
        assertEquals(Decision.Install(old), Gate.decide(app(SourceKind.FDroid), archive(), installed(), noIndex))
        // Code hosts have no signed index: the first install pins the certificate.
        assertEquals(Decision.Install(old), Gate.decide(app(SourceKind.GitHub), archive(), null, noIndex))
        assertEquals(Decision.Install(old), Gate.decide(app(SourceKind.GitLab), archive(), null, noIndex))
    }

    @Test fun sameOrOlderVersionIsUpToDate() = runTest {
        assertEquals(Decision.UpToDate(old), Gate.decide(app(), archive(code = 2), installed(code = 2), noIndex))
        assertEquals(Decision.UpToDate(old), Gate.decide(app(cert = old), archive(code = 1), installed(code = 2), noIndex))
        assertEquals(Decision.Install(old), Gate.decide(app(), archive(code = 3), installed(code = 2), noIndex))
        // The same version with a rotated key keeps the pin: only an install moves it.
        val rotated = archive(code = 2, current = new, lineage = setOf(new, old))
        assertEquals(Decision.UpToDate(old), Gate.decide(app(cert = old), rotated, installed(code = 2), noIndex))
        // No stored pin: the installed certificate is the pin.
        assertEquals(Decision.UpToDate(new), Gate.decide(app(), archive(code = 2, current = new), installed(code = 2, cert = new), noIndex))
        // An older APK with another key is refused, not up to date.
        assertIs<Decision.Refuse>(Gate.decide(app(), archive(code = 1, current = other), installed(code = 2), noIndex))
    }
}
