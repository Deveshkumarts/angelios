package org.sih.itantra.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.presentation.ui.theme.*

/*
 * Editorial "lab" building blocks: lowercase wordmarks ending in a full stop, tiny tracked
 * uppercase captions, hairline-framed square panels, solid orange blocks and black square
 * arrow buttons. Everything is sharp-cornered on purpose.
 */

/** Lowercase wordmark with the trailing full stop, e.g. "angelios." / "talk." */
@Composable
fun LabWordmark(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 56.sp,
    color: Color = LabInk,
    dotColor: Color = color
) {
    Text(
        text = buildAnnotatedString {
            append(text.lowercase())
            withStyle(SpanStyle(color = dotColor)) { append(".") }
        },
        style = LabDisplay.copy(
            fontSize = fontSize,
            lineHeight = fontSize,
            letterSpacing = (fontSize.value * -0.045f).sp,
            color = color
        ),
        modifier = modifier,
        maxLines = 1
    )
}

/** Tiny tracked uppercase caption. */
@Composable
fun LabLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LabInk,
    style: TextStyle = LabCaption,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text = text.uppercase(),
        style = style.copy(color = color),
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** Rotates content 90° counter-clockwise and swaps its measured width/height (reads bottom-to-top). */
fun Modifier.verticalText(): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = 0, maxWidth = Constraints_Infinity,
                minHeight = 0, maxHeight = Constraints_Infinity
            )
        )
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = -(placeable.width / 2 - placeable.height / 2),
                y = -(placeable.height / 2 - placeable.width / 2)
            )
        }
    }
    .graphicsLayer { rotationZ = -90f }

private const val Constraints_Infinity = androidx.compose.ui.unit.Constraints.Infinity

/** Square black button with a white arrow — the reference's signature call-to-action. */
@Composable
fun LabArrowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    icon: ImageVector = Icons.AutoMirrored.Filled.ArrowForward,
    background: Color = LabInk,
    tint: Color = LabPaper,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/** Hairline-framed white panel. `strong = true` uses a full ink frame for emphasis. */
@Composable
fun LabPanel(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    background: Color = LabCard,
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .border(if (strong) 1.5.dp else 1.dp, if (strong) LabInk else LabHairline)
            .padding(padding),
        content = content
    )
}

/** "02 ──────── SECTION TITLE" divider used to open each content block. */
@Composable
fun LabSectionLabel(
    index: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LabLabel(index, color = LabOrangeDeep)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .width(22.dp)
                .height(1.dp)
                .background(LabInk)
        )
        Spacer(Modifier.width(8.dp))
        LabLabel(title, modifier = Modifier.weight(1f), maxLines = 1)
        if (trailing != null) LabLabel(trailing, color = LabInkDim, maxLines = 1)
    }
}

/**
 * Screen hero modelled on the reference poster:
 *
 *   001 / 004 ─ SECTION                         CAPTION LINE 1
 *   talk.                                       CAPTION LINE 2
 *   [→]█████████████ status line ████████████████████████
 */
@Composable
fun LabScreenHeader(
    index: Int,
    total: Int,
    section: String,
    title: String,
    modifier: Modifier = Modifier,
    captions: List<String> = emptyList(),
    status: String? = null,
    statusIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowForward,
    accent: Color = LabOrange,
    onStatusClick: (() -> Unit)? = null,
    titleSize: TextUnit = 52.sp
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabLabel("%03d".format(index), color = LabInk)
                    LabLabel(" / %03d".format(total), color = LabInkDim)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(18.dp).height(1.dp).background(LabInk))
                    Spacer(Modifier.width(8.dp))
                    LabLabel(section, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                LabWordmark(title, fontSize = titleSize, dotColor = accent)
            }
            if (captions.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(top = 2.dp, start = 8.dp).widthIn(max = 140.dp)
                ) {
                    captions.forEach { LabLabel(it, color = LabInkMuted, maxLines = 1) }
                }
            }
        }
        if (status != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                LabArrowButton(
                    onClick = onStatusClick ?: {},
                    size = 40.dp,
                    icon = statusIcon
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(accent)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    LabLabel(status, color = LabInk, maxLines = 1)
                }
            }
        }
    }
}

/** Small square status tag: ink frame, optional filled state. */
@Composable
fun LabTag(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    color: Color = LabInk
) {
    Box(
        modifier = modifier
            .background(if (filled) color else Color.Transparent)
            .border(1.dp, color)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        LabLabel(text, color = if (filled) (if (color == LabInk) LabPaper else LabInk) else color, maxLines = 1)
    }
}
