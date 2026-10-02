package org.kiwidroid.core

import java.time.Instant
import java.util.Locale
import kotlin.math.*

/** Decimal degrees, north and east positive. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "Latitude must be −90…90 and longitude −180…180 degrees"
        }
    }
    fun distanceKm(other: GeoPoint) = distanceKm(latitude, longitude, other.latitude, other.longitude)

    /** Initial great-circle bearing from this point, 0–360° clockwise from true north. */
    fun bearingTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    companion object {
        fun parse(latitude: String, longitude: String): GeoPoint {
            fun number(text: String, name: String) = requireNotNull(
                text.trim().replace('−', '-').replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
            ) { "$name must be decimal degrees" }
            return GeoPoint(number(latitude, "Latitude"), number(longitude, "Longitude"))
        }
    }
}

val MwStation.site: GeoPoint get() = GeoPoint(latitude, longitude)

fun compassPoint(bearing: Double): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((bearing + 22.5) / 45).toInt() % 8]

/** NOAA low-precision solar position; within a fraction of a degree, ample for day/night decisions. */
fun sunElevationDeg(point: GeoPoint, instant: Instant): Double {
    val julianDay = instant.epochSecond / 86400.0 + 2440587.5
    val t = (julianDay - 2451545.0) / 36525
    val l0 = Math.toRadians((280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0))
    val m = Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t))
    val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
    val center = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) +
        sin(3 * m) * 0.000289
    val omega = Math.toRadians(125.04 - 1934.136 * t)
    val lambda = l0 + Math.toRadians(center - 0.00569 - 0.00478 * sin(omega))
    val obliquity = Math.toRadians(23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60 +
        0.00256 * cos(omega))
    val declination = asin(sin(obliquity) * sin(lambda))
    val y = tan(obliquity / 2).pow(2)
    val equationOfTimeMin = 4 * Math.toDegrees(y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
        0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m))
    val utcMinutes = instant.epochSecond.mod(86400L) / 60.0
    val hourAngle = Math.toRadians((utcMinutes + equationOfTimeMin + 4 * point.longitude) / 4 - 180)
    val lat = Math.toRadians(point.latitude)
    val cosZenith = sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)
    return 90 - Math.toDegrees(acos(cosZenith.coerceIn(-1.0, 1.0)))
}

/** Authorization period at the station: FCC critical hours are the two hours after sunrise and before sunset. */
enum class MwPeriod { DAY, CRITICAL, NIGHT }

private const val SUNRISE_ELEVATION = -0.833

fun mwPeriod(site: GeoPoint, instant: Instant): MwPeriod {
    if (sunElevationDeg(site, instant) <= SUNRISE_ELEVATION) return MwPeriod.NIGHT
    val twoHours = 7200L
    val nearTwilight = sunElevationDeg(site, instant.minusSeconds(twoHours)) <= SUNRISE_ELEVATION ||
        sunElevationDeg(site, instant.plusSeconds(twoHours)) <= SUNRISE_ELEVATION
    return if (nearTwilight) MwPeriod.CRITICAL else MwPeriod.DAY
}

fun MwStation.powerKw(period: MwPeriod): Double? = when (period) {
    MwPeriod.DAY -> dayKw
    MwPeriod.CRITICAL -> criticalKw ?: dayKw
    MwPeriod.NIGHT -> nightKw
}

data class MwRanking(
    val station: MwStation,
    val distanceKm: Double,
    val bearingDeg: Double,
    val period: MwPeriod,
    /** Null when the station has no authorization for the current period (e.g. a daytimer at night). */
    val kw: Double?,
    /** Relative field-strength estimate in dB; only differences between stations are meaningful. */
    val scoreDb: Double,
    val skywave: Boolean,
) {
    val onAir: Boolean get() = kw != null && kw > 0
}

/**
 * Coarse likelihood heuristic, not a propagation prediction. Ground wave falls with inverse distance plus
 * frequency-proportional excess loss over average ground. Sky wave needs darkness at the path midpoint and
 * falls slowly with distance. Directional antenna patterns, ground conductivity, season and solar activity
 * are ignored, so treat the ranking as "who to listen for", not identification.
 */
object MwRanker {
    private const val GROUND_EXCESS_DB_PER_100KM_AT_1MHZ = 3.0
    private const val SKY_BASE_LOSS_DB = 6.0
    private const val SKY_MIN_DISTANCE_KM = 200.0
    private const val SKY_LONG_PATH_KM = 3000.0
    private const val SKY_LONG_PATH_DB_PER_100KM = 1.5
    private const val DARK_ELEVATION = -12.0
    private const val OFF_AIR_DB = -1000.0

    fun rank(stations: List<MwStation>, receiver: GeoPoint, instant: Instant): List<MwRanking> =
        stations.map { rank(it, receiver, instant) }
            .sortedWith(compareByDescending<MwRanking> { it.scoreDb }.thenBy { it.distanceKm })

    fun rank(station: MwStation, receiver: GeoPoint, instant: Instant): MwRanking {
        val site = station.site
        val distance = receiver.distanceKm(site)
        val period = mwPeriod(site, instant)
        val kw = station.powerKw(period)?.takeIf { it > 0 }
        val bearing = receiver.bearingTo(site)
        if (kw == null) return MwRanking(station, distance, bearing, period, null, OFF_AIR_DB, false)
        val powerDb = 10 * log10(kw)
        val ground = powerDb - 20 * log10(max(distance, 1.0)) -
            GROUND_EXCESS_DB_PER_100KM_AT_1MHZ * (distance / 100) * (station.khz / 1000.0)
        val darkness = ((-sunElevationDeg(midpoint(receiver, site), instant)) / -DARK_ELEVATION).coerceIn(1e-4, 1.0)
        val sky = powerDb - 20 * log10(max(distance, SKY_MIN_DISTANCE_KM)) - SKY_BASE_LOSS_DB -
            max(0.0, distance - SKY_LONG_PATH_KM) / 100 * SKY_LONG_PATH_DB_PER_100KM + 10 * log10(darkness)
        val total = 10 * log10(10.0.pow(ground / 10) + 10.0.pow(sky / 10))
        return MwRanking(station, distance, bearing, period, kw, total, sky > ground)
    }

    /** Spherical midpoint of the great-circle path. */
    fun midpoint(a: GeoPoint, b: GeoPoint): GeoPoint {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val lon1 = Math.toRadians(a.longitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val bx = cos(lat2) * cos(dLon)
        val by = cos(lat2) * sin(dLon)
        val lat = atan2(sin(lat1) + sin(lat2), sqrt((cos(lat1) + bx).pow(2) + by * by))
        val lon = lon1 + atan2(by, cos(lat1) + bx)
        return GeoPoint(Math.toDegrees(lat), (Math.toDegrees(lon) + 540) % 360 - 180)
    }
}

fun formatDistance(km: Double): String = String.format(Locale.ROOT, "%,d km", km.roundToInt())
