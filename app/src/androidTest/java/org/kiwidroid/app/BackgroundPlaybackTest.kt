package org.kiwidroid.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.PowerManager
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
import org.kiwidroid.core.Mode
import org.kiwidroid.core.formatKhz

/** Loopback receiver + real AudioTrack. No radio slots or audible test tones. */
class BackgroundPlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun screenOffKeepsStreamingAndNotificationStopReleasesSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Unlock the phone before starting the screen-off test",
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val radio = (context.applicationContext as KiwiApplication).radio
        val device = UiDevice.getInstance(instrumentation)
        await { radio.state.value.loaded }
        val original = radio.state.value.settings
        val socket = AtomicReference<WebSocket?>()
        val keepalives = AtomicInteger()
        val sequence = AtomicInteger()
        val closed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadScheduledExecutor()
        var added = false
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ts":1234567890}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.startsWith("SET auth")) webSocket.send("MSG badp=0 audio_rate=12000 sample_rate=11999.0")
                    if (text == "SET compression=0") socket.set(webSocket)
                    if (text == "SET keepalive") keepalives.incrementAndGet()
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closed.set(true)
                    webSocket.close(code, reason)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    closed.set(true)
                }
            }))
            executor.scheduleAtFixedRate({
                socket.get()?.let { ws ->
                    val seq = sequence.incrementAndGet()
                    val bytes = ByteBuffer.allocate(10 + 512 * 2).apply {
                        put("SND".toByteArray()); put(0.toByte())
                        order(ByteOrder.LITTLE_ENDIAN); putInt(seq)
                        order(ByteOrder.BIG_ENDIAN); putShort((850 + seq % 10).toShort())
                    }.array()
                    ws.send(bytes.toByteString())
                }
            }, 0, 43, TimeUnit.MILLISECONDS)
            val endpoint = server.url("/").toString() // bind the mock server off the Android main thread
            try {
                instrumentation.runOnMainSync {
                    radio.stop()
                    assertTrue(radio.saveReceiver(null, "Screen-off test", endpoint))
                    added = true
                    radio.selectReceiver(radio.state.value.settings.receivers.lastIndex)
                    radio.setMode(Mode.AM)
                    radio.tune("5000.000")
                    radio.play()
                }
                await { radio.state.value.status == "Playing" }
                val manager = context.getSystemService(NotificationManager::class.java)
                await { manager.activeNotifications.any { it.id == PlaybackService.NOTIFICATION_ID } }
                device.pressHome()
                device.sleep()
                assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
                val before = sequence.get()
                SystemClock.sleep(33_000) // includes a protocol keepalive, not just buffered sound
                assertTrue("Service stopped: ${radio.state.value}", radio.state.value.running)
                assertEquals("Playing", radio.state.value.status)
                assertFalse(radio.state.value.metrics!!.stale)
                assertTrue(sequence.get() - before > 600)
                assertTrue("Expected initial and periodic keepalive", keepalives.get() >= 2)
                val notification = manager.activeNotifications.single { it.id == PlaybackService.NOTIFICATION_ID }.notification
                assertNotNull(notification.extras.getParcelable<android.media.session.MediaSession.Token>("android.mediaSession"))
                notification.actions.single { it.title == "Stop" }.actionIntent.send()
                await { !radio.state.value.running && manager.activeNotifications.none { it.id == PlaybackService.NOTIFICATION_ID } }
                await { closed.get() }
                assertNull(radio.state.value.metrics)
                assertEquals(2, server.requestCount) // /VER + one SND socket, no reconnect
            } finally {
                instrumentation.runOnMainSync {
                    radio.stop()
                    radio.setMode(Mode.AM)
                    radio.tune(formatKhz(original.tuning.frequencyHz))
                    radio.setMode(original.tuning.mode)
                    if (added) radio.removeReceiver(radio.state.value.settings.receivers.lastIndex)
                    radio.selectReceiver(original.selected)
                }
                executor.shutdownNow()
                runCatching { socket.get()?.close(1000, "Test complete") }
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Condition timed out", condition())
    }
}
