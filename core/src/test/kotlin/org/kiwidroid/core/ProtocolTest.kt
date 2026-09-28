package org.kiwidroid.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.test.*

/** Independently generated vectors; expected values checked against ~/kiwi protocol notes. */
fun sndPacket(seq: Long = 1, flags: Int = 0, smeter: Int = 850,
              samples: ShortArray = shortArrayOf(-32768, -1, 0, 1, 32767)): ByteArray =
    ByteBuffer.allocate(10 + samples.size * 2).apply {
        put("SND".toByteArray()); put(flags.toByte())
        order(ByteOrder.LITTLE_ENDIAN); putInt(seq.toInt())
        order(ByteOrder.BIG_ENDIAN); putShort(smeter.toShort())
        samples.forEach { putShort(it) }
    }.array()

fun stereoPacket(seq: Long = 1, flags: Int = 0x08,
                 samples: ShortArray = shortArrayOf(-32768, 32767, -1234, 5678, 0, -1)): ByteArray =
    ByteBuffer.allocate(20 + samples.size * 2).apply {
        put("SND".toByteArray()); put(flags.toByte())
        order(ByteOrder.LITTLE_ENDIAN); putInt(seq.toInt())
        order(ByteOrder.BIG_ENDIAN); putShort(850.toShort())
        put(3.toByte()); put(0.toByte())
        order(ByteOrder.LITTLE_ENDIAN); putInt(0xf1234567.toInt()); putInt(987654321)
        order(ByteOrder.BIG_ENDIAN); samples.forEach { putShort(it) }
    }.array()

class ProtocolTest {
    @Test fun `mixed endian PCM and RSSI match documented reference vector`() {
        val result = Protocol.snd(sndPacket(seq = 0x87654321))
        assertEquals(0x87654321, result.sequence)
        assertEquals(-42.0, result.rssiDbm)
        assertContentEquals(shortArrayOf(-32768, -1, 0, 1, 32767), result.samples)
    }

    @Test fun `malformed and unsupported audio fails safely`() {
        for (bytes in listOf(byteArrayOf(), "SND".toByteArray(), sndPacket().dropLast(1).toByteArray())) {
            assertFailsWith<ProtocolException> { Protocol.snd(bytes) }
        }
        for (flags in listOf(0x10, 0x80)) {
            assertFailsWith<ProtocolException> { Protocol.snd(sndPacket(flags = flags)) }
        }
        assertTrue(Protocol.snd(sndPacket(flags = 2)).adcOverflow)
        assertEquals(6426.5, Protocol.snd(sndPacket(smeter = 65535)).rssiDbm)
    }

    @Test fun `stereo IQ header skips GPS metadata and preserves left right sample order`() {
        val frame = Protocol.snd(stereoPacket(seq = 0xf1234567))
        assertEquals(2, frame.channels)
        assertEquals(3, frame.sampleFrames)
        assertEquals(-42.0, frame.rssiDbm)
        assertEquals(0xf1234567, frame.sequence)
        assertEquals(SndGps(3, 0xf1234567, 987654321), frame.gps)
        assertContentEquals(shortArrayOf(-32768, 32767, -1234, 5678, 0, -1), frame.samples)
        assertEquals(1, Protocol.snd(sndPacket()).channels)
        assertNull(Protocol.snd(sndPacket()).gps)
    }

    @Test fun `malformed stereo headers and incomplete pairs fail safely`() {
        for (length in listOf(10, 19, 21, 22, 23)) {
            assertFailsWith<ProtocolException> { Protocol.snd(stereoPacket().copyOf(length)) }
        }
        assertFailsWith<ProtocolException> { Protocol.snd(stereoPacket(flags = 0x18)) }
        assertFailsWith<ProtocolException> { Protocol.snd(stereoPacket(flags = 0x88)) }
    }

    @Test fun `MSG flags separators and escaping`() {
        for (separator in listOf(" ", "\u0000")) {
            val msg = Protocol.msg("MSG${separator}down name=a%20b plus=a+b value=a=b".toByteArray())
            assertTrue("down" in msg)
            assertNull(msg["down"])
            assertEquals("a b", msg["name"])
            assertEquals("a+b", msg["plus"])
            assertEquals("a=b", msg["value"])
        }
    }

