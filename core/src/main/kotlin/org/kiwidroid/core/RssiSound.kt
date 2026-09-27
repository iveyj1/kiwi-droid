package org.kiwidroid.core

import java.util.Locale
import kotlin.math.*

enum class MusicalScale(val label: String, val offsets: List<Int>) {
    CHROMATIC("Chromatic (12 notes)", (0..11).toList()),
    MAJOR("Major", listOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("Natural minor", listOf(0, 2, 3, 5, 7, 8, 10)),
    PENTATONIC("Major pentatonic", listOf(0, 2, 4, 7, 9)),
    CUSTOM("Custom scale", emptyList()),
    CONTINUOUS("Continuous pitch", emptyList()),
}

enum class ToneWaveform { SINE, TRIANGLE }

val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
fun noteName(midi: Int): String = "${NOTE_NAMES[Math.floorMod(midi, 12)]}${Math.floorDiv(midi, 12) - 1}"
fun noteFrequency(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)

fun parseNote(text: String): Int {
    val value = text.trim()
    val midi = value.toIntOrNull() ?: run {
        val match = Regex("([A-Ga-g])([#b]?)(-?[0-9]+)").matchEntire(value)
        require(match != null) { "Use notes such as C4, F#4, Bb3, or MIDI numbers" }
        val natural = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)
            .getValue(match.groupValues[1].uppercase(Locale.ROOT))
        val accidental = when (match.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 }
        val octave = match.groupValues[3].toIntOrNull()
        require(octave != null && octave in 1..8) { "Notes must be C2–C8 (MIDI 36–108)" }
        (octave + 1) * 12 + natural + accidental
    }
    require(midi in 36..108) { "Notes must be C2–C8 (MIDI 36–108)" }
    return midi
}

data class PitchPoint(val dbm: Double, val midi: Int) {
    init {
        require(dbm.isFinite() && dbm in -180.0..30.0) { "Mapping RSSI must be −180 to +30 dBm" }
        require(midi in 36..108) { "Mapping notes must be C2–C8 (MIDI 36–108)" }
    }
}

/** Equal-tempered mapping: every 10 dB increases pitch by exactly 12 semitones. */
fun defaultPitchPoints(centerDbm: Double = -90.0): List<PitchPoint> =
    listOf(-20.0 to 48, -10.0 to 60, 0.0 to 72, 10.0 to 84, 20.0 to 96)
        .map { (offset, note) -> PitchPoint(centerDbm + offset, note) }

fun formatPitchPoints(points: List<PitchPoint>): String = points.joinToString("\n") {
    "${java.math.BigDecimal.valueOf(it.dbm).stripTrailingZeros().toPlainString()}=${noteName(it.midi)}"
}

fun parsePitchPoints(text: String): List<PitchPoint> = text.lineSequence()
    .map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val parts = line.split('=')
        require(parts.size == 2) { "Enter one mapping per line, for example -90=C5" }
        val dbm = parts[0].trim().toDoubleOrNull()
        require(dbm != null) { "Invalid RSSI mapping: ${parts[0]}" }
        PitchPoint(dbm, parseNote(parts[1]))
    }.toList()

data class RssiToneSettings(
    val enabled: Boolean = false,
    val points: List<PitchPoint> = defaultPitchPoints(),
    val scale: MusicalScale = MusicalScale.CHROMATIC,
    val root: Int = 0,
    val customOffsets: List<Int> = listOf(0, 2, 4, 7, 9),
    val volumePercent: Int = 12,
    val durationMs: Int = 100,
    val intervalMs: Int = 300,
    val fadeMs: Int = 8,
    val smoothingMs: Int = 100,
    val hysteresisDb: Double = 0.15,
    val waveform: ToneWaveform = ToneWaveform.SINE,
) {
    init {
        require(points.size in 2..16) { "Use 2–16 mapping points" }
        require(points.zipWithNext().all { (a, b) -> a.dbm < b.dbm && a.midi < b.midi }) {
            "Mapping points must increase in both RSSI and note (weakest first)"
        }
        require(root in 0..11) { "Invalid scale root" }
        require(customOffsets.isNotEmpty() && customOffsets.size <= 12 &&
            customOffsets.all { it in 0..11 } && customOffsets.distinct().size == customOffsets.size) {
            "Custom scale needs unique semitone offsets from 0 to 11"
        }
        if (scale != MusicalScale.CONTINUOUS) {
            val offsets = if (scale == MusicalScale.CUSTOM) customOffsets else scale.offsets
            require((points.first().midi..points.last().midi).any { Math.floorMod(it - root, 12) in offsets }) {
                "No notes in the selected scale within the mapping range"
            }
        }
        require(volumePercent in 0..100) { "Tone volume must be 0–100%" }
        require(durationMs in 20..2000) { "Beep length must be 20–2000 ms" }
        require(intervalMs in 50..5000 && intervalMs >= durationMs + 10) {
            "Repeat interval must be 50–5000 ms and at least 10 ms longer than the beep"
        }
        require(fadeMs in 1..50 && fadeMs * 2 <= durationMs) { "Fade must be 1–50 ms and at most half the beep length" }
        require(smoothingMs in 0..2000) { "Smoothing must be 0–2000 ms" }
        require(hysteresisDb.isFinite() && hysteresisDb in 0.0..3.0) { "Hysteresis must be 0–3 dB" }
    }
}

data class SoundSettings(
    val radioEnabled: Boolean = true,
    val radioVolumePercent: Int = 100,
    val tones: RssiToneSettings = RssiToneSettings(),
) {
    init { require(radioVolumePercent in 0..100) { "Receiver volume must be 0–100%" } }
}

