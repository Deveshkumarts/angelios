package org.sih.itantra.emergency

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance/bearing between two GPS points (pure maths, unit-tested; used by the FIND screen). */
object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle (haversine) distance in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from true north (0..360). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 8-point compass label for a bearing: N, NE, E, ... */
    fun compassPoint(bearing: Double): String =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((bearing % 360) + 360) % 360 / 45.0).roundToInt() % 8]

    /** "45 m", "340 m", "1.2 km", "18 km". */
    fun formatDistance(meters: Double): String = when {
        meters < 1_000 -> "${(meters / 5).roundToInt() * 5} m"
        meters < 10_000 -> String.format(java.util.Locale.ROOT, "%.1f km", meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }
}
