package app.fonte.net

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.NetworkException
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The GET that the sources use. [Http] is the implementation; tests use a scripted fake. */
interface HttpGet {
    /** A GET that reads the whole body (at most [maxBytes]). Any status is a result; only network errors throw. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = Http.MAX_BODY): Http.Response
}

/**
 * HTTPS through the platform's HttpEngine (Cronet in the Connectivity module, Android 14+): HTTP/3, HTTP/2
 * multiplexing and Brotli, with no library and no native code in the APK. One engine for the whole app, so the
 * checks of many apps on one host share one connection. No HTTP cache, no cookies, a User-Agent with no device data.
 * Only https URLs, also after redirects. An Authorization header never follows a redirect to another host.
 */
class Http(context: Context) : HttpGet {
    private val engine: HttpEngine = HttpEngine.Builder(context)
        // Only connection data (HTTP/3 support of hosts), so that QUIC works from the first request after a restart.
        .setStoragePath(File(context.noBackupFilesDir, "http").apply { mkdirs() }.path)
        .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0)
        .setEnableQuic(true)
        .setEnableHttp2(true)
        .setEnableBrotli(true)
        .setUserAgent(USER_AGENT)
        .build()
    private val executor = Dispatchers.IO.asExecutor()

    class Response(val code: Int, val url: String, private val headers: Map<String, List<String>>, val body: ByteArray) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        val text: String get() = body.decodeToString()
    }

    class Download(val size: Long, val sha256: String)

    override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): Response {
        var attempt = 0
        while (true) {
            val body = ByteArrayOutputStream()
            try {
                val info = withTimeoutOrNull(TIMEOUT_MS) {
                    exchange(url, headers, onStart = {}) { buf ->
                        if (body.size() + buf.remaining() > maxBytes) throw IOException("Answer too large")
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        body.write(bytes)
                    }
                } ?: throw IOException("Timed out")
                Log.d(TAG, "GET ${URI(url).host} → ${info.httpStatusCode} ${info.negotiatedProtocol}")
                return Response(info.httpStatusCode, info.url, info.headers.asMap, body.toByteArray())
            } catch (e: HttpException) {
                if (e !is NetworkException || !e.isImmediatelyRetryable || ++attempt > 1) throw readable(e)
            }
        }
    }

    /**
     * Streams [url] into [to] and hashes it on the way. Fails when the server sends no data for [STALL_MS], when the
     * size passes [maxBytes], or when fewer bytes arrive than announced.
     */
    suspend fun download(
        url: String,
        to: File,
        maxBytes: Long,
        headers: Map<String, String> = emptyMap(),
        progress: (done: Long, total: Long) -> Unit,
    ): Download {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = -1L
        var done = 0L
        val lastData = AtomicLong(System.nanoTime())
        try {
            FileChannel.open(to.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                coroutineScope {
                    val watchdog = launch {
                        while (true) {
                            delay(STALL_MS / 4)
                            if (System.nanoTime() - lastData.get() > STALL_MS * 1_000_000) throw IOException("Download stalled")
                        }
                    }
                    exchange(
                        url,
                        headers,
                        onStart = { info ->
                            if (info.httpStatusCode != 200) throw IOException("Server answered HTTP ${info.httpStatusCode}")
                            val map = info.headers.asMap
                            // A compressed transfer announces the compressed length; HttpEngine hands out decoded data.
                            if (map.value("content-encoding") == null) total = map.value("content-length")?.toLongOrNull() ?: -1
                            if (total > maxBytes) throw IOException("File too large")
                        },
                    ) { buf ->
                        done += buf.remaining()
                        if (done > maxBytes) throw IOException("File too large")
                        lastData.set(System.nanoTime())
                        buf.mark()
                        digest.update(buf)
                        buf.reset()
                        while (buf.hasRemaining()) out.write(buf)
                        progress(done, total)
                    }
                    watchdog.cancel()
                }
            }
        } catch (e: HttpException) {
            throw readable(e)
        }
        if (total >= 0 && done != total) throw IOException("Download incomplete")
        Log.d(TAG, "Downloaded $done bytes from ${URI(url).host}")
        return Download(done, digest.digest().toHex())
    }

    /** Follows redirects; drops the Authorization header when one leads to another host. */
    private suspend fun exchange(
        url: String,
        headers: Map<String, String>,
        onStart: (UrlResponseInfo) -> Unit,
        onData: (ByteBuffer) -> Unit,
    ): UrlResponseInfo {
        var target = url
        var sent = headers
        repeat(MAX_HOPS) {
            requireHttps(target)
            when (val outcome = once(target, sent, onStart, onData)) {
                is Outcome.Done -> return outcome.info
                is Outcome.Moved -> {
                    target = outcome.url
                    sent = sent.filterKeys { !it.equals(AUTHORIZATION, ignoreCase = true) }
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private suspend fun once(
        url: String,
        headers: Map<String, String>,
        onStart: (UrlResponseInfo) -> Unit,
        onData: (ByteBuffer) -> Unit,
    ): Outcome = suspendCancellableCoroutine { cont ->
        val credentials = headers.keys.any { it.equals(AUTHORIZATION, ignoreCase = true) }
        val callback = Callback(cont, URI(url).host, credentials, onStart, onData)
        val request = engine.newUrlRequestBuilder(url, executor, callback)
            .setHttpMethod("GET")
            .setCacheDisabled(true)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        cont.invokeOnCancellation { request.cancel() }
        request.start()
    }

    private sealed interface Outcome {
        class Done(val info: UrlResponseInfo) : Outcome
        class Moved(val url: String) : Outcome
    }

    private class Callback(
        private val cont: CancellableContinuation<Outcome>,
        private val host: String,
        private val credentials: Boolean,
        private val onStart: (UrlResponseInfo) -> Unit,
        private val onData: (ByteBuffer) -> Unit,
    ) : UrlRequest.Callback {
        private val buffer = ByteBuffer.allocateDirect(BUFFER)
        private var failure: Throwable? = null
        private var moved: String? = null

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            val next = runCatching { URI(newLocationUrl) }.getOrNull()
            when {
                next == null || next.scheme != "https" || next.host == null -> abort(request, IOException("Refused a redirect to an insecure address"))
                credentials && !next.host.equals(host, ignoreCase = true) -> {
                    moved = newLocationUrl
                    request.cancel()
                }
                else -> request.followRedirect()
            }
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            try {
                onStart(info)
            } catch (e: Throwable) {
                return abort(request, e)
            }
            request.read(buffer)
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            try {
                onData(byteBuffer)
            } catch (e: Throwable) {
                return abort(request, e)
            }
            byteBuffer.clear()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) = cont.resume(Outcome.Done(info))

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) = cont.resumeWithException(error)

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            val f = failure
            val m = moved
            when {
                f != null -> cont.resumeWithException(f)
                m != null -> cont.resume(Outcome.Moved(m))
                else -> cont.cancel()
            }
        }

        private fun abort(request: UrlRequest, e: Throwable) {
            failure = e
            request.cancel()
        }
    }

    companion object {
        const val USER_AGENT = "Fonte"
        private const val TAG = "FonteHttp"
        const val AUTHORIZATION = "Authorization"
        const val MAX_BODY = 8 shl 20
        private const val MAX_HOPS = 8
        private const val BUFFER = 256 shl 10
        private const val TIMEOUT_MS = 30_000L
        private const val STALL_MS = 45_000L

        fun requireHttps(url: String) {
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrEmpty()) throw IOException("Only https links are allowed")
        }

        private fun Map<String, List<String>>.value(name: String) = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        private fun readable(e: HttpException): IOException {
            val text = when ((e as? NetworkException)?.errorCode) {
                NetworkException.ERROR_HOSTNAME_NOT_RESOLVED -> "Server not found"
                NetworkException.ERROR_INTERNET_DISCONNECTED -> "No internet connection"
                NetworkException.ERROR_TIMED_OUT, NetworkException.ERROR_CONNECTION_TIMED_OUT -> "Connection timed out"
                NetworkException.ERROR_CONNECTION_REFUSED -> "Connection refused"
                NetworkException.ERROR_CONNECTION_RESET, NetworkException.ERROR_CONNECTION_CLOSED -> "Connection lost"
                NetworkException.ERROR_NETWORK_CHANGED -> "The network changed"
                NetworkException.ERROR_ADDRESS_UNREACHABLE -> "Server unreachable"
                else -> e.message ?: "Network error"
            }
            return IOException(text, e)
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