/** Linear interpolation in semitones (log frequency), clamped at the mapping endpoints. */
class RssiPitchMapping(private val settings: RssiToneSettings) {
    private val points = settings.points
    private val offsets = if (settings.scale == MusicalScale.CUSTOM) settings.customOffsets else settings.scale.offsets
    private val notes = (points.first().midi..points.last().midi).filter {
        Math.floorMod(it - settings.root, 12) in offsets
    }

    fun continuousMidi(dbm: Double): Double {
        require(dbm.isFinite())
        val bounded = dbm.coerceIn(points.first().dbm, points.last().dbm)
        val index = points.indexOfFirst { it.dbm >= bounded }.coerceAtLeast(1)
        val left = points[index - 1]
        val right = points[index]
        return left.midi + (right.midi - left.midi) * (bounded - left.dbm) / (right.dbm - left.dbm)
    }

    fun dbmAtMidi(midi: Double): Double {
        val bounded = midi.coerceIn(points.first().midi.toDouble(), points.last().midi.toDouble())
        val index = points.indexOfFirst { it.midi >= bounded }.coerceAtLeast(1)
        val left = points[index - 1]
        val right = points[index]
        return left.dbm + (right.dbm - left.dbm) * (bounded - left.midi) / (right.midi - left.midi)
    }

    fun midi(dbm: Double): Double {
        val value = continuousMidi(dbm)
        if (settings.scale == MusicalScale.CONTINUOUS) return value
        require(notes.isNotEmpty()) { "No notes in the selected scale within the mapping range" }
        return notes.minBy { abs(it - value) }.toDouble()
    }
}

data class RssiReading(val dbm: Double, val receivedAtMs: Long)

/** Solely audio-thread owned. Latest readings may be published atomically by the transport. */
class RssiToneSynth(private val sampleRate: Int = Protocol.OUTPUT_RATE) {
    private var settings: RssiToneSettings? = null
    private var mapping: RssiPitchMapping? = null
    private var previousReading: RssiReading? = null
    private var smoothed: Double? = null
    private var targetMidi: Double? = null
    internal val currentMidi: Double? get() = targetMidi
    private var cycleSample = 0
    private var phase = 0.0
    private var beepFrequency = 0.0

    init { require(sampleRate >= 8000) }

    fun render(count: Int, config: RssiToneSettings, reading: RssiReading?, nowMs: Long): ShortArray {
        require(count >= 0)
        val output = ShortArray(count)
        if (settings != config) {
            reset()
            settings = config
            mapping = RssiPitchMapping(config)
        }
        if (!config.enabled || reading == null || !reading.dbm.isFinite() ||
            nowMs - reading.receivedAtMs !in 0 until STALE_MS) {
            reset()
            return output
        }
        val map = checkNotNull(mapping)
        if (reading != previousReading) {
            val previous = previousReading
            val old = smoothed
            smoothed = if (old == null || previous == null || config.smoothingMs == 0) reading.dbm else {
                val dt = (reading.receivedAtMs - previous.receivedAtMs).coerceAtLeast(0)
                val alpha = 1.0 - exp(-dt.toDouble() / config.smoothingMs)
                old + alpha * (reading.dbm - old)
            }
            previousReading = reading
            val filtered = checkNotNull(smoothed)
            val candidate = map.midi(filtered)
            val oldNote = targetMidi
            targetMidi = when {
                oldNote == null || config.scale == MusicalScale.CONTINUOUS -> candidate
                candidate > oldNote && filtered >= map.dbmAtMidi((oldNote + candidate) / 2) + config.hysteresisDb -> candidate
                candidate < oldNote && filtered <= map.dbmAtMidi((oldNote + candidate) / 2) - config.hysteresisDb -> candidate
                else -> oldNote
            }
        }
        val interval = sampleRate * config.intervalMs / 1000
        val duration = sampleRate * config.durationMs / 1000
        val fade = sampleRate * config.fadeMs / 1000
        for (index in output.indices) {
            if (cycleSample == 0) {
                beepFrequency = noteFrequency(checkNotNull(targetMidi))
                phase = 0.0
            }
            if (cycleSample < duration) {
                val envelope = minOf(1.0, cycleSample.toDouble() / fade, (duration - 1 - cycleSample).toDouble() / fade)
                val wave = when (config.waveform) {
                    ToneWaveform.SINE -> sin(phase)
                    ToneWaveform.TRIANGLE -> 4 * abs(((phase / (2 * PI) + 0.25) % 1) - 0.5) - 1
                }
                output[index] = (wave * envelope * config.volumePercent / 100.0 * Short.MAX_VALUE).roundToInt().toShort()
                phase = (phase + 2 * PI * beepFrequency / sampleRate) % (2 * PI)
            }
            cycleSample = (cycleSample + 1) % interval
        }
        return output
    }

    private fun reset() {
        previousReading = null
        smoothed = null
        targetMidi = null
        cycleSample = 0
        phase = 0.0
        beepFrequency = 0.0
    }

    companion object { const val STALE_MS = 2000L }
}

/** Apply independent gains and saturate instead of overflowing signed PCM when both are on. */
fun mixSound(radio: ShortArray, tones: ShortArray, settings: SoundSettings): ShortArray {
    require(radio.size == tones.size)
    val gain = if (settings.radioEnabled) settings.radioVolumePercent / 100.0 else 0.0
    return ShortArray(radio.size) { index ->
        (radio[index] * gain + tones[index]).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
