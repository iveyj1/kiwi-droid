package org.kiwidroid.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.kiwidroid.core.*

/** A single clocked output mixes independent radio and RSSI sounds, including in the background. */
class AndroidAudioSink(private val settings: () -> SoundSettings = { SoundSettings() }) : AudioSink {
    private val queue = PcmBuffer(Protocol.OUTPUT_RATE / 5) // at most 200 ms queued radio audio
    private val reading = AtomicReference<RssiReading?>()
    private var resampler: PcmResampler? = null
    private var worker: Thread? = null
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var closed = false

    override fun start(sourceRate: Double) {
        check(worker == null && !closed)
        resampler = PcmResampler(sourceRate)
        val minBuffer = AudioTrack.getMinBufferSize(Protocol.OUTPUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IOException("48 kHz mono audio is unavailable ($minBuffer)")
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(Protocol.OUTPUT_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, Protocol.OUTPUT_RATE / 10 * 2))
            .build()
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            throw IOException("AudioTrack initialization failed")
        }
        track = output
        worker = thread(name = "kiwi-audio", isDaemon = true) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val tones = RssiToneSynth()
                val blockSize = Protocol.OUTPUT_RATE / 100 // 10 ms; AudioTrack writes pace this loop
                var radioReady = false
                var fadeSamples = 0
                output.play()
                while (!closed) {
                    val sound = settings()
                    if (!sound.radioEnabled) {
                        queue.clear() // no old receiver audio waiting to play when unmuted
                        radioReady = false
                        fadeSamples = 0
                    }
                    if (sound.radioEnabled && !radioReady && queue.queuedSamples() >= 2400) radioReady = true
                    val radioPcm = if (radioReady) queue.read(blockSize) else ShortArray(blockSize)
                    if (radioReady) {
                        for (index in radioPcm.indices) {
                            if (fadeSamples >= 2400) break
                            radioPcm[index] = (radioPcm[index] * (fadeSamples++ / 2400.0)).toInt().toShort()
                        }
                        if (queue.queuedSamples() == 0) { radioReady = false; fadeSamples = 0 }
                    }
                    val tonePcm = tones.render(blockSize, sound.tones, reading.get(), monotonicMs())
                    val samples = mixSound(radioPcm, tonePcm, sound)
                    var offset = 0
                    while (offset < samples.size && !closed) {
                        val written = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0 && !closed) throw IOException("AudioTrack write failed ($written)")
                        if (written > 0) offset += written
                    }
                }
            } catch (e: Exception) {
                if (!closed) failure.set(e)
            } finally {
                runCatching { output.pause() }
                runCatching { output.flush() }
                output.release()
            }
        }
    }

    override fun observeRssi(rssiDbm: Double) {
        reading.set(RssiReading(rssiDbm, monotonicMs()))
    }

    override fun offer(samples: ShortArray) {
        checkHealth()
        // Preserve resampler phase even when muted; the sound switch never changes the SND session.
        val pcm = checkNotNull(resampler).convert(samples)
        if (settings().radioEnabled) queue.offer(pcm) else queue.clear()
    }

    override fun checkHealth() {
        failure.get()?.let { throw IOException("Audio playback failed: ${it.message}", it) }
    }

    override fun close() {
        if (closed) return
        closed = true
        reading.set(null)
        queue.close()
        runCatching { track?.pause() }
        worker?.join(1000)
        track = null
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000
}
