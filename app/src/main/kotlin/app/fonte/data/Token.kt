package app.fonte.data

import android.util.AtomicFile
import android.util.Log
import app.fonte.install.Packages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileNotFoundException

/**
 * The optional GitHub token (raises the API limit from 60 to 5,000 requests an hour). Encrypted by the [Vault] key,
 * kept in memory after the first read, and sent only to api.github.com. It goes with each GitHub check, so GitHub
 * links the checks to the account.
 */
class Token(dir: File, private val vault: Vault) {
    private val file = AtomicFile(File(dir, "github-token.bin"))
    private val refusedFile = AtomicFile(File(dir, "github-token-refused"))
    private val _isSet = MutableStateFlow(file.baseFile.exists())
    val isSet: StateFlow<Boolean> = _isSet.asStateFlow()
    private val _refused = MutableStateFlow(refusedFile.baseFile.exists())

    /** GitHub refused the token (expired or revoked), also in an earlier process. Until the next [set], [toSend] gives no token. */
    val refused: StateFlow<Boolean> = _refused.asStateFlow()

    @Volatile private var cached: String? = null
    @Volatile private var refusal: Refusal? = null
    @Volatile private var read = false

    /** Call off the main thread: the first call is a Keystore operation. */
    fun get(): String? {
        if (!read) synchronized(this) {
            if (!read) {
                cached = try {
                    vault.decryptString(file.readFully())
                } catch (_: FileNotFoundException) {
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "Token unreadable: ${e.javaClass.simpleName}")
                    null
                }
                // A refusal of an older token (the process stopped in [set]) does not count.
                val kept = runCatching { Refusal.decode(refusedFile.readFully().decodeToString(), cached) }.getOrNull()
                if (kept == null) keep(null) else refusal = kept
                read = true
            }
        }
        return cached
    }

    /** The token for a request: null while GitHub refuses it. Call off the main thread. */
    fun toSend(): String? = get()?.takeIf { refusal == null }

    /** GitHub refused [token]. A token that the user saved since then stays usable. */
    fun refuse(token: String) = synchronized(this) {
        if (token == cached && refusal == null) keep(Refusal.of(token))
    }

    /** True once for each refused token, also across process starts: then the background job posts its notice. */
    fun takeNotice(): Boolean = synchronized(this) {
        get()
        val r = refusal?.takeUnless { it.noticed } ?: return false
        keep(r.copy(noticed = true))
        true
    }

    /** Saves [token], or deletes it when blank. Call off the main thread. */
    fun set(token: String?) = synchronized(this) {
        val value = token?.trim().orEmpty()
        if (value.isEmpty()) {
            file.delete()
            cached = null
        } else {
            file.write(vault.encryptString(value))
            cached = value
        }
        read = true
        _isSet.value = cached != null
        keep(null)
    }

    /** Keeps [r] in memory and in its file. When the write fails, [r] holds for this process only. */
    private fun keep(r: Refusal?) {
        refusal = r
        _refused.value = r != null
        runCatching { if (r == null) refusedFile.delete() else refusedFile.write(r.encode().toByteArray()) }
            .onFailure { Log.w(TAG, "Refusal not kept: ${it.javaClass.simpleName}") }
    }

    private fun AtomicFile.write(bytes: ByteArray) {
        val out = startWrite()
        try {
            out.write(bytes)
            finishWrite(out)
        } catch (e: Exception) {
            failWrite(out)
            throw e
        }
    }

    private companion object {
        const val TAG = "FonteToken"
    }
}

/**
 * A token that GitHub refused, kept on disk across process starts: its SHA-256, never the token. [noticed]: the
 * background job told the user. FONTE-UX-002.
 */
internal data class Refusal(val fingerprint: String, val noticed: Boolean = false) {
    fun encode(): String = if (noticed) "$fingerprint $NOTICED" else fingerprint

    companion object {
        private const val NOTICED = "noticed"

        fun of(token: String) = Refusal(Packages.sha256(token.toByteArray()))

        /** The refusal in [text], only when it is for [token]. */
        fun decode(text: String, token: String?): Refusal? {
            val parts = text.split(' ')
            return Refusal(parts[0], parts.getOrNull(1) == NOTICED).takeIf { token != null && it.fingerprint == of(token).fingerprint }
        }
    }
}
