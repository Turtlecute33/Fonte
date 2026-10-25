package app.fonte

import app.fonte.data.Asset
import app.fonte.source.Apks
import org.junit.Test
import kotlin.test.assertEquals

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

    @Test fun x86DeviceDropsArm() {
        assertEquals(listOf("a-x86_64.apk", "a.apk"), rank(names("a-arm64.apk", "a.apk", "a-x86_64.apk"), abis = listOf("x86_64", "x86")))
    }
}
