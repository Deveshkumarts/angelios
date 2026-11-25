package org.sih.itantra.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.presentation.ui.theme.*
import org.sih.itantra.storage.UserProfile

/**
 * Editorial top bar: "angelios." wordmark with the operator's callsign underneath,
 * a caption-style language switch ("EN ▾") and a square orange SOS block on the right.
 * A full-width orange strip appears while a private link is active or SOS is transmitting.
 */
@Composable
fun TacticalTopBar(
    selectedLanguage: Language,
    isSosActive: Boolean,
    userProfile: UserProfile = UserProfile(),
    activeTargetNode: NetworkNode? = null,
    onOpenLanguage: () -> Unit,
    onTriggerSos: () -> Unit,
    onClearTarget: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sosTopPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LabPaper)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Wordmark + operator line
            Column(modifier = Modifier.weight(1f)) {
                LabWordmark("angelios", fontSize = 22.sp, dotColor = LabOrange)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isSosActive -> LabOrange
                                    activeTargetNode != null -> LabOrange
                                    else -> LabGreen
                                }
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    LabLabel(
                        text = "${userProfile.callsign} · ${userProfile.username}",
                        color = LabInkMuted,
                        maxLines = 1
                    )
                }
            }

            // Language switch, styled like the reference's "NEW ARRIVALS ▾" menu caption
            Row(
                modifier = Modifier
                    .clickable { onOpenLanguage() }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedLanguage.nativeName,
                    fontFamily = LabFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = LabInk
                )
                Spacer(Modifier.width(4.dp))
                LabLabel(selectedLanguage.code, color = LabInkDim)
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Change language",
                    tint = LabInk,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Square SOS block
            Box(
                modifier = Modifier
                    .size(width = 52.dp, height = 36.dp)
                    .background(if (isSosActive) LabOrange.copy(alpha = pulseAlpha) else LabOrange)
                    .clickable { onTriggerSos() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SOS",
                    fontFamily = LabFont,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp,
                    color = LabInk
                )
            }
        }

        // Status strip
        when {
            isSosActive -> StatusStrip(text = "SOS transmitting to every reachable node", onClick = onTriggerSos)
            activeTargetNode != null -> StatusStrip(
                text = "Private link · ${activeTargetNode.name} — tap to return to broadcast",
                onClick = onClearTarget,
                closable = true
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LabInk)
        )
    }
}

@Composable
private fun StatusStrip(text: String, onClick: () -> Unit, closable: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LabOrange)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LabLabel(text, color = LabInk, modifier = Modifier.weight(1f), maxLines = 1)
        if (closable) {
            Icon(Icons.Default.Close, contentDescription = "Clear private link", tint = LabInk, modifier = Modifier.size(14.dp))
        }
    }
}
