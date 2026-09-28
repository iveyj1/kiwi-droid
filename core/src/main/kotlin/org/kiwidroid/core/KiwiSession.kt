package org.kiwidroid.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

interface AudioSink : AutoCloseable {
    fun start(sourceRate: Double)
    /** Must not block the transport; the implementation owns a bounded playback queue. */
    fun offer(samples: ShortArray)
    /** RSSI is independent of whether the caller chooses to hear the receiver PCM. */
    fun observeRssi(rssiDbm: Double) {}
    fun checkHealth() {}
    fun playbackDiagnostics(): PlaybackDiagnostics? = null
    override fun close()
}

data class SessionMetrics(
    val rssiDbm: Double?,
    val stale: Boolean,
    val missingFrames: Long,
    val adcOverflows: Long,
    val sourceRate: Double,
    val playback: PlaybackDiagnostics? = null,
)

/** One run == one connection. Caller cancels and joins it before switching receivers. */
class KiwiSession(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS).build(),
) {
    private sealed interface Event {
        data object Open : Event
        data class Message(val bytes: ByteArray) : Event
    }

    suspend fun run(
        receiver: Receiver,
        tuning: StateFlow<Tuning>,
        sink: AudioSink,
        onMetrics: (SessionMetrics) -> Unit,
    ): Unit = coroutineScope {
        var socket: WebSocket? = null
        val events = Channel<Event>(64)
        val ticks = Channel<Unit>(Channel.CONFLATED)
        var timer: kotlinx.coroutines.Job? = null
        try {
            val stamp = fetchTimestamp(receiver)
            val url = receiver.baseUrl.newBuilder().encodedPath("/ws/kiwi/$stamp/SND").build()
            fun enqueue(event: Event) {
                if (events.trySend(event).isFailure) {
                    events.close(ProtocolException("Receive queue overflow; reconnect"))
                }
            }
            socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = enqueue(Event.Open)
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > Protocol.MAX_MESSAGE_BYTES) {
                        events.close(ProtocolException("Oversized receiver message"))
                    } else enqueue(Event.Message(text.toByteArray(Charsets.UTF_8)))
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size > Protocol.MAX_MESSAGE_BYTES) {
                        events.close(ProtocolException("Oversized receiver message"))
                    } else enqueue(Event.Message(bytes.toByteArray()))
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    response?.close()
                    events.close(IOException("Connection failed: ${t.message}", t))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    events.close(IOException("Receiver closed connection ($code): $reason"))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    events.close(IOException("Receiver disconnected ($code)"))
                }
            })
            fun send(command: String) {
                if (!socket.send(command)) throw IOException("Connection is no longer writable")
            }
            val handshake = Handshake()
            val sequences = SequenceTracker()
            var sentTuning: Tuning? = null
            var started = false
            val connectedAt = monotonicMs()
            var lastAudio = connectedAt
            var lastKeepalive = connectedAt
            var lastRssi: Double? = null
            var overflowCount = 0L
            timer = launch {
                while (isActive) {
                    delay(200)
                    ticks.trySend(Unit)
                }
            }
            while (isActive) {
                select<Unit> {
                    events.onReceive { event ->
                        when (event) {
                            Event.Open -> send("SET auth t=kiwi p=")
                            is Event.Message -> when (Protocol.tag(event.bytes)) {
                                "MSG" -> {
                                    val current = tuning.value
                                    val wasReady = handshake.ready
                                    handshake.accept(Protocol.msg(event.bytes), current).forEach(::send)
                                    if (handshake.ready && !wasReady) {
                                        sentTuning = current
                                        lastKeepalive = monotonicMs()
                                    }
                                }
                                "SND" -> {
                                    // Transitional packets before compression=0/setup are not playback data.
                                    if (handshake.ready) {
                                        val frame = Protocol.snd(event.bytes)
                                        if (sequences.accept(frame.sequence) && frame.samples.isNotEmpty()) {
                                            if (!started) {
                                                sink.start(checkNotNull(handshake.sampleRate))
                                                started = true
                                            }
                                            sink.observeRssi(frame.rssiDbm)
                                            sink.offer(frame.samples)
                                            lastAudio = monotonicMs()
                                            lastRssi = frame.rssiDbm
                                            if (frame.adcOverflow) overflowCount++
                                        }
                                    }
                                }
                                // Other Kiwi metadata tags do not carry MVP audio.
                            }
                        }
                    }
                    ticks.onReceive {
                        val now = monotonicMs()
                        if (!started && now - connectedAt > 10_000) {
                            throw IOException("Timed out waiting for authentication/audio setup")
                        }
                        if (started && now - lastAudio > 10_000) throw IOException("Audio stream timed out")
                        if (handshake.ready) {
                            val current = tuning.value
                            if (current != sentTuning) {
                                send(current.command())
                                sentTuning = current
                            }
                            if (now - lastKeepalive >= 30_000) {
                                send("SET keepalive")
                                lastKeepalive = now
                            }
                        }
                        if (started) {
                            sink.checkHealth()
                            onMetrics(SessionMetrics(lastRssi, now - lastAudio >= 2000,
                                sequences.missing, overflowCount, checkNotNull(handshake.sampleRate), sink.playbackDiagnostics()))
                        }
                    }
                }
            }
        } finally {
            timer?.cancel()
            events.cancel()
            ticks.cancel()
            // Hard cancellation also interrupts a half-open handshake and releases the slot promptly.
            socket?.close(1000, "Stopped")
            socket?.cancel()
            sink.close()
        }
    }

    private suspend fun fetchTimestamp(receiver: Receiver): Long = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder()
            .url(receiver.baseUrl.newBuilder().encodedPath("/VER").build()).build())
        call.timeout().timeout(5, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val timestamp = response.use {
                        if (!it.isSuccessful) throw IOException("/VER returned HTTP ${it.code}; redirects are not followed")
                        val body = it.body ?: throw IOException("Empty /VER response")
                        val source = body.source()
                        source.request(Protocol.MAX_MESSAGE_BYTES + 1L)
                        if (source.buffer.size > Protocol.MAX_MESSAGE_BYTES) throw ProtocolException("Oversized /VER response")
                        Protocol.timestamp(source.readUtf8())
                    }
                    if (continuation.isActive) continuation.resume(timestamp)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}

private fun monotonicMs(): Long = System.nanoTime() / 1_000_000
