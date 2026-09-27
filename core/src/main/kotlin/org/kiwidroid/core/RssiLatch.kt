package org.kiwidroid.core

/** Extremes are captured on receipt, not on the slower audio/UI refresh. */
enum class RssiLatchMode(val label: String) {
    OFF("Off"), DIPS("Dips only"), PEAKS("Peaks only"), BOTH("Dips and peaks"),
}

internal class RssiExtremaLatch(private val settings: RssiToneSettings) {
    private data class Held(val dbm: Double, var firstSoundedMs: Long? = null)
    private var dip: Held? = null
    private var peak: Held? = null
    private var lastWasDip = false

    fun observe(rawDbm: Double, baselineDbm: Double, nowMs: Long) {
        expire(nowMs)
        if (settings.latchMode == RssiLatchMode.DIPS || settings.latchMode == RssiLatchMode.BOTH) {
            if (baselineDbm - rawDbm >= settings.latchThresholdDb && (dip == null || rawDbm < dip!!.dbm)) {
                dip = Held(rawDbm)
            }
        }
        if (settings.latchMode == RssiLatchMode.PEAKS || settings.latchMode == RssiLatchMode.BOTH) {
            if (rawDbm - baselineDbm >= settings.latchThresholdDb && (peak == null || rawDbm > peak!!.dbm)) {
                peak = Held(rawDbm)
            }
        }
    }

    /** A pending extreme cannot expire before its first beep. Both directions alternate. */
    fun next(nowMs: Long): Double? {
        expire(nowMs)
        val useDip = dip != null && (peak == null || !lastWasDip)
        val selected = if (useDip) dip else peak
        if (selected == null) return null
        lastWasDip = useDip
        if (selected.firstSoundedMs == null) selected.firstSoundedMs = nowMs
        return selected.dbm
    }

    private fun expire(nowMs: Long) {
        fun expired(held: Held?): Boolean = held?.firstSoundedMs?.let { nowMs - it >= settings.latchHoldMs } ?: false
        if (expired(dip)) dip = null
        if (expired(peak)) peak = null
    }
}
