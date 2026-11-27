package org.sih.itantra.presentation.find

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.emergency.GeoMath
import org.sih.itantra.presentation.ui.components.LabLabel
import org.sih.itantra.presentation.ui.components.LabPanel
import org.sih.itantra.presentation.ui.components.LabWordmark
import org.sih.itantra.presentation.ui.theme.*

/**
 * Direction finder to a peer in distress. With GPS from both phones it shows an arrow that turns
 * with the phone (compass, corrected to true north) and the distance. Without the peer's GPS it
 * falls back to radio signal strength: "warmer" as the signal gets stronger, "colder" as it fades.
 */
@Composable
fun FindScreen(repository: CommunicationRepository, peerId: String, onBack: () -> Unit) {
    val locations by repository.peerLocations.collectAsState()
    val nodes by repository.nodes.collectAsState()
    val target = locations[peerId]
    val name = target?.name?.ifBlank { null } ?: nodes.firstOrNull { it.id.trim() == peerId }?.name ?: peerId

    // Own position: live updates only while this screen is open
    DisposableEffect(Unit) {
        repository.locationProvider.startUpdates()
        onDispose { repository.locationProvider.stopUpdates() }
    }
    val me by repository.locationProvider.current.collectAsState()

    val magneticAzimuth = rememberCompassAzimuth()
    val declination = me?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), it.time).declination } ?: 0f
    val heading = magneticAzimuth?.let { (it + declination + 360f) % 360f }

    val distance = if (me != null && target != null) GeoMath.distanceMeters(me!!.latitude, me!!.longitude, target.latitude, target.longitude) else null
    val bearing = if (me != null && target != null) GeoMath.bearingDegrees(me!!.latitude, me!!.longitude, target.latitude, target.longitude) else null
    val relative = if (bearing != null) ((bearing - (heading ?: 0f) + 360.0) % 360.0).toFloat() else 0f
    val arrowAngle by animateFloatAsState(targetValue = relative, animationSpec = tween(250), label = "arrow")

    // Ticking clock for "updated N s ago" and the signal trend
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    // Warmer / colder from the peer's radio signal
    val rssi = nodes.firstOrNull { it.id.trim() == peerId }?.rssi
    val rssiHistory = remember { mutableStateListOf<Int>() }
    LaunchedEffect(now) { rssi?.let { rssiHistory.add(it); if (rssiHistory.size > 12) rssiHistory.removeAt(0) } }
    val trend = if (rssiHistory.size >= 6) {
        rssiHistory.takeLast(3).average() - rssiHistory.take(3).average()
    } else 0.0

    // Haptic tick for every 10 m closer
    val view = LocalView.current
    var lastTick by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(distance?.let { (it / 10).toInt() }) {
        val bucket = distance?.let { (it / 10).toInt() } ?: return@LaunchedEffect
        if (lastTick != null && bucket < lastTick!!) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        lastTick = bucket
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LabPaper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LabInk, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            LabLabel("Back")
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabLabel("SOS", color = LabOrangeDeep)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(18.dp).height(1.dp).background(LabInk))
            Spacer(Modifier.width(8.dp))
            LabLabel("Find ${name}")
        }
        Spacer(Modifier.height(6.dp))
        LabWordmark(name, fontSize = 48.sp, dotColor = LabOrange)
        Spacer(Modifier.height(10.dp))

        // Status strip
        val status = when {
            target == null -> "No GPS from $name yet · follow the signal below"
            me == null -> "Waiting for your GPS fix… go outdoors if it takes long"
            else -> "Position ${ageText(now - target.receivedAt)} · ${if (target.inSos) "live SOS" else "last known"}"
        }
        Box(
            modifier = Modifier.fillMaxWidth().background(LabOrange).padding(horizontal = 12.dp, vertical = 10.dp)
        ) { LabLabel(status, color = LabInk) }

        Spacer(Modifier.height(20.dp))

        // Arrow
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DirectionDial(
                arrowAngle = arrowAngle,
                northAngle = -(heading ?: 0f),
                active = distance != null,
                modifier = Modifier.size(260.dp)
            )
        }
        Spacer(Modifier.height(16.dp))

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = distance?.let { GeoMath.formatDistance(it) } ?: "--",
                fontFamily = LabFont,
                fontWeight = FontWeight.Black,
                fontSize = 56.sp,
                letterSpacing = (-2).sp,
                color = LabInk
            )
            LabLabel(
                when {
                    bearing != null -> "${GeoMath.compassPoint(bearing)} · ${bearing.toInt()}°" +
                        if (heading == null) " · no compass: hold the phone pointing north" else ""
                    else -> "distance appears when both positions are known"
                },
                color = LabInkMuted
            )
        }

        Spacer(Modifier.height(20.dp))

        // Signal meter: works even with no GPS at all
        LabPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabLabel("Radio signal", modifier = Modifier.weight(1f))
                LabLabel(
                    when {
                        rssi == null -> "out of range"
                        trend > 2 -> "Warmer ↑"
                        trend < -2 -> "Colder ↓"
                        else -> "Steady"
                    },
                    color = if (trend > 2) LabGreen else if (trend < -2) LabOrangeDeep else LabInk
                )
            }
            Spacer(Modifier.height(8.dp))
            val strength = rssi?.let { ((it + 100).coerceIn(0, 60) / 60f) } ?: 0f
            Box(Modifier.fillMaxWidth().height(14.dp).background(LabPaperDeep)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(strength).background(LabInk))
            }
            Spacer(Modifier.height(6.dp))
            LabLabel(rssi?.let { "$it dBm · walk in the direction that makes it warmer" } ?: "no beacons from $name right now",
                color = LabInkMuted)
        }
        Spacer(Modifier.height(12.dp))
        LabLabel("Hold the phone flat, top edge pointing ahead.", color = LabInkDim)
        Spacer(Modifier.height(24.dp))
    }
}

