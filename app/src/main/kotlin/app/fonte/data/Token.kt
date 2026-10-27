package app.fonte.data

import android.util.AtomicFile
import android.util.Log
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
    private val _isSet = MutableStateFlow(file.baseFile.exists())
    val isSet: StateFlow<Boolean> = _isSet.asStateFlow()
    private val _refused = MutableStateFlow(false)

    /** GitHub refused the token (expired or revoked). Until the next [set], [toSend] gives no token. */
    val refused: StateFlow<Boolean> = _refused.asStateFlow()

    @Volatile private var cached: String? = null
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
                read = true
            }
        }
        return cached
    }

    /** The token for a request: null while GitHub refuses it. Call off the main thread. */
    fun toSend(): String? = if (_refused.value) null else get()

    /** GitHub refused [token]. A token that the user saved since then stays usable. */
    fun refuse(token: String) {
        if (token == cached) _refused.value = true
    }

    /** Saves [token], or deletes it when blank. Call off the main thread. */
    fun set(token: String?) = synchronized(this) {
        val value = token?.trim().orEmpty()
        if (value.isEmpty()) {
            file.delete()
            cached = null
        } else {
            val out = file.startWrite()
            try {
                out.write(vault.encryptString(value))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
            cached = value
        }
        read = true
        _isSet.value = cached != null
        _refused.value = false
    }

    private companion object {
        const val TAG = "FonteToken"
    }
}
