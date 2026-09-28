package org.kiwidroid.core

/** Local playback counters, independent of missing SND sequence numbers. */
data class PlaybackDiagnostics(
    val radioUnderruns: Long = 0,
    val deviceUnderruns: Int = 0,
    val droppedAudioMs: Long = 0,
    val queuedAudioMs: Int = 0,
    val targetAudioMs: Int = 0,
    val deviceBufferMs: Int = 0,
    val buffering: Boolean = false,
)

/**
 * A device may accept a whole AudioTrack buffer in a burst rather than one block per wakeup.
 * Prime for that burst PLUS network jitter. Only an actually short read is an underrun;
 * an exact drain is not a reason to mute and restart the fade.
 */
class RadioJitterBuffer(
    private val sampleRate: Int,
    deviceBufferFrames: Int,
) : AutoCloseable {
    private val lock = Any()
    private val initialTarget: Int
    private val maximumTarget: Int
    private val queue: PcmBuffer
    private var target: Int
    private var ready = false
    private var enabled = true
    private var closed = false
    private var fadePosition = 0
    private var underruns = 0L
    private val fadeFrames = sampleRate / 20 // 50 ms on first play / actual rebuffer only

    init {
        require(sampleRate in 8000..192000)
        require(deviceBufferFrames in 0..sampleRate * 10)
        initialTarget = maxOf(sampleRate * 150 / 1000, deviceBufferFrames + sampleRate / 10)
        maximumTarget = maxOf(sampleRate, initialTarget) // adapt up to 1 s, or the device's larger minimum
        target = initialTarget
        queue = PcmBuffer(maximumTarget + maxOf(deviceBufferFrames, sampleRate / 5))
    }

    fun offer(samples: ShortArray, radioEnabled: Boolean = true) = synchronized(lock) {
        setEnabled(radioEnabled)
        if (!closed && enabled) queue.offer(samples)
    }

    fun render(count: Int, radioEnabled: Boolean = true): ShortArray = synchronized(lock) {
        require(count in 1..initialTarget)
        setEnabled(radioEnabled)
        if (closed || !enabled) return@synchronized ShortArray(count)
        if (!ready) {
            if (queue.queuedSamples() < target) return@synchronized ShortArray(count)
            ready = true
            fadePosition = 0
        }
        val available = minOf(count, queue.queuedSamples())
        val pcm = queue.read(count)
        for (index in 0 until available) {
            if (fadePosition >= fadeFrames) break
            pcm[index] = (pcm[index] * (fadePosition++ / fadeFrames.toDouble())).toInt().toShort()
        }
        if (available < count) {
            underruns++
            ready = false
            target = minOf(maximumTarget, target + sampleRate / 20) // +50 ms after a real starvation
            // Soften the partial tail rather than abruptly chopping it to silence.
            val tail = minOf(available, sampleRate / 200)
            for (index in 0 until tail) {
                val pos = available - tail + index
                pcm[pos] = (pcm[pos] * (tail - 1 - index) / tail.toDouble()).toInt().toShort()
            }
        }
        pcm
    }

    fun diagnostics(): PlaybackDiagnostics = synchronized(lock) {
        PlaybackDiagnostics(
            radioUnderruns = underruns,
            droppedAudioMs = queue.droppedSamples * 1000 / sampleRate,
            queuedAudioMs = queue.queuedSamples() * 1000 / sampleRate,
            targetAudioMs = target * 1000 / sampleRate,
            buffering = enabled && !ready && !closed,
        )
    }

    private fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        queue.clear()
        ready = false
        fadePosition = 0
        // Keep the learned jitter target when toggling mute, but never keep old PCM.
    }

    override fun close() = synchronized(lock) {
        closed = true
        queue.close()
    }
}
