package org.kiwidroid.app

import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.kiwidroid.core.SoundSettings

/** Real AudioTrack on the current route (including Bluetooth); zero PCM is deliberately silent. */
class AudioPlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun steadyReceiverPcmHasNoLocalGapsOnCurrentAudioRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertFalse("Unlock the phone before testing audio",
            instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val sound = SoundSettings(radioEnabled = true)
        val sink = AndroidAudioSink { sound }
        val producer = Executors.newSingleThreadScheduledExecutor()
        val failure = AtomicReference<Throwable?>()
        try {
            sink.start(11999.0)
            producer.scheduleAtFixedRate({
                try {
                    sink.observeRssi(-90.0)
                    sink.offer(ShortArray(512))
                } catch (e: Exception) { failure.set(e) }
            }, 0, (512 * 1_000_000_000.0 / 11999.0).toLong(), TimeUnit.NANOSECONDS)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (sink.playbackDiagnostics()?.buffering != false && SystemClock.elapsedRealtime() < deadline) {
                sink.checkHealth()
                SystemClock.sleep(20)
            }
            assertFalse("Radio never primed", sink.playbackDiagnostics()!!.buffering)
            val start = sink.playbackDiagnostics()!!
            val until = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < until) {
                sink.checkHealth()
                failure.get()?.let { throw AssertionError("Producer failed", it) }
                SystemClock.sleep(100)
            }
            val end = sink.playbackDiagnostics()!!
            instrumentation.sendStatus(2, Bundle().apply { putString("audio_diagnostics", end.toString()) })
            assertEquals("Local radio starvation: $end", 0L, end.radioUnderruns)
            assertEquals("Audio backlog discarded: $end", 0L, end.droppedAudioMs)
            assertEquals("Device underruns grew: $start -> $end", start.deviceUnderruns, end.deviceUnderruns)
            assertTrue(end.targetAudioMs >= end.deviceBufferMs + 99)
        } finally {
            producer.shutdownNow()
            producer.awaitTermination(2, TimeUnit.SECONDS)
            sink.close()
        }
    }
}
