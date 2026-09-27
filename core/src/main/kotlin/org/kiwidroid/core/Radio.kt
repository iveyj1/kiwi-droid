package org.kiwidroid.core

import java.math.BigDecimal
import java.math.RoundingMode
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The MVP supports ordinary 0–30 MHz receivers, not transverter offsets. */
const val MAX_FREQUENCY_HZ = 30_000_000L

enum class Mode(val wireName: String, val lowCut: Int, val highCut: Int, val offsetHz: Long = 0) {
    AM("am", -5000, 5000),
    USB("usb", 0, 3000),
    LSB("lsb", -3000, 0),
    CW("cw", 600, 1000, -800),
}

data class Tuning(val frequencyHz: Long = 5_000_000, val mode: Mode = Mode.AM) {
    init {
        require(frequencyHz in 0..MAX_FREQUENCY_HZ) { "Frequency must be 0–30000 kHz" }
        require(frequencyHz + mode.offsetHz in 0..MAX_FREQUENCY_HZ) {
            "CW frequency must be at least 0.800 kHz"
        }
    }
    fun command(): String = "SET mod=${mode.wireName} low_cut=${mode.lowCut} " +
        "high_cut=${mode.highCut} freq=${formatKhz(frequencyHz + mode.offsetHz)}"

    fun step(amountHz: Long): Tuning = copy(frequencyHz = Math.addExact(frequencyHz, amountHz))
}

fun formatKhz(hz: Long): String = BigDecimal.valueOf(hz, 3).toPlainString()

fun parseFrequencyKhz(text: String): Long {
    require(text.trim().matches(Regex("[0-9]+(?:[.,][0-9]{1,3})?"))) {
        "Enter kHz with up to three decimal places"
    }
    val hz = text.trim().replace(',', '.').toBigDecimal().movePointRight(3)
        .setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    require(hz in 0..MAX_FREQUENCY_HZ) { "Frequency must be 0–30000 kHz" }
    return hz
}

/** Explicit endpoints only; no credentials, query, fragment, or reverse-proxy path guessing. */
data class Receiver(val name: String, val url: String) {
    init {
        require(name.isNotBlank() && name.length <= 80) { "Receiver name must be 1–80 characters" }
        endpoint(url)
    }
    val baseUrl: HttpUrl get() = endpoint(url)
    val cleartext: Boolean get() = baseUrl.scheme == "http"

    companion object {
        fun endpoint(value: String): HttpUrl {
            val parsed = value.toHttpUrl()
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Credentials are not supported" }
            require(parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
                "Enter a base URL such as http://10.0.0.41:8073"
            }
            return parsed
        }
    }
}

val DEFAULT_RECEIVERS = listOf(
    Receiver("Local Kiwi 41", "http://10.0.0.41:8073"),
    Receiver("Local Kiwi 42", "http://10.0.0.42:8073"),
    Receiver("Local Kiwi 43", "http://10.0.0.43:8073"),
    Receiver("MISDR proxy", "http://misdr.proxy.kiwisdr.com:8073"),
)
