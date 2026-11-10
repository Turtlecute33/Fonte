package app.fonte

import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.source.Links
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LinksTest {
    private fun known(text: String, hint: SourceKind? = null): Source? = (Links.parse(text, hint) as? Links.Parsed.Known)?.source

    @Test fun github() {
        assertEquals(Source(SourceKind.GitHub, "github.com", "ImranR98/Obtainium"), known("https://github.com/ImranR98/Obtainium/releases/latest"))
        assertEquals(Source(SourceKind.GitHub, "github.com", "owner/repo"), known("Look at this: https://www.github.com/owner/repo.git, nice"))
        assertEquals(Source(SourceKind.GitHub, "github.com", "owner/repo"), known("github.com/owner/repo"))
        assertNull(known("https://github.com/owner"))
        assertNull(known("https://github.com/../user"))
        assertNull(known("https://github.com/o/.."))
        assertNull(known("https://gitlab.com/a/./b"))
        assertEquals(Source(SourceKind.GitHub, "github.com", "o/.r"), known("https://github.com/o/.r"))
    }

    @Test fun gitlabWithSubgroups() {
        assertEquals(Source(SourceKind.GitLab, "gitlab.com", "AuroraOSS/AuroraStore"), known("https://gitlab.com/AuroraOSS/AuroraStore/-/releases"))
        assertEquals(Source(SourceKind.GitLab, "gitlab.com", "a/b/c"), known("https://gitlab.com/a/b/c"))
        assertEquals(Source(SourceKind.GitLab, "git.example.org", "team/app"), known("https://git.example.org/team/app", SourceKind.GitLab))
    }

    @Test fun codeberg() {
        assertEquals(Source(SourceKind.Forgejo, "codeberg.org", "Freeyourgadget/Gadgetbridge"), known("https://codeberg.org/Freeyourgadget/Gadgetbridge/releases"))
    }

    @Test fun fdroidAndIzzy() {
        assertEquals(Source(SourceKind.FDroid, "f-droid.org", "org.schabi.newpipe"), known("https://f-droid.org/en/packages/org.schabi.newpipe/"))
        assertEquals(Source(SourceKind.FDroid, "f-droid.org", "org.fdroid.fdroid"), known("https://f-droid.org/packages/org.fdroid.fdroid"))
        assertEquals(Source(SourceKind.Izzy, "apt.izzysoft.de", "com.aurora.store"), known("https://apt.izzysoft.de/fdroid/index/apk/com.aurora.store"))
        assertEquals(Source(SourceKind.Izzy, "apt.izzysoft.de", "com.aurora.store"), known("https://android.izzysoft.de/frepo/com.aurora.store"))
    }

    @Test fun fdroidRepos() {
        val key = "b7c2eefd8dac7806af67dfcd92eb18126bc08312a7f2d6f3862e46013c7a6135"
        val guardian = Links.Parsed.Choose("guardianproject.info", "fdroid/repo", key)
        assertEquals(guardian, Links.parse("https://guardianproject.info/fdroid/repo?fingerprint=${key.uppercase()}"))
        assertEquals(guardian, Links.parse("Add: fdroidrepos://guardianproject.info/fdroid/repo/?fingerprint=$key"))
        assertEquals(guardian, Links.parse("https://fdroid.link/#https://guardianproject.info/fdroid/repo?fingerprint=$key"))
        assertEquals("https://guardianproject.info/fdroid/repo", guardian.address)
        // One app: the link that Fonte makes for it.
        val tor = guardian.source("org.torproject.torbrowser")
        assertEquals(Source(SourceKind.Repo, "guardianproject.info", "fdroid/repo/org.torproject.torbrowser", key), tor)
        assertEquals(tor, known(tor.webUrl))
        assertEquals(guardian.address, tor.repoAddress)
        assertEquals("org.torproject.torbrowser", tor.packageName)
        // A fingerprint wins over a forge host, and "www." stays.
        assertEquals(Links.Parsed.Choose("gitlab.com", "guardianproject/fdroid-repo/-/raw/master/fdroid/repo", key), Links.parse("https://gitlab.com/guardianproject/fdroid-repo/-/raw/master/fdroid/repo?fingerprint=$key"))
        assertEquals(Links.Parsed.Choose("www.gpdroid.org", "fdroid/repo", key), Links.parse("https://www.gpdroid.org/fdroid/repo?fingerprint=$key"))
        // A repo at the root of its host.
        val root = Links.parse("https://repo.example.org/?fingerprint=$key") as Links.Parsed.Choose
        assertEquals("https://repo.example.org", root.address)
        assertEquals(root.address, root.source("org.example.app").repoAddress)
        // A short fingerprint, another parameter, a port, a user, a dot segment, the no-TLS scheme: no repo.
        for (bad in listOf("?fingerprint=B7C2", "?fingerprint=$key&x=1")) assertNull(Links.parse("https://guardianproject.info/fdroid/repo$bad"), bad)
        assertNull(Links.parse("https://guardianproject.info:8443/fdroid/repo?fingerprint=$key"))
        assertNull(Links.parse("https://u@guardianproject.info/fdroid/repo?fingerprint=$key"))
        assertNull(Links.parse("https://guardianproject.info/fdroid/../repo?fingerprint=$key"))
        assertNull(Links.parse("fdroidrepo://guardianproject.info/fdroid/repo?fingerprint=$key"))
        // Only repo sources have a key, and they must have one.
        assertFailsWith<IllegalArgumentException> { Source(SourceKind.Repo, "h", "p/a.b") }
        assertFailsWith<IllegalArgumentException> { Source(SourceKind.GitHub, "github.com", "o/r", key) }
        assertFailsWith<IllegalArgumentException> { Source(SourceKind.Repo, "h", "p/a.b", key.uppercase()) }
    }

    @Test fun unknownHostNeedsProbe() {
        assertEquals(Links.Parsed.Probe("git.example.org", listOf("team", "app", "releases")), Links.parse("https://git.example.org/team/app/releases"))
        // A self-hosted forge keeps "www." (Review NETWORK-6).
        assertEquals(Links.Parsed.Probe("www.example.org", listOf("owner", "app")), Links.parse("https://www.example.org/owner/app"))
        assertNull(Links.parse("no link here"))
        assertNull(Links.parse("https://example.org/"))
        // A port or a user: the source would keep only the host, and its requests would go to another server.
        assertNull(Links.parse("https://git.example.org:3000/team/app"))
        assertNull(Links.parse("https://github.com@evil.example/owner/app"))
    }
}
