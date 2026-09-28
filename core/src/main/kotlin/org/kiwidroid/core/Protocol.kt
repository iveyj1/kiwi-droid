package org.kiwidroid.core

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class ProtocolException(message: String) : Exception(message)

data class SndGps(val lastSolution: Int, val seconds: Long, val nanoseconds: Long)

data class SndFrame(val flags: Int, val sequence: Long, val rssiDbm: Double, val samples: ShortArray,
                    val channels: Int = 1, val gps: SndGps? = null) {
    val adcOverflow: Boolean get() = flags and 0x02 != 0
    val sampleFrames: Int get() = samples.size / channels
}

object Protocol {
    const val OUTPUT_RATE = 48_000
    const val MAX_MESSAGE_BYTES = 65_536

    fun timestamp(json: String): Long {
        val field = Json.parseToJsonElement(json).jsonObject["ts"] as? JsonPrimitive
        if (field == null || field.isString || !field.content.matches(Regex("[0-9]+"))) {
            throw ProtocolException("/VER must contain an integer ts")
        }
        return field.content.toLongOrNull() ?: throw ProtocolException("/VER timestamp exceeds 64 bits")
    }

    fun tag(bytes: ByteArray): String {
        if (bytes.size < 3 || bytes.size > MAX_MESSAGE_BYTES) throw ProtocolException("Invalid message length")
        return bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII)
    }

    fun msg(bytes: ByteArray): Map<String, String?> {
        if (tag(bytes) != "MSG") throw ProtocolException("Expected MSG")
        return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
            .trimStart(' ', '\u0000').split(Regex("\\s+")).filter { it.isNotEmpty() }.associate {
                val key = it.substringBefore('=')
                val value = if ('=' in it) {
                    // Kiwi percent escaping is not HTML form encoding: literal '+' must survive.
                    URLDecoder.decode(it.substringAfter('=').replace("+", "%2B"), "UTF-8")
                } else null
                key to value
            }
    }

    fun snd(bytes: ByteArray): SndFrame {
        if (tag(bytes) != "SND" || bytes.size < 10) throw ProtocolException("Truncated SND header")
        val flags = bytes[3].toInt() and 0xff
        if (flags and 0x10 != 0) throw ProtocolException("Compressed audio not supported")
        if (flags and 0x80 != 0) throw ProtocolException("Camping audio not supported")
        val channels = if (flags and 0x08 != 0) 2 else 1
        val pcmOffset = if (channels == 2) 20 else 10
        if (bytes.size < pcmOffset) throw ProtocolException("Truncated stereo/IQ GPS header")
        if ((bytes.size - pcmOffset) % (2 * channels) != 0) throw ProtocolException("Incomplete PCM sample frame")
        val buffer = ByteBuffer.wrap(bytes)
        val sequence = buffer.order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() and 0xffffffffL
        val smeter = buffer.order(ByteOrder.BIG_ENDIAN).getShort(8).toInt() and 0xffff
        val gps = if (channels == 2) {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            SndGps(bytes[10].toInt() and 0xff, buffer.getInt(12).toLong() and 0xffffffffL,
                buffer.getInt(16).toLong() and 0xffffffffL)
        } else null
        buffer.order(ByteOrder.BIG_ENDIAN).position(pcmOffset)
        val pcm = ShortArray((bytes.size - pcmOffset) / 2) { buffer.short }
        return SndFrame(flags, sequence, smeter * 0.1 - 127.0, pcm, channels, gps)
    }

    fun serverError(params: Map<String, String?>): String? = when {
        "too_busy" in params -> "Receiver busy: all ${params["too_busy"]} slots occupied"
        "badp" in params && params["badp"] != "0" ->
            if (params["badp"] == "1") "Password required or no-password slots busy"
            else "Receiver rejected authentication/policy (badp=${params["badp"]})"
        "down" in params -> "Receiver is down"
        "redirect" in params -> "Receiver redirects to ${params["redirect"]}; select it explicitly"
        else -> null
    }

    fun setup(tuning: Tuning): List<String> = listOf(
        "SET squelch=0 max=0", "SET genattn=0", "SET gen=0 mix=-1",
        "SET ident_user=kiwi-droid", tuning.command(),
        "SET agc=1 hang=0 thresh=-100 slope=6 decay=1000 manGain=50",
        "SET compression=0", "SET keepalive",
    )
}

/** Readiness depends on fields, never their arrival order. No setup before auth success. */
class Handshake {
    var sampleRate: Double? = null
        private set
    var ready = false
        private set
    private var authenticated = false
    private var audioRate: Int? = null
    private var acknowledged = false

    fun accept(params: Map<String, String?>, tuning: Tuning): List<String> {
        Protocol.serverError(params)?.let { throw ProtocolException(it) }
        if (params["badp"] == "0") authenticated = true
        if ("audio_rate" in params) {
            audioRate = params["audio_rate"]?.toIntOrNull()?.takeIf { it in 8000..96000 }
                ?: throw ProtocolException("Invalid audio_rate")
        }
        if ("sample_rate" in params) {
            val rate = params["sample_rate"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 8000.0..96000.0 }
                ?: throw ProtocolException("Invalid sample_rate")
            if (ready && rate != sampleRate) throw ProtocolException("Sample rate changed; reconnect")
            sampleRate = rate
        }
        val commands = mutableListOf<String>()
        if (authenticated && audioRate != null && !acknowledged) {
            commands += "SET AR OK in=$audioRate out=${Protocol.OUTPUT_RATE}"
            acknowledged = true
        }
        if (acknowledged && sampleRate != null && !ready) {
            commands += Protocol.setup(tuning)
            ready = true
        }
        return commands
    }
}

class SequenceTracker {
    private var expected: Long? = null
    var missing = 0L
        private set
    var outOfOrder = 0L
        private set

    /** False for duplicate/backwards packets. Forward gaps are counted modulo 2^32. */
    fun accept(sequence: Long): Boolean {
        require(sequence in 0..0xffffffffL)
        expected?.let {
            val delta = (sequence - it) and 0xffffffffL
            if (delta > 0x7fffffffL) {
                outOfOrder++
                return false
            }
            missing += delta
        }
        expected = (sequence + 1) and 0xffffffffL
        return true
    }
}