    @Test fun `timestamp preserves integer precision and rejects wrong types`() {
        assertEquals(9007199254740993L, Protocol.timestamp("""{"ts":9007199254740993}"""))
        for (value in listOf("true", "\"123\"", "1.2", "-1", "9223372036854775808", "null")) {
            assertFailsWith<ProtocolException> { Protocol.timestamp("""{"ts":$value}""") }
        }
    }

    @Test fun `handshake accepts all field orderings and waits for auth`() {
        val fields = listOf("badp" to "0", "sample_rate" to "11998.75", "audio_rate" to "12000")
        for (order in listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))) {
            val handshake = Handshake()
            val commands = order.flatMap { handshake.accept(mapOf(fields[it]), Tuning()) }
            assertTrue(handshake.ready)
            assertEquals(11998.75, handshake.sampleRate)
            assertEquals("SET AR OK in=12000 out=48000", commands.first())
            assertEquals(1, commands.count { it == "SET compression=0" })
            assertTrue(handshake.accept(mapOf("badp" to "0"), Tuning()).isEmpty())
        }
        val handshake = Handshake()
        assertTrue(handshake.accept(mapOf("sample_rate" to "12000", "audio_rate" to "12000"), Tuning()).isEmpty())
        assertFalse(handshake.ready)
    }

    @Test fun `policy errors and invalid rates fail setup`() {
        for (msg in listOf("badp=1", "badp=2", "too_busy=4", "down", "redirect=http%3A%2F%2Fexample.com")) {
            assertFailsWith<ProtocolException> { Handshake().accept(Protocol.msg("MSG $msg".toByteArray()), Tuning()) }
        }
        for (rate in listOf("NaN", "Infinity", "0", "1000000", "bad")) {
            assertFailsWith<ProtocolException> { Handshake().accept(mapOf("sample_rate" to rate), Tuning()) }
        }
    }

    @Test fun `sequence gaps duplicates backwards packets and wraparound`() {
        val tracker = SequenceTracker()
        assertTrue(tracker.accept(0xfffffffe))
        assertTrue(tracker.accept(0xffffffff))
        assertTrue(tracker.accept(0))
        assertTrue(tracker.accept(2))
        assertFalse(tracker.accept(2))
        assertFalse(tracker.accept(1))
        assertTrue(tracker.accept(3))
        assertEquals(1, tracker.missing)
        assertEquals(2, tracker.outOfOrder)
    }

    @Test fun `tuning steps and CW offset are exact and locale independent`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("5000.001", formatKhz(Tuning().step(1).frequencyHz))
            assertEquals(5_000_001, parseFrequencyKhz("5000,001"))
            assertEquals("SET mod=cw low_cut=600 high_cut=1000 freq=334.200", Tuning(335000, Mode.CW).command())
            var tune = Tuning()
            repeat(10000) { tune = tune.step(1) }
            repeat(10000) { tune = tune.step(-1) }
            assertEquals(Tuning(), tune)
            for (invalid in listOf("", "-1", "NaN", "1e3", "0.0001", "30000.001")) {
                assertFailsWith<IllegalArgumentException> { parseFrequencyKhz(invalid) }
            }
            assertFailsWith<IllegalArgumentException> { Tuning(799, Mode.CW) }
            assertFailsWith<IllegalArgumentException> { Tuning().step(-5_000_001) }
        } finally { Locale.setDefault(old) }
    }

    @Test fun `endpoint accepts explicit base URL only`() {
        assertTrue(Receiver("Local", "http://10.0.0.41:8073").cleartext)
        assertFalse(Receiver("Secure", "https://example.com").cleartext)
        for (url in listOf("ftp://example.com", "http://a:b@example.com", "http://example.com/path", "http://example.com/?q=1")) {
            assertFailsWith<IllegalArgumentException> { Receiver("Invalid", url) }
        }
    }
}
