package app.fonte.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import app.fonte.net.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest

/** The installed state of tracked apps, read from PackageManager. Only tracked packages are read. */
class Packages(context: Context) {
    private val pm = context.packageManager

    data class Info(
        val pkg: String,
        val versionName: String,
        val versionCode: Long,
        /** [pin] of the current signing certificates. */
        val cert: String,
        val installer: String?,
        val updateOwner: String?,
    )

    private val _installed = MutableStateFlow<Map<String, Info>>(emptyMap())
    val installed: StateFlow<Map<String, Info>> = _installed.asStateFlow()

    fun info(pkg: String): Info? = try {
        val pi = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val source = runCatching { pm.getInstallSourceInfo(pkg) }.getOrNull()
        Info(
            pkg = pkg,
            versionName = pi.versionName.orEmpty(),
            versionCode = pi.longVersionCode,
            cert = pin(current(pi.signingInfo)),
            installer = source?.installingPackageName,
            updateOwner = source?.updateOwnerPackageName,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Time of the last [refresh] of all tracked packages. */
    @Volatile private var refreshedAt = 0L

    /** Reads [pkgs] again. Off the main thread. */
    fun refresh(pkgs: Collection<String>) {
        val now = System.currentTimeMillis()
        _installed.value = pkgs.mapNotNull(::info).associateBy { it.pkg }
        refreshedAt = now
    }

    /** [refresh], but not when the last one is less than [FRESH_MS] old (an activity start right after the app start). */
    fun refreshIfOld(pkgs: Collection<String>) {
        if (System.currentTimeMillis() - refreshedAt >= FRESH_MS) refresh(pkgs)
    }

    /** Reads one package again (after an install or a package broadcast). Off the main thread. */
    fun refresh(pkg: String): Info? {
        val info = info(pkg)
        _installed.update { if (info != null) it + (pkg to info) else it - pkg }
        return info
    }

    fun label(pkg: String): String? = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()

    /** The app's icon (launcher shape) at [px] pixels, or null. Off the main thread. */
    fun icon(pkg: String, px: Int): Bitmap? = runCatching {
        val d = pm.getApplicationIcon(pkg)
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
            d.setBounds(0, 0, px, px)
            d.draw(Canvas(b))
        }
    }.getOrNull()

    fun launchIntent(pkg: String) = pm.getLaunchIntentForPackage(pkg)

    companion object {
        private const val FRESH_MS = 5_000L

        /** SHA-256 of each certificate the APK is signed with now. */
        fun current(si: SigningInfo?): Set<String> = si?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()

        /** [current] plus every older certificate of a key rotation (APK signature scheme v3). */
        fun lineage(si: SigningInfo?): Set<String> {
            si ?: return emptySet()
            val history = if (si.hasMultipleSigners()) emptyList() else si.signingCertificateHistory?.toList().orEmpty()
            return current(si) + history.map { sha256(it.toByteArray()) }
        }

        /** The stored form of a set of certificates: sorted digests, comma-separated. */
        fun pin(certs: Set<String>): String = certs.sorted().joinToString(",")

        /**
         * Does an APK signed with [current] (rotation history [lineage]) fit the [pinned] certificate? One pinned
         * certificate may be in the history (the developer rotated the key); several must all still sign. A pin with
         * no certificate fits nothing.
         */
        fun matches(pinned: String, current: Set<String>, lineage: Set<String>): Boolean {
            val certs = pinned.split(',').filter { it.isNotEmpty() }.toSet()
            return when (certs.size) {
                0 -> false
                1 -> certs.first() in lineage
                else -> certs == current
            }
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }
}
