package org.sih.itantra.presentation.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Single grotesque family; hierarchy comes from weight, size and tracking, not from typefaces. */
val LabFont: FontFamily = FontFamily.SansSerif

/** Giant lowercase wordmark ("angelios.") */
val LabDisplay = TextStyle(
    fontFamily = LabFont,
    fontWeight = FontWeight.Black,
    fontSize = 56.sp,
    lineHeight = 56.sp,
    letterSpacing = (-2.5).sp,
    color = LabInk
)

/** Tiny uppercase tracked caption used for labels, eyebrows and metadata. */
val LabCaption = TextStyle(
    fontFamily = LabFont,
    fontWeight = FontWeight.Bold,
    fontSize = 9.sp,
    lineHeight = 12.sp,
    letterSpacing = 1.4.sp,
    color = LabInk
)

val Typography = Typography(
    displayLarge = LabDisplay,
    headlineLarge = TextStyle(
        fontFamily = LabFont,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        letterSpacing = (-1.2).sp,
        color = LabInk
    ),
    headlineMedium = TextStyle(
        fontFamily = LabFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.6).sp,
        color = LabInk
    ),
    titleLarge = TextStyle(
        fontFamily = LabFont,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
        color = LabInk
    ),
    titleMedium = TextStyle(
        fontFamily = LabFont,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = LabInk
    ),
    bodyLarge = TextStyle(
        fontFamily = LabFont,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = LabInk
    ),
    bodyMedium = TextStyle(
        fontFamily = LabFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = LabInkMuted
    ),
    labelSmall = LabCaption
)