private fun ageText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}

/** Compass ring with a north tick and a big arrow pointing at the target (angles in degrees). */
@Composable
private fun DirectionDial(arrowAngle: Float, northAngle: Float, active: Boolean, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        drawCircle(LabHairline, radius = r - 2.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
        drawCircle(LabCard, radius = r - 4.dp.toPx(), center = c)
        // north tick
        rotate(northAngle, c) {
            drawLine(LabOrange, Offset(c.x, c.y - r + 4.dp.toPx()), Offset(c.x, c.y - r + 22.dp.toPx()), strokeWidth = 4.dp.toPx())
        }
        if (!active) return@Canvas
        rotate(arrowAngle, c) {
            val tip = Offset(c.x, c.y - r * 0.78f)
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(c.x + r * 0.22f, c.y + r * 0.05f)
                lineTo(c.x + r * 0.07f, c.y + r * 0.05f)
                lineTo(c.x + r * 0.07f, c.y + r * 0.55f)
                lineTo(c.x - r * 0.07f, c.y + r * 0.55f)
                lineTo(c.x - r * 0.07f, c.y + r * 0.05f)
                lineTo(c.x - r * 0.22f, c.y + r * 0.05f)
                close()
            }
            drawPath(path, LabInk)
        }
        drawCircle(LabOrange, radius = 7.dp.toPx(), center = c)
    }
}

/** Device heading (degrees from magnetic north) from the rotation-vector sensor, smoothed; null if absent. */
@Composable
private fun rememberCompassAzimuth(): Float? {
    val context = LocalContext.current
    var azimuth by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, orientation)
                val deg = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
                val prev = azimuth
                // Low-pass on the circle so 359 -> 1 doesn't swing through 180
                azimuth = if (prev == null) deg else {
                    val diff = ((deg - prev + 540) % 360) - 180
                    (prev + diff * 0.15f + 360) % 360
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return azimuth
}
