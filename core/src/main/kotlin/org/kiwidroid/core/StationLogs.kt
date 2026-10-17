package org.kiwidroid.core

/** Latest reception, not highest confidence; aliases survive facility consolidation. */
fun latestStationLog(entries: List<LogEntry>, station: MwStation): LogEntry? =
    entries.filter { it.station?.matches(station) == true }.maxByOrNull { it.utc }

/** Browsing a different channel must never copy measurements from the currently heard channel. */
fun LogEntry.forStation(station: MwStation): LogEntry {
    val sameChannel = frequencyHz == station.khz * 1000L
    val current = Tuning(frequencyHz, mode, defaultPassbands() + (mode to passband))
    val tune = if (sameChannel) current else current.toBroadcastChannel(station.khz)
    return copy(frequencyHz = tune.frequencyHz, mode = tune.mode,
        passband = if (tune.mode == mode) passband else tune.passband,
        station = LoggedStation.of(station, receiverLocation?.let { MwRanker.rank(station, it, utc) }),
        rssiDbm = if (sameChannel) rssiDbm else null,
        rssiMinDbm = if (sameChannel) rssiMinDbm else null,
        rssiMaxDbm = if (sameChannel) rssiMaxDbm else null)
}
