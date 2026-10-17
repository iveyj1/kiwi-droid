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
class AndroidAudioSink(
    private val focusAllowed: () -> Boolean = { true },
    private val externalRender: ((Int) -> ShortArray)? = null,
    private val settings: () -> SoundSettings = { SoundSettings() },
) : AudioSink {
    var outputBufferFrames: Int = 0
        private set
    private var radioBuffer: RadioJitterBuffer? = null
    @Volatile private var focusSilenced = false
    @Volatile private var deviceUnderruns = 0
    @Volatile private var deviceBufferMs = 0
    private val tones = RssiToneSynth()
    private var resampler: StereoPcmResampler? = null
    private var worker: Thread? = null
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var closed = false

    override fun start(sourceRate: Double) {
        check(worker == null && !closed)
        resampler = StereoPcmResampler(sourceRate)
        val minBuffer = AudioTrack.getMinBufferSize(Protocol.OUTPUT_RATE,
            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IOException("48 kHz stereo audio is unavailable ($minBuffer)")
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(Protocol.OUTPUT_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, Protocol.OUTPUT_RATE / 5 * 4)) // request >=200 ms; routes may enlarge it
            .build()
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            throw IOException("AudioTrack initialization failed")
        }
        val actualFrames = output.bufferSizeInFrames
        outputBufferFrames = actualFrames
        deviceBufferMs = actualFrames * 1000 / Protocol.OUTPUT_RATE
        val buffer = RadioJitterBuffer(Protocol.OUTPUT_RATE, actualFrames, channels = 2)
        radioBuffer = buffer
        track = output
        worker = thread(name = "kiwi-audio", isDaemon = true) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val blockSize = Protocol.OUTPUT_RATE / 100 // 10 ms; AudioTrack writes pace this loop
                // Prime the native ring with silence before play(), avoiding startup hardware underruns.
                val silence = ShortArray(actualFrames * 2)
                val primed = output.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                if (primed < 0) throw IOException("AudioTrack priming failed ($primed)")
                var audible = focusAllowed()
                output.setVolume(if (audible) 1f else 0f)
                focusSilenced = !audible
                output.play()
                while (!closed) {
                    val allowed = focusAllowed()
                    if (allowed != audible) {
                        // Hardware volume also silences PCM already queued in the native ring.
                        output.setVolume(if (allowed) 1f else 0f)
                        audible = allowed
                        focusSilenced = !allowed
                    }
                    // Consume live PCM while silenced: no stale backlog or reconnect on focus gain.
                    val sound = settings()
                    val samples = externalRender?.invoke(blockSize) ?: run {
                        val radioPcm = buffer.render(blockSize, sound.radioEnabled)
                        val tonePcm = tones.render(blockSize, sound.tones, monotonicMs())
                        mixStereoSound(radioPcm, tonePcm, sound)
                    }
                    var offset = 0
                    while (offset < samples.size && !closed) {
                        val written = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0 && !closed) throw IOException("AudioTrack write failed ($written)")
                        if (written > 0) offset += written
                    }
                    deviceUnderruns = output.underrunCount
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
        tones.observe(RssiReading(rssiDbm, monotonicMs()), settings().tones)
    }

    override fun offer(samples: ShortArray, channels: Int) {
        checkHealth()
        val converter = checkNotNull(resampler)
        val buffer = checkNotNull(radioBuffer)
        val layoutChanged = converter.inputChannels != channels
        // Preserve phase while muted; reset both channels together on a wire-format transition.
        val pcm = converter.convert(samples, channels)
        if (layoutChanged) buffer.clear()
        buffer.offer(pcm, settings().radioEnabled)
    }

    override fun playbackDiagnostics(): PlaybackDiagnostics? = radioBuffer?.diagnostics()?.copy(
        deviceUnderruns = deviceUnderruns,
        deviceBufferMs = deviceBufferMs,
        focusSilenced = focusSilenced,
    )

    override fun checkHealth() {
        failure.get()?.let { throw IOException("Audio playback failed: ${it.message}", it) }
    }

    override fun close() {
        if (closed) return
        closed = true
        radioBuffer?.close()
        runCatching { track?.pause() }
        worker?.join(1000)
        track = null
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000
}
