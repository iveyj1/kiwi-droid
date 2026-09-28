package org.kiwidroid.core

import java.math.BigDecimal
import java.math.RoundingMode
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The MVP supports ordinary 0–30 MHz receivers, not transverter offsets. */
const val MAX_FREQUENCY_HZ = 30_000_000L

enum class Mode(val wireName: String, val lowCut: Int, val highCut: Int,
                val channels: Int = 1, val centerTuning: Boolean = false) {
    AM("am", -5000, 5000),
    USB("usb", 0, 3000),
    LSB("lsb", -3000, 0),
    CW("cw", 600, 1000, centerTuning = true),
    SAM("sam", -5000, 5000),
    SAU("sau", 0, 5000),
    SAL("sal", -5000, 0),
    SAS("sas", -5000, 5000, channels = 2),
    IQ("iq", -5000, 5000, channels = 2),
    CWN("cwn", 470, 530, centerTuning = true),
    ;
    val defaultPassband: Passband get() = Passband(lowCut, highCut)
}

/** Signed audio/baseband cuts for the supported ordinary 12 kHz receivers. */
data class Passband(val lowHz: Int, val highHz: Int) {
    init {
        require(lowHz in -6000..6000 && highHz in -6000..6000) { "Passband limits must be −6000 to +6000 Hz" }
        require(lowHz < highHz) { "Lower passband limit must be below the upper limit" }
    }
}

fun defaultPassbands(): Map<Mode, Passband> = Mode.entries.associateWith { it.defaultPassband }

data class Tuning(val frequencyHz: Long = 5_000_000, val mode: Mode = Mode.AM,
                  val passbands: Map<Mode, Passband> = defaultPassbands()) {
    val passband: Passband get() = passbands.getValue(mode)
    // Half-Hz units keep an odd sum of cuts centered exactly, without losing tune precision.
    val radioFrequencyHalfHz: Long get() = frequencyHz * 2 -
        if (mode.centerTuning) (passband.lowHz + passband.highHz).toLong() else 0L

    init {
        require(passbands.keys.containsAll(Mode.entries)) { "Missing per-mode passband settings" }
        require(frequencyHz in 0..MAX_FREQUENCY_HZ) { "Frequency must be 0–30000 kHz" }
        require(radioFrequencyHalfHz in 0..MAX_FREQUENCY_HZ * 2) {
            "Mode/passband puts the receiver frequency outside 0–30000 kHz"
        }
    }
    fun command(): String {
        val radioKhz = BigDecimal.valueOf(radioFrequencyHalfHz).divide(BigDecimal(2000))
            .setScale(if (radioFrequencyHalfHz % 2 == 0L) 3 else 4).toPlainString()
        return "SET mod=${mode.wireName} low_cut=${passband.lowHz} high_cut=${passband.highHz} freq=$radioKhz"
    }

    fun withPassband(value: Passband): Tuning = copy(passbands = passbands + (mode to value))
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
