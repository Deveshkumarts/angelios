package org.sih.itantra.emergency

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager

/**
 * Best-effort device location for SOS beacons, using only the platform LocationManager (GNSS works
 * with no network or Play Services). Returns the freshest last-known fix across providers.
 */
class LocationProvider(private val context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Freshest known fix no older than [maxAgeMs], or null. */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(maxAgeMs: Long = 30 * 60_000L): Location? {
        val manager = lm ?: return null
        if (!hasPermission()) return null
        val now = System.currentTimeMillis()
        return try {
            manager.getProviders(true)
                .mapNotNull { p -> try { manager.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .filter { now - it.time <= maxAgeMs }
                .maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    /** Ask GNSS for a fresh fix in the background so the *next* SOS has a current position. */
    @SuppressLint("MissingPermission")
    fun requestFreshFix() {
        val manager = lm ?: return
        if (!hasPermission()) return
        try {
            val provider = when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> return
            }
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, { }, context.mainLooper)
        } catch (_: Exception) {}
    }
}
