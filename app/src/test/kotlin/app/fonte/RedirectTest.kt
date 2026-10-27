package app.fonte

import app.fonte.net.Http
import app.fonte.net.Http.Hop
import org.junit.Test
import kotlin.test.assertEquals

/** Where a redirect may go, and where the token may go with it. */
class RedirectTest {
    private val api = "api.github.com"

    @Test fun anotherHostDropsTheToken() {
        val cdn = "https://release-assets.example.com/a.apk?sig=1"
        assertEquals(Hop.Restart(cdn), Http.nextHop(api, cdn, credentials = true))
        // With no token, the request follows the redirect as it is.
        assertEquals(Hop.Follow, Http.nextHop(api, cdn, credentials = false))
    }

    @Test fun sameHostKeepsTheToken() {
        assertEquals(Hop.Follow, Http.nextHop(api, "https://api.github.com/repositories/1/releases", credentials = true))
        assertEquals(Hop.Follow, Http.nextHop(api, "https://API.GitHub.com/x", credentials = true))
    }

    @Test fun refusesInsecureOrBrokenAddresses() {
        for (credentials in listOf(true, false)) {
            assertEquals(Hop.Refuse, Http.nextHop(api, "http://api.github.com/x", credentials))
            assertEquals(Hop.Refuse, Http.nextHop(api, "http://example.com/a.apk", credentials))
            assertEquals(Hop.Refuse, Http.nextHop(api, "ftp://example.com/a.apk", credentials))
            assertEquals(Hop.Refuse, Http.nextHop(api, "https:///no-host", credentials))
            assertEquals(Hop.Refuse, Http.nextHop(api, "https://exa mple.com/", credentials))
            assertEquals(Hop.Refuse, Http.nextHop(api, "/relative", credentials))
        }
    }

    @Test fun restartSendsNoAuthorizationInAnyCase() {
        val headers = mapOf("authorization" to "Bearer t", "AUTHORIZATION" to "Bearer t", "Accept" to "application/octet-stream")
        assertEquals(mapOf("Accept" to "application/octet-stream"), Http.withoutAuth(headers))
        assertEquals(mapOf("Accept" to "x"), Http.withoutAuth(mapOf("Accept" to "x")))
    }
}
