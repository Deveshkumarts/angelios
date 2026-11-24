package org.sih.itantra.presentation.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.sih.itantra.presentation.ui.components.LabLabel
import org.sih.itantra.presentation.ui.components.LabWordmark
import org.sih.itantra.presentation.ui.components.verticalText
import org.sih.itantra.presentation.ui.theme.*

/**
 * Editorial launch poster: the orange block wipes in from the right, the "angelios." wordmark
 * rises into place, the vertical "001" index and the black arrow square settle, and a hairline
 * progress rule runs along the bottom. ~2.2 s total; tap anywhere to skip.
 */
@Composable
fun SplashScreen(
    onNavigateNext: () -> Unit
) {
    val blockReveal = remember { Animatable(0f) }
    val wordRise = remember { Animatable(0f) }
    val detailFade = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    val exitFade = remember { Animatable(1f) }
    var navigated by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val statusLines = listOf("Starting radios", "Loading language models", "Joining mesh", "Ready")
    val statusIndex = (progress.value * (statusLines.size - 1)).toInt().coerceIn(0, statusLines.lastIndex)

    fun finish() {
        if (navigated) return
        navigated = true
        onNavigateNext()
    }

    LaunchedEffect(Unit) {
        launch { blockReveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        delay(250)
        launch { wordRise.animateTo(1f, tween(600, easing = LinearOutSlowInEasing)) }
        delay(350)
        launch { detailFade.animateTo(1f, tween(400)) }
        progress.animateTo(1f, tween(1100, easing = LinearEasing))
        delay(200)
        exitFade.animateTo(0f, tween(250))
        finish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LabPaper)
            .graphicsLayer { alpha = exitFade.value }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { scope.launch { finish() } }
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top rail
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = detailFade.value },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("+", fontFamily = LabFont, fontWeight = FontWeight.Black, fontSize = 18.sp, color = LabInk)
                Spacer(Modifier.width(18.dp))
                LabLabel("Field kit", color = LabInkMuted)
                Spacer(Modifier.weight(1f))
                LabLabel("SIH · PS 26173", color = LabInkMuted)
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(18.dp).height(1.5.dp).background(LabInk))
                    Box(Modifier.width(18.dp).height(1.5.dp).background(LabInk))
                }
            }

            Spacer(Modifier.height(40.dp))

            // Poster captions, top right
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = detailFade.value }
                ) {
                    LabLabel("Mission", color = LabInkDim)
                    Spacer(Modifier.height(4.dp))
                    LabLabel("Offline voice mesh for", color = LabInkMuted)
                    LabLabel("disaster response teams", color = LabInkMuted)
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.graphicsLayer { alpha = detailFade.value }
                ) {
                    LabLabel("Angelios")
                    LabLabel("Signals of")
                    LabLabel("survival")
                }
            }

            Spacer(Modifier.height(28.dp))

            // Wordmark rises into place
            LabWordmark(
                text = "angelios",
                fontSize = 68.sp,
                dotColor = LabOrange,
                modifier = Modifier.graphicsLayer {
                    alpha = wordRise.value
                    translationY = (1f - wordRise.value) * 60.dp.toPx()
                }
            )

            Spacer(Modifier.height(20.dp))

            // Orange block, wiping in from the right, with "001" and the arrow square
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 48.dp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(1f, 0.5f)
                            scaleX = blockReveal.value
                        }
                        .background(LabOrange)
                )

                // Vertical index on the left edge
                Text(
                    text = "001",
                    fontFamily = LabFont,
                    fontWeight = FontWeight.Black,
                    fontSize = 40.sp,
                    letterSpacing = (-1).sp,
                    color = LabInk,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .graphicsLayer { alpha = detailFade.value }
                        .verticalText()
                )

                // Caption inside the block
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .graphicsLayer { alpha = detailFade.value },
                    horizontalAlignment = Alignment.End
                ) {
                    LabLabel("Wi-Fi · BLE · LoRa")
                    LabLabel("10 languages")
                    LabLabel("Zero internet")
                }

                // Black arrow square anchored to the block's bottom-left
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 48.dp)
                        .size(52.dp)
                        .graphicsLayer { alpha = detailFade.value }
                        .background(LabInk),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Skip",
                        tint = LabPaper,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Progress rule + status
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabLabel(statusLines[statusIndex], modifier = Modifier.weight(1f))
                LabLabel("%02d%%".format((progress.value * 100).toInt()), color = LabInkDim)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LabHairline)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value)
                        .background(LabInk)
                )
            }
            Spacer(Modifier.height(10.dp))
            LabLabel("Tap anywhere to skip", color = LabInkDim)
        }
    }
}
