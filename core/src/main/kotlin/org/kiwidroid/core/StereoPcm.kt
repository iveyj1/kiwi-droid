package org.kiwidroid.core

/** Independent, phase-matched channel resamplers; mono is duplicated, never averaged with Q. */
class StereoPcmResampler(private val sourceRate: Double) {
    var inputChannels: Int = 0
        private set
    private var left = PcmResampler(sourceRate)
    private var right = PcmResampler(sourceRate)

    fun convert(samples: ShortArray, channels: Int): ShortArray {
        require(channels == 1 || channels == 2) { "Only mono/stereo PCM is supported" }
        require(samples.size % channels == 0) { "Incomplete input sample frame" }
        if (inputChannels != channels) {
            left = PcmResampler(sourceRate)
            right = PcmResampler(sourceRate)
            inputChannels = channels
        }
        val l: ShortArray
        val r: ShortArray
        if (channels == 1) {
            l = left.convert(samples)
            r = l
        } else {
            l = left.convert(ShortArray(samples.size / 2) { samples[it * 2] })
            r = right.convert(ShortArray(samples.size / 2) { samples[it * 2 + 1] })
        }
        check(l.size == r.size) { "Channel resampler phases diverged" }
        return ShortArray(l.size * 2) { if (it % 2 == 0) l[it / 2] else r[it / 2] }
    }
}

/** Mono RSSI tones are centered; receiver left/right (or I/Q) remain distinct. */
fun mixStereoSound(radio: ShortArray, tones: ShortArray, settings: SoundSettings): ShortArray {
    require(radio.size == tones.size * 2)
    val centeredTones = ShortArray(radio.size) { tones[it / 2] }
    return mixSound(radio, centeredTones, settings)
}
