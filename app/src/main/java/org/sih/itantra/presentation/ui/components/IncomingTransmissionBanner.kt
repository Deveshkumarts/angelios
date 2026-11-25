package org.sih.itantra.presentation.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.model.TransportType
import org.sih.itantra.domain.repository.IncomingTransmission
import org.sih.itantra.presentation.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Receive-side counterpart to the PTT button: slides in over any screen when a message arrives.
 *
 *  [▮▮▮] | INCOMING · VIA WI-FI · 12:04        ✕
 *        | RAVI
 *        | We are safe
 *        | PLAYING VOICE NOTE
 *
 * The left block shows live level bars while the voice note plays. SOS messages use the orange
 * block colour. Tapping the banner opens the conversation.
 */
@Composable
fun IncomingTransmissionBanner(
    transmission: IncomingTransmission,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    LaunchedEffect(transmission.messageId) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    val sos = transmission.isSos
    val body = if (sos) LabOrange else LabCard
    val playing = isPlaying && !transmission.spoken

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(body)
            .border(1.5.dp, LabInk)
            .clickable(onClick = onOpen)
    ) {
        // Left block: live level bars while speaking, a tick once played
        Box(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight()
                .background(LabInk),
            contentAlignment = Alignment.Center
        ) {
            if (transmission.spoken) {
                Icon(Icons.Default.Done, contentDescription = "Played", tint = LabPaper, modifier = Modifier.size(22.dp))
            } else {
                LevelBars(active = playing, color = if (sos) LabOrange else LabPaper)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
        ) {
            LabLabel(
                text = listOf(
                    if (sos) "SOS · distress" else "Incoming",
                    "via ${transportLabel(transmission.transport)}",
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(transmission.receivedAt))
                ).joinToString(" · "),
                color = if (sos) LabInk else LabOrangeDeep,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = transmission.senderName.uppercase(),
                fontFamily = LabFont,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = (-0.3).sp,
                color = LabInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = transmission.text,
                fontFamily = LabFont,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = LabInk,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            LabLabel(
                text = when {
                    transmission.spoken -> "Played · tap to open"
                    playing -> if (sos) "Announcing at full volume" else "Playing voice note"
                    else -> "Received · preparing voice"
                },
                color = if (sos) LabInk else LabInkMuted
            )
        }

        Box(
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(10.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = LabInk, modifier = Modifier.size(16.dp))
        }
    }
}

/** Four bars that bounce while [active]; flat otherwise. Only the bars redraw (graphicsLayer). */
@Composable
private fun LevelBars(active: Boolean, color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "rxBars")
    val phases = listOf(0, 180, 90, 270)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        phases.forEach { delay ->
            val scale by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(420, delayMillis = delay), RepeatMode.Reverse),
                label = "bar"
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(22.dp)
                    .graphicsLayer { scaleY = if (active) scale else 0.25f }
                    .background(color)
            )
        }
    }
}

private fun transportLabel(t: TransportType): String = when (t) {
    TransportType.WIFI_DIRECT -> "Wi-Fi"
    TransportType.BLE -> "Bluetooth"
    TransportType.LORA -> "LoRa"
    else -> t.name.replace('_', ' ').lowercase()
}
