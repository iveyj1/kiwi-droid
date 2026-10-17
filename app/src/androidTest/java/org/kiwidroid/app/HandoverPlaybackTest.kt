package org.kiwidroid.app

import android.app.KeyguardManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.kiwidroid.core.*

/** Two silent local receivers, real shared AudioTrack, no live receiver slots. */
class HandoverPlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private class ReceiverStub : AutoCloseable {
        val server = MockWebServer()
        val audio = AtomicBoolean(true)
        val closed = AtomicBoolean(false)
        val socket = AtomicReference<WebSocket?>()
        val seq = AtomicInteger()
        private val executor = Executors.newSingleThreadScheduledExecutor()
        init {
            server.enqueue(MockResponse().setBody("""{"ts":123}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    if (text.startsWith("SET auth")) ws.send("MSG badp=0 audio_rate=12000 sample_rate=12000")
                    if (text == "SET compression=0") socket.set(ws)
                }
                override fun onClosing(ws: WebSocket, code: Int, reason: String) { closed.set(true); ws.close(code, reason) }
                override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) { closed.set(true) }
            }))
            executor.scheduleAtFixedRate({
                if (audio.get()) socket.get()?.send(ByteBuffer.allocate(10 + 480 * 2).apply {
                    put("SND".toByteArray()); put(0.toByte())
                    order(ByteOrder.LITTLE_ENDIAN); putInt(seq.incrementAndGet())
                    order(ByteOrder.BIG_ENDIAN); putShort(850.toShort())
                }.array().toByteString())
            }, 0, 40, TimeUnit.MILLISECONDS)
        }
        override fun close() { executor.shutdownNow(); runCatching { socket.get()?.close(1000, "Done") }; server.close() }
    }

    @Test fun transferRetainsAudioAndSwitchbackReusesConnectionUntilRetuning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Unlock phone first", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val radio = (context.applicationContext as KiwiApplication).radio
        await { radio.state.value.loaded }
        val original = radio.state.value.settings
        var added = 0
        ReceiverStub().use { a -> ReceiverStub().use { b ->
            val urlA = a.server.url("/").toString()
            val urlB = b.server.url("/").toString()
            try {
                instrumentation.runOnMainSync {
                    radio.stop()
                    assertTrue(radio.saveReceiver(null, "Handover A", urlA)); added++
                    assertTrue(radio.saveReceiver(null, "Handover B", urlB)); added++
                    radio.selectReceiver(original.receivers.size)
                    radio.setMode(Mode.AM); radio.tune("950")
                    radio.setKeepReceiverSeconds(60)
                    radio.setSound(SoundSettings(radioEnabled = true, tones = RssiToneSettings(enabled = true, volumePercent = 0)))
                    radio.play()
                }
                await { radio.state.value.status == "Playing" }
                SystemClock.sleep(1000)
                val baseline = radio.state.value.metrics!!.playback!!.deviceUnderruns
                b.audio.set(false)
                instrumentation.runOnMainSync { radio.selectReceiver(original.receivers.size + 1) }
                await { b.socket.get() != null }
                SystemClock.sleep(400)
                assertEquals("Handover A", radio.state.value.audibleReceiver!!.name)
                assertEquals(urlA, radio.draftLog().receiverUrl)
                assertFalse(a.closed.get())
                b.audio.set(true)
                await { radio.state.value.audibleReceiver?.name == "Handover B" }
                assertEquals("Handover A", radio.state.value.retainedReceiver!!.name)
                UiDevice.getInstance(instrumentation).pressHome()
                SystemClock.sleep(15_000)
                assertEquals(baseline, radio.state.value.metrics!!.playback!!.deviceUnderruns)
                instrumentation.runOnMainSync { radio.selectReceiver(original.receivers.size) }
                await { radio.state.value.audibleReceiver?.name == "Handover A" }
                assertEquals(2, a.server.requestCount)
                assertEquals(2, b.server.requestCount)
                assertFalse(b.closed.get())
                instrumentation.runOnMainSync { radio.tune("960") }
                await { b.closed.get() && radio.state.value.retainedReceiver == null }
                assertTrue(radio.state.value.running)
                instrumentation.runOnMainSync { radio.stop() }
                await { a.closed.get() }
            } finally {
                instrumentation.runOnMainSync {
                    radio.stop()
                    repeat(added) { radio.removeReceiver(radio.state.value.settings.receivers.lastIndex) }
                    radio.selectReceiver(original.selected)
                    radio.setMode(Mode.AM); radio.tune(formatKhz(original.tuning.frequencyHz)); radio.setMode(original.tuning.mode)
                    radio.setKeepReceiverSeconds(original.keepReceiverSeconds)
                    radio.setSound(original.sound)
                }
            }
        } }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Timed out", condition())
    }
}
