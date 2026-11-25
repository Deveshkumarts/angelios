package org.sih.itantra

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.navigation.AppNavigation
import org.sih.itantra.presentation.ui.theme.ITANTRATheme
import org.sih.itantra.service.SosNavigationBus

class MainActivity : ComponentActivity() {

    private var repository: CommunicationRepository? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // The battery-optimization prompt is more intrusive than a permission dialog, so it only
        // fires once the (equally intrusive) system permission dialogs are already out of the way.
        maybeRequestBatteryOptimizationExemption()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force hardware acceleration and highest display refresh rate (120 Hz)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        enable120HzRefreshRate()

        requestRequiredPermissions()
        handleSosNotificationIntent(intent)

        val app = application as ItantraApplication
        val repo = app.communicationRepository
        repository = repo

        // Update repository with active screen refresh rate
        detectAndSyncDisplayRate()

        setContent {
            ITANTRATheme {
                AppNavigation(repository = repo)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSosNotificationIntent(intent)
    }

    /** Tapping the high-priority SOS notification (posted by MeshForegroundService) lands here;
     *  jump straight to the Emergency tab instead of leaving the user on whatever screen was last open. */
    private fun handleSosNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_emergency", false) == true) {
            SosNavigationBus.pendingOpenEmergency.value = true
        }
    }

    override fun onStart() {
        super.onStart()
        // While the UI is visible, received messages show as an in-app banner instead of a notification
        repository?.isAppInForeground = true
    }

    override fun onStop() {
        repository?.isAppInForeground = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        enable120HzRefreshRate()
        detectAndSyncDisplayRate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enable120HzRefreshRate()
            detectAndSyncDisplayRate()
        }
    }

    /**
     * Unlocks and forces 120 Hz display refresh rate on supported devices.
     * Checks all available display modes and sets preferredDisplayModeId and preferredRefreshRate.
     */
    private fun enable120HzRefreshRate() {
        try {
            val win = window ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        this.display
                    } catch (_: Throwable) {
                        (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }

                if (display != null) {
                    val supportedModes = display.supportedModes ?: emptyArray()
                    val currentMode = display.mode

                    // Filter modes: prioritize current resolution to avoid unwanted resolution scale changes
                    val candidateModes = supportedModes.filter {
                        currentMode == null || (it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight)
                    }.ifEmpty { supportedModes.toList() }

                    // Seek 120 Hz mode (118 Hz to 122 Hz), or highest available above 60 Hz
                    val highRateMode = candidateModes.filter { it.refreshRate in 118.0f..122.0f }.maxByOrNull { it.refreshRate }
                        ?: candidateModes.filter { it.refreshRate > 60.0f }.maxByOrNull { it.refreshRate }
                        ?: candidateModes.maxByOrNull { it.refreshRate }

                    val layoutParams = win.attributes
                    if (highRateMode != null) {
                        layoutParams.preferredDisplayModeId = highRateMode.modeId
                        layoutParams.preferredRefreshRate = highRateMode.refreshRate
                    } else {
                        layoutParams.preferredRefreshRate = 120.0f
                    }
                    win.attributes = layoutParams
                } else {
                    val layoutParams = win.attributes
                    layoutParams.preferredRefreshRate = 120.0f
                    win.attributes = layoutParams
                }
            } else {
                val layoutParams = win.attributes
                layoutParams.preferredRefreshRate = 120.0f
                win.attributes = layoutParams
            }
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "High refresh rate request: ${e.message}")
        }
    }

    private fun detectAndSyncDisplayRate() {
        try {
            val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    this.display
                } catch (_: Throwable) {
                    (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
                }
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }

            val hz = display?.refreshRate?.toInt() ?: 120
            repository?.setScreenRefreshRate(if (hz >= 118) 120 else hz)
        } catch (_: Throwable) {}
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.VIBRATE,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            // Without this, MeshForegroundService's SOS alert notification is silently dropped by the OS.
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            maybeRequestBatteryOptimizationExemption()
        }
    }

    /**
     * Asked once, ever. Battery-optimization / "app hibernation" policies on Xiaomi, Oppo, Vivo,
     * Samsung and (eventually) stock Android are the single biggest real-world reason a correctly
     * built foreground service still gets killed in the background - exempting the app is what
     * keeps the mesh listener and SOS alerts actually running after the user stops looking at it.
     */
    private fun maybeRequestBatteryOptimizationExemption() {
        val prefs = getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_prompted", false)) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } catch (_: Throwable) {
            // Some OEM ROMs (or a missing Settings activity) reject this intent; the app still
            // works, just without the battery-optimization exemption.
        } finally {
            prefs.edit().putBoolean("battery_opt_prompted", true).apply()
        }
    }
}
