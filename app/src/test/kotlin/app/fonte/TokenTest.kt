package app.fonte

import app.fonte.data.Refusal
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TokenTest {
    /** FONTE-UX-002: a refusal stays after a restart, only for its token, and with its notice state. */
    @Test fun refusalHoldsOnlyForTheRefusedToken() {
        val refused = Refusal.of("ghp_old")
        assertFalse("ghp_old" in refused.encode())
        assertEquals(refused, Refusal.decode(refused.encode(), "ghp_old"))
        // A token that the user saved since then, or no token, is not refused.
        assertNull(Refusal.decode(refused.encode(), "ghp_new"))
        assertNull(Refusal.decode(refused.encode(), null))
        assertNull(Refusal.decode("", "ghp_old"))
        // After the notice, the next process does not post it again.
        val noticed = refused.copy(noticed = true)
        assertEquals(noticed, Refusal.decode(noticed.encode(), "ghp_old"))
    }
}
