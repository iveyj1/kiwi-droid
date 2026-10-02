package org.kiwidroid.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** KiwiSDR `/status`: `key=value` lines, including `gps=(lat, lon)` and `loc=` when the owner publishes them. */
object ReceiverStatus {
    private const val MAX_BYTES = 16 * 1024
    private val GPS = Regex("""\(\s*([-+]?[0-9.]+)\s*,\s*([-+]?[0-9.]+)\s*\)""")

    fun parse(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }
        .toMap()

    /** Null when absent, malformed, or the unset (0, 0) placeholder. */
    fun location(fields: Map<String, String>): GeoPoint? {
        val match = fields["gps"]?.let { GPS.matchEntire(it) } ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        return runCatching { GeoPoint(lat, lon) }.getOrNull()
    }

    /** One explicit request made only when the user asks; redirects are not followed. */
    suspend fun fetch(receiver: Receiver, client: OkHttpClient = defaultClient): Map<String, String> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder()
                .url(receiver.baseUrl.newBuilder().encodedPath("/status").build()).build())
            call.timeout().timeout(5, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val fields = response.use {
                            if (!it.isSuccessful) throw IOException("/status returned HTTP ${it.code}; redirects are not followed")
                            val source = (it.body ?: throw IOException("Empty /status response")).source()
                            source.request(MAX_BYTES + 1L)
                            if (source.buffer.size > MAX_BYTES) throw ProtocolException("Oversized /status response")
                            parse(source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(fields)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    private val defaultClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).build()
    }
}
