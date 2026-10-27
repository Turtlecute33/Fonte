package app.fonte

import app.fonte.data.Source
import app.fonte.data.SourceKind
import app.fonte.data.TrackedApp
import app.fonte.net.Http
import app.fonte.net.HttpGet
import app.fonte.source.Check
import app.fonte.source.Ctx
import app.fonte.source.FDroid
import app.fonte.source.Forgejo
import app.fonte.source.GitHub
import app.fonte.source.GitLab
import app.fonte.source.Notes
import app.fonte.source.Sources
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcesTest {
    private val abis = listOf("arm64-v8a", "armeabi-v7a")
    private fun app(kind: SourceKind, path: String, prerelease: Boolean = false) =
        Source(kind, "host", path).let { TrackedApp(it.id, it, "x", prerelease = prerelease) }

    @Test fun githubSkipsDraftsPrereleasesAndReleasesWithoutApk() {
        val json = """[
          {"tag_name":"v3","draft":true,"prerelease":false,"assets":[{"name":"a.apk","size":1,"browser_download_url":"https://g/3.apk"}]},
          {"tag_name":"v2-beta","draft":false,"prerelease":true,"assets":[{"name":"a.apk","size":1,"browser_download_url":"https://g/2.apk"}]},
          {"tag_name":"cli-1","draft":false,"prerelease":false,"assets":[{"name":"cli.zip","size":1,"browser_download_url":"https://g/c.zip"}]},
          {"tag_name":"v1.0.1","draft":false,"prerelease":false,"published_at":"2026-10-01T10:00:00Z","body":"**Fixed** [bug](https://x)","html_url":"https://github.com/o/r/releases/v1.0.1",
           "assets":[{"name":"app-arm64-v8a.apk","size":10,"browser_download_url":"https://g/1.apk","digest":"sha256:ABCDEF","url":"https://api.github.com/repos/o/r/releases/assets/7"}]}
        ]"""
        val stable = Sources.pick(GitHub.parse(json), app(SourceKind.GitHub, "o/r"), abis)!!
        assertEquals("v1.0.1", stable.tag)
        assertEquals("1.0.1", stable.version)
        assertEquals("abcdef", stable.assets.single().sha256)
        assertEquals("https://api.github.com/repos/o/r/releases/assets/7", stable.assets.single().api)
        assertEquals("Fixed bug", stable.notes)
        assertEquals(1790848800000, stable.date)
        assertEquals("v2-beta", Sources.pick(GitHub.parse(json), app(SourceKind.GitHub, "o/r", prerelease = true), abis)!!.tag)
    }

    /** Answers with the scripted status codes in turn, and keeps the headers of each request. */
    private class Scripted(private vararg val codes: Int) : HttpGet {
        val sent = ArrayList<Map<String, String>>()

        override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): Http.Response {
            sent += headers
            val code = codes[sent.size - 1]
            val body = if (code == 200) """[{"tag_name":"v1","assets":[{"name":"a.apk","browser_download_url":"https://g/1.apk"}]}]""" else ""
            return Http.Response(code, url, emptyMap(), body.toByteArray())
        }
    }

    @Test fun githubGoesOnWithoutARefusedToken() = runTest {
        val http = Scripted(401, 200, 200)
        var refused = false
        val ctx = Ctx(lazyOf(http), { "t".takeIf { !refused } }, abis, tokenRefused = { refused = it == "t" })
        val app = app(SourceKind.GitHub, "o/r")
        assertEquals("v1", (GitHub.check(app, ctx) as Check.Found).release?.tag)
        assertTrue(refused)
        assertEquals("Bearer t", http.sent[0][Http.AUTHORIZATION])
        assertNull(http.sent[1][Http.AUTHORIZATION])
        // A later check makes one request, with no token.
        GitHub.check(app, ctx)
        assertEquals(3, http.sent.size)
        assertNull(http.sent[2][Http.AUTHORIZATION])
    }

    @Test fun forgejo() {
        val json = """[{"tag_name":"0.95.1","draft":false,"prerelease":false,"published_at":"2026-10-09T11:04:35+02:00","body":"",
          "assets":[{"name":"banglejs-0.95.1.apk","size":5,"browser_download_url":"https://codeberg.org/b.apk"},{"name":"gadgetbridge-0.95.1.apk","size":6,"browser_download_url":"https://codeberg.org/g.apk"}]}]"""
        val r = Sources.pick(Forgejo.parse(json), app(SourceKind.Forgejo, "o/r").copy(apkFilter = "^gadgetbridge"), abis)!!
        assertEquals(listOf("gadgetbridge-0.95.1.apk"), r.assets.map { it.name })
    }

    @Test fun gitlabLinksAndUploads() = runTest {
        val json = """[{"tag_name":"4.8.4","upcoming_release":false,"released_at":"2026-10-01T00:00:00Z",
          "description":"Changelog\n[AuroraStore-4.8.4.apk](/uploads/245eec88/AuroraStore-4.8.4.apk) [hw](/uploads/ceb55e58/AuroraStore-hw-4.8.4.apk)",
          "assets":{"links":[{"name":"extra.apk","url":"https://gitlab.com/x/extra.apk","direct_asset_url":"https://gitlab.com/x/-/releases/4.8.4/downloads/extra.apk"}]},
          "_links":{"self":"https://gitlab.com/AuroraOSS/AuroraStore/-/releases/4.8.4"}}]"""
        var asked = 0
        val raws = GitLab.parse(json, "gitlab.com") { asked++; 6922885L }
        assertEquals(1, asked)
        assertEquals(
            listOf(
                "https://gitlab.com/x/-/releases/4.8.4/downloads/extra.apk",
                "https://gitlab.com/-/project/6922885/uploads/245eec88/AuroraStore-4.8.4.apk",
                "https://gitlab.com/-/project/6922885/uploads/ceb55e58/AuroraStore-hw-4.8.4.apk",
            ),
            raws.single().assets.map { it.url },
        )
    }

    @Test fun fdroidSuggestedAndAbiGroups() {
        val json = """{"packageName":"p.q","suggestedVersionCode":"102","packages":[
          {"versionName":"2.0","versionCode":"200"},{"versionName":"1.0","versionCode":"103"},{"versionName":"1.0","versionCode":102},{"versionName":"1.0","versionCode":101},{"versionName":"0.9","versionCode":90}]}"""
        val r = FDroid.parse(json, "p.q", "https://f-droid.org", prerelease = false, page = null)!!
        assertEquals("1.0", r.version)
        assertEquals(101, r.code)
        assertEquals(listOf("p.q_102.apk", "p.q_103.apk", "p.q_101.apk"), r.assets.map { it.name })
        assertEquals("https://f-droid.org/repo/p.q_102.apk", r.assets.first().url)
        val beta = FDroid.parse(json, "p.q", "https://f-droid.org", prerelease = true, page = null)!!
        assertEquals("2.0", beta.version)
        assertNull(FDroid.parse("""{"packages":[]}""", "p.q", "https://f-droid.org", false, null))
    }

    @Test fun notesArePlain() {
        val n = Notes.plain("## New\n<!-- hidden -->\n- **Bold** item ![img](https://x/i.png)\n* <b>tag</b> &amp; more\n\n\n\nEnd")
        assertEquals("New\n\n• Bold item \n• tag & more\n\nEnd", n)
        assertTrue(Notes.plain("x".repeat(7000)).length <= 6001)
        assertEquals("a\n-b\n  • c", Notes.plain("a\n-b\n  - c"))
    }

    /** Before the fix, 125,000 "<" (GitHub's limit for a release text) took 12 s on a laptop. */
    @Test(timeout = 5_000) fun hostileNotesStayFast() = runTest {
        for (s in listOf("<", "<!--", "![", "[", "[a](", "\n", " \n")) Notes.plain(s.repeat(2_000_000 / s.length))
        GitLab.parse("""[{"tag_name":"1","description":"${"(https://".repeat(200_000)}${"](/uploads/a/".repeat(100_000)}"}]""", "h") { 1L }
    }
}
