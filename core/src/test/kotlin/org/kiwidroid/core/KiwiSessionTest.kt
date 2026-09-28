package org.kiwidroid.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString

class KiwiSessionTest {
    private class FakeSink : AudioSink {
        var rate: Double? = null
        var closed = false
        var rssi: Double? = null
        val samples = CopyOnWriteArrayList<Short>()
        override fun start(sourceRate: Double) { rate = sourceRate }
        val channelCounts = CopyOnWriteArrayList<Int>()
        override fun offer(samples: ShortArray, channels: Int) {
            channelCounts += channels
            this.samples.addAll(samples.toList())
        }
        override fun observeRssi(rssiDbm: Double) { rssi = rssiDbm }
        override fun playbackDiagnostics() = PlaybackDiagnostics(radioUnderruns = 3, deviceUnderruns = 2, targetAudioMs = 300)
        override fun close() { closed = true }
    }

    @Test fun `mock receiver bootstraps streams retunes and closes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("""{"ts":9007199254740993}""", 7))
            val commands = CopyOnWriteArrayList<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    commands.add(text)
                    if (text.startsWith("SET auth")) {
                        webSocket.send("MSG sample_rate=11998.75".toByteArray().toByteString())
                        webSocket.send("MSG badp=0 audio_rate=12000")
                    }
                    if (text == "SET compression=0") webSocket.send(sndPacket().toByteString())
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }))
            val tuning = MutableStateFlow(Tuning())
            val sink = FakeSink()
            val received = CompletableDeferred<SessionMetrics>()
            val job = launch {
                KiwiSession().run(Receiver("Mock", server.url("/").toString()), tuning, sink) {
                    received.complete(it)
                }
            }
            try {
                val metrics = withTimeout(5000) { received.await() }
                assertEquals(-42.0, metrics.rssiDbm)
                assertEquals(-42.0, sink.rssi)
                assertEquals(3, metrics.playback?.radioUnderruns)
                assertEquals(2, metrics.playback?.deviceUnderruns)
                assertEquals(300, metrics.playback?.targetAudioMs)
                assertEquals(11998.75, sink.rate)
                assertContentEquals(shortArrayOf(-32768, -1, 0, 1, 32767), sink.samples.toShortArray())
                assertEquals("/VER", server.takeRequest().path)
                assertEquals("/ws/kiwi/9007199254740993/SND", server.takeRequest().path)
                tuning.value = Tuning(335000, Mode.CW)
                withTimeout(2000) {
                    while (tuning.value.command() !in commands) delay(20)
                }
                assertEquals("SET auth t=kiwi p=", commands.first())
                assertTrue(commands.indexOf("SET AR OK in=12000 out=48000") < commands.indexOf("SET compression=0"))
            } finally { job.cancelAndJoin() }
            assertTrue(sink.closed)
        }
    }

    @Test fun `live mode and passband changes accept mono stereo IQ and mono without reconnecting`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ts":123}"""))
            val commands = CopyOnWriteArrayList<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                var seq = 0L
                var ready = false
                override fun onMessage(webSocket: WebSocket, text: String) {
                    commands += text
                    if (text.startsWith("SET auth")) webSocket.send("MSG badp=0 audio_rate=12000 sample_rate=12000")
                    if (text == "SET compression=0") {
                        ready = true
                        webSocket.send(sndPacket(seq = ++seq).toByteString())
                    } else if (ready && text.startsWith("SET mod=")) {
                        val stereo = text.startsWith("SET mod=sas ") || text.startsWith("SET mod=iq ")
                        webSocket.send((if (stereo) stereoPacket(seq = ++seq) else sndPacket(seq = ++seq)).toByteString())
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }))
            val tuning = MutableStateFlow(Tuning())
            val sink = FakeSink()
            val lastMetrics = MutableStateFlow<SessionMetrics?>(null)
            val job = launch {
                KiwiSession().run(Receiver("Mock", server.url("/").toString()), tuning, sink) { lastMetrics.value = it }
            }
            try {
                withTimeout(5000) { while (sink.channelCounts.isEmpty()) delay(10) }
                for ((index, mode) in listOf(Mode.SAS, Mode.IQ, Mode.CWN, Mode.SAM, Mode.SAU, Mode.SAL, Mode.AM).withIndex()) {
                    tuning.value = tuning.value.copy(mode = mode)
                    withTimeout(2000) { while (sink.channelCounts.size < index + 2) delay(10) }
                    assertEquals(mode.channels, sink.channelCounts.last())
                    assertTrue(tuning.value.command() in commands)
                }
                tuning.value = tuning.value.withPassband(Passband(-2500, 2500))
                withTimeout(2000) { while (tuning.value.command() !in commands) delay(10) }
                withTimeout(2000) { while (lastMetrics.value == null) delay(10) }
                assertEquals(0, lastMetrics.value!!.missingFrames)
                assertEquals(2, server.requestCount)
                assertEquals(1, commands.count { it.startsWith("SET auth") })
                assertEquals(12000.0, sink.rate)
            } finally { job.cancelAndJoin() }
            assertTrue(sink.closed)
        }
    }

    @Test fun `busy receiver returns actionable error and closes sink`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ts":123}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("MSG too_busy=4") }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }))
            val sink = FakeSink()
            val error = assertFailsWith<ProtocolException> {
                withTimeout(5000) {
                    KiwiSession().run(Receiver("Mock", server.url("/").toString()), MutableStateFlow(Tuning()), sink) {}
                }
            }
            assertTrue(error.message!!.contains("busy"))
            assertTrue(sink.closed)
            assertNull(sink.rate)
        }
    }

    @Test fun `HTTP redirect is not followed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://example.invalid/VER"))
            val sink = FakeSink()
            val error = assertFailsWith<java.io.IOException> {
                KiwiSession().run(Receiver("Mock", server.url("/").toString()), MutableStateFlow(Tuning()), sink) {}
            }
            assertTrue(error.message!!.contains("302"))
            assertTrue(sink.closed)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `cancelling bootstrap closes sink without waiting for HTTP timeout`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val sink = FakeSink()
            val job = launch {
                KiwiSession().run(Receiver("Mock", server.url("/").toString()), MutableStateFlow(Tuning()), sink) {}
            }
            delay(100)
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(sink.closed)
        }
    }
}
