package org.kiwidroid.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
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
import org.kiwidroid.core.SoundSettings
import org.kiwidroid.core.RssiToneSettings

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
        val channels = AtomicInteger(1)
        val modes = CopyOnWriteArrayList<String>()
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
                    if (text.startsWith("SET mod=")) {
                        val mode = text.substringAfter("SET mod=").substringBefore(' ')
                        channels.set(if (mode == "sas" || mode == "iq") 2 else 1)
                        modes.add(mode)
                    }
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
                    val channelCount = channels.get()
                    val bytes = ByteBuffer.allocate((if (channelCount == 2) 20 else 10) + 512 * 2 * channelCount).apply {
                        put("SND".toByteArray()); put((if (channelCount == 2) 8 else 0).toByte())
                        order(ByteOrder.LITTLE_ENDIAN); putInt(seq)
                        // One-frame raw RSSI excursions exercise the dip/peak receive path.
                        val smeter = when (seq % 40) { 10 -> 650; 20 -> 1050; else -> 850 + seq % 10 }
                        order(ByteOrder.BIG_ENDIAN); putShort(smeter.toShort())
                        if (channelCount == 2) {
                            put(0.toByte()); put(0.toByte())
                            order(ByteOrder.LITTLE_ENDIAN); putInt(1234); putInt(123456789)
                        }
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
                    // Exercise tone-only routing without audible test beeps on the user's phone.
                    radio.setSound(SoundSettings(radioEnabled = false, tones = RssiToneSettings(enabled = true, volumePercent = 0)))
                    radio.play()
                }
                await { radio.state.value.status == "Playing" }
                assertFalse(radio.state.value.settings.sound.radioEnabled)
                assertTrue(radio.state.value.settings.sound.tones.enabled)
                assertNotNull(radio.state.value.metrics?.rssiDbm)
                instrumentation.runOnMainSync {
                    radio.setRadioAudio(true)
                    radio.setRadioAudio(false)
                    radio.setRssiTones(false)
                    radio.setRssiTones(true)
                }
                for (mode in listOf(Mode.SAS, Mode.IQ, Mode.CWN, Mode.SAM, Mode.SAU, Mode.SAL, Mode.IQ)) {
                    val beforeCommands = modes.size
                    instrumentation.runOnMainSync { radio.setMode(mode) }
                    await { modes.size > beforeCommands && modes.last() == mode.wireName && radio.state.value.metrics?.channels == mode.channels }
                }
                // Competing focus owners simulate notification ducking and longer interruptions.
                val audioManager = context.getSystemService(AudioManager::class.java)
                for (gain in listOf(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)) {
                    val interruption = AudioFocusRequest.Builder(gain)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                        .setOnAudioFocusChangeListener { }.build()
                    try {
                        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audioManager.requestAudioFocus(interruption))
                        await { radio.state.value.metrics?.playback?.focusSilenced == true }
                        assertTrue(radio.state.value.running)
                        assertEquals(2, server.requestCount)
                    } finally { audioManager.abandonAudioFocusRequest(interruption) }
                    await { radio.state.value.metrics?.playback?.focusSilenced == false }
                    assertTrue(radio.state.value.running)
                }
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
                    radio.setSound(original.sound)
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
