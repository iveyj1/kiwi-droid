package org.kiwidroid.core

/** Two live inputs, one output clock. Even silent inputs are drained to keep switch-back current. */
class ReceiverMixer(private val deviceFrames: Int, private val settings: () -> SoundSettings) {
    private val inputs = mutableListOf<Input>()
    private var selected: Input? = null
    private var fading: Input? = null
    private var fadeFrame = 0
    private val fadeLength = Protocol.OUTPUT_RATE / 50 // 20 ms

    inner class Input internal constructor() : AudioSink {
        private val buffer = RadioJitterBuffer(Protocol.OUTPUT_RATE, deviceFrames, 2)
        private var converter: StereoPcmResampler? = null
        private var tones = RssiToneSynth()
        private var reading: RssiReading? = null
        @Volatile private var lastReceived = 0L
        @Volatile private var started = false
        @Volatile private var closed = false
        val ready: Boolean get() = started && !closed && now() - lastReceived < 2000 && !buffer.diagnostics().buffering
        @Synchronized override fun start(sourceRate: Double) { converter = StereoPcmResampler(sourceRate); started = true }
        @Synchronized override fun offer(samples: ShortArray, channels: Int) {
            if (closed) return
            val converter = checkNotNull(converter)
            if (converter.inputChannels != channels) buffer.clear()
            buffer.offer(converter.convert(samples, channels))
            lastReceived = now()
        }
        @Synchronized override fun observeRssi(rssiDbm: Double) {
            reading = RssiReading(rssiDbm, now())
            tones.observe(checkNotNull(reading), settings().tones)
        }
        @Synchronized internal fun radio(count: Int): ShortArray = buffer.render(count)
        @Synchronized internal fun tone(count: Int): ShortArray = tones.render(count, settings().tones, now())
        @Synchronized internal fun resetTone() {
            tones = RssiToneSynth()
            reading?.let { tones.observe(it, settings().tones) }
        }
        @Synchronized fun retuned() { reading = null; resetTone() }
        override fun playbackDiagnostics() = buffer.diagnostics().copy(deviceBufferMs = deviceFrames * 1000 / Protocol.OUTPUT_RATE)
        @Synchronized override fun close() { closed = true; buffer.close() }
    }

    @Synchronized fun createInput(): Input {
        check(inputs.size < 2) { "At most two receiver streams" }
        return Input().also { inputs += it }
    }

    val transitioning: Boolean @Synchronized get() = fading != null

    @Synchronized fun select(input: Input) {
        require(input in inputs)
        if (selected === input) return
        val reversing = fading === input
        fadeFrame = if (reversing) (fadeLength - fadeFrame).coerceIn(0, fadeLength) else 0
        if (!reversing) input.resetTone()
        fading = selected
        selected = input
    }

    @Synchronized fun remove(input: Input) {
        inputs.remove(input)
        if (selected === input) selected = null
        if (fading === input) fading = null
        input.close()
    }

    @Synchronized fun render(count: Int): ShortArray {
        val blocks = inputs.associateWith { it.radio(count) }
        val current = selected ?: return ShortArray(count * 2)
        val sound = settings()
        val pcm = mixStereoSound(checkNotNull(blocks[current]), current.tone(count), sound)
        fading?.let { old ->
            val previous = blocks[old]?.let { mixStereoSound(it, old.tone(count), sound) }
            if (previous != null) for (frame in 0 until count) {
                val gain = (fadeFrame++ / fadeLength.toDouble()).coerceAtMost(1.0)
                for (channel in 0..1) {
                    val i = frame * 2 + channel
                    pcm[i] = (previous[i] * (1 - gain) + pcm[i] * gain).toInt().toShort()
                }
            }
            if (fadeFrame >= fadeLength) fading = null
        }
        return pcm
    }

    private fun now() = System.nanoTime() / 1_000_000
}
