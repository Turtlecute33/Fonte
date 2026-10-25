package app.fonte

import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.source.Links
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinksTest {
    private fun known(text: String, hint: SourceKind? = null): Source? = (Links.parse(text, hint) as? Links.Parsed.Known)?.source

    @Test fun github() {
        assertEquals(Source(SourceKind.GitHub, "github.com", "ImranR98/Obtainium"), known("https://github.com/ImranR98/Obtainium/releases/latest"))
        assertEquals(Source(SourceKind.GitHub, "github.com", "owner/repo"), known("Look at this: https://www.github.com/owner/repo.git, nice"))
        assertEquals(Source(SourceKind.GitHub, "github.com", "owner/repo"), known("github.com/owner/repo"))
        assertNull(known("https://github.com/owner"))
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

    @Test fun unknownHostNeedsProbe() {
        assertEquals(Links.Parsed.Probe("git.example.org", listOf("team", "app", "releases")), Links.parse("https://git.example.org/team/app/releases"))
        assertNull(Links.parse("no link here"))
        assertNull(Links.parse("https://example.org/"))
    }
}
