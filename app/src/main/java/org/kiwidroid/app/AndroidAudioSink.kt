package org.kiwidroid.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.kiwidroid.core.*

/** AudioTrack and queue have fixed bounds; slow output can never block WebSocket delivery. */
class AndroidAudioSink : AudioSink {
    private val queue = PcmBuffer(Protocol.OUTPUT_RATE / 5) // at most 200 ms queued
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
            .setBufferSizeInBytes(maxOf(minBuffer, Protocol.OUTPUT_RATE / 10 * 2)) // >=100 ms
            .build()
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            throw IOException("AudioTrack initialization failed")
        }
        track = output
        worker = thread(name = "kiwi-audio", isDaemon = true) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                var priming = true
                var fadeSamples = 0
                var underruns = output.underrunCount
                while (!closed) {
                    val samples = queue.take(if (priming) Protocol.OUTPUT_RATE / 10 else 1) ?: break
                    if (closed) break
                    // A short fade prevents the initial arbitrary PCM boundary becoming a click.
                    for (index in samples.indices) {
                        if (fadeSamples >= 2400) break
                        samples[index] = (samples[index] * (fadeSamples++ / 2400.0)).toInt().toShort()
                    }
                    if (priming) {
                        output.play()
                        priming = false
                    }
                    var offset = 0
                    while (offset < samples.size && !closed) {
                        val written = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0 && !closed) throw IOException("AudioTrack write failed ($written)")
                        if (written > 0) offset += written
                    }
                    val currentUnderruns = output.underrunCount
                    if (currentUnderruns > underruns && queue.queuedSamples() == 0) {
                        output.pause()
                        output.flush()
                        priming = true
                        fadeSamples = 0
                    }
                    underruns = currentUnderruns
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

    override fun offer(samples: ShortArray) {
        checkHealth()
        queue.offer(checkNotNull(resampler).convert(samples))
    }

    override fun checkHealth() {
        failure.get()?.let { throw IOException("Audio playback failed: ${it.message}", it) }
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.close()
        // Pause unblocks a blocking write; the writer alone releases its AudioTrack.
        runCatching { track?.pause() }
        worker?.join(1000)
        track = null
    }
}
