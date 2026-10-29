package app.fonte

import app.fonte.data.Asset
import app.fonte.source.Apks
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApksTest {
    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private fun names(vararg n: String) = n.map { Asset(it, "https://x/$it") }
    private fun rank(assets: List<Asset>, filter: String = "", abis: List<String> = arm64) = Apks.rank(assets, filter, abis).map { it.name }

    @Test fun prefersMainAbiThenUniversal() {
        val a = names("app-x86_64-release.apk", "app-universal-release.apk", "app-arm64-v8a-release.apk", "app-armeabi-v7a-release.apk", "notes.txt")
        assertEquals(listOf("app-arm64-v8a-release.apk", "app-universal-release.apk", "app-armeabi-v7a-release.apk"), rank(a))
    }

    @Test fun x86_64IsNotX86() {
        assertEquals(setOf("x86_64"), Apks.abisIn("app-x86_64.apk"))
        assertEquals(setOf("x86"), Apks.abisIn("app-x86.apk"))
        assertEquals(setOf("arm64-v8a"), Apks.abisIn("App_arm64.apk"))
        assertEquals(emptySet(), Apks.abisIn("charm-alarm-1.0.apk"))
    }

    @Test fun debugLast() {
        assertEquals(listOf("app-release.apk", "app-debug.apk"), rank(names("app-debug.apk", "app-release.apk")))
    }

    @Test fun filterAndInvertedFilter() {
        val a = names("app-fdroid-arm64-v8a.apk", "app-play-arm64-v8a.apk")
        assertEquals(listOf("app-fdroid-arm64-v8a.apk"), rank(a, "fdroid"))
        assertEquals(listOf("app-fdroid-arm64-v8a.apk"), rank(a, "!play"))
        // A bad regex is plain text.
        assertEquals(listOf("app-[x].apk"), rank(names("app-[x].apk", "app-y.apk"), "[x"))
    }

    @Test(timeout = 2_000) fun hostileFilterStaysFast() {
        val name = "app-" + "a".repeat(44) + "-release.apk"
        assertEquals(60, name.length)
        val hostile = listOf("(.*.*.*.*.*)*#", "(a+)+b", "((a+))+b", "(a|a)*b", ".*.*.*.*.*.*.*.*#", "(a)\\1b") +
            listOf("a?".repeat(45) + "a".repeat(45), ".?".repeat(40) + "#", "(a|a)".repeat(30) + "b", "(?:a|b)?".repeat(20)) +
            listOf("(?:\\c[)?" + ".?".repeat(30) + "#", "(?:\\Q[\\E)?" + ".?".repeat(30) + "#", "(?x)(a+) +?b") +
            listOf("(a+[a[a])])+?b", "(a+[]a)])+?b")
        for (f in hostile) {
            // Plain text: no match on the long name, a match on a name with the filter in it.
            assertEquals(emptyList(), rank(names(name), f), f)
            assertEquals(listOf("x$f.apk"), rank(names("x$f.apk"), f), f)
        }
        assertTrue(Apks.slow("a".repeat(201)))
        assertEquals(emptyList(), rank(names("a".repeat(197) + ".apk")))
    }

    @Test fun usualFiltersStayRegexes() {
        val usual = listOf("arm64.*", "^((?!play).)*$", "^(?!.*debug).*\\.apk$", "app-(arm64-v8a|universal)-release", ".*-v\\d+\\.\\d+-.*") +
            listOf("(arm64-v8a|armeabi-v7a|x86_64|x86|universal|noarch)", "v?\\d+\\.\\d+(\\.\\d+)?", ".*-v?\\d+\\.\\d+-.*") +
            listOf("(?i)^app-?(?:foss)?-?release\\.apk$", "arm64[-_]v8a")
        for (f in usual) {
            assertFalse(Apks.slow(f), f)
        }
        assertEquals(listOf("app-fdroid.apk"), rank(names("app-fdroid.apk", "app-play.apk"), "^((?!play).)*$"))
    }

    @Test fun x86DeviceDropsArm() {
        assertEquals(listOf("a-x86_64.apk", "a.apk"), rank(names("a-arm64.apk", "a.apk", "a-x86_64.apk"), abis = listOf("x86_64", "x86")))
    }
}
