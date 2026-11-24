package org.sih.itantra.presentation.ui.theme

import androidx.compose.ui.graphics.Color

// ==========================================
// EDITORIAL "LAB" PALETTE
// Off-white paper, black ink, one loud orange-red block colour.
// ==========================================

val LabPaper = Color(0xFFF3F2EF)        // page background
val LabPaperDeep = Color(0xFFE9E7E3)    // recessed areas, inactive fills
val LabCard = Color(0xFFFFFFFF)         // framed panels
val LabInk = Color(0xFF111111)          // primary text, frames, buttons
val LabInkMuted = Color(0xFF55524D)     // secondary text
val LabInkDim = Color(0xFF8E8A84)       // captions, disabled
val LabHairline = Color(0x22111111)     // 13% ink: quiet panel frames
val LabHairlineFaint = Color(0x12111111)

val LabOrange = Color(0xFFFF4B1F)       // the signature block colour
val LabOrangeDeep = Color(0xFFD93A0E)   // orange used as text on paper (better contrast)
val LabOrangeDark = Color(0xFFA82C08)
val LabOrangeTint = Color(0x1FFF4B1F)   // 12% wash

val LabGreen = Color(0xFF12804A)        // link up / delivered
val LabAmber = Color(0xFFC77F00)        // degraded / warning

// ------------------------------------------
// Legacy token names used across the screens, re-pointed at the editorial palette.
// "Text*" tokens are now ink on paper; "*Red*" tokens are the orange accent.
// ------------------------------------------

val CyberVoidBlack = LabPaper
val CyberSurfaceDark = LabPaperDeep
val CyberCardDark = LabCard

val CyberGlassSurface = LabCard
val CyberGlassCard = LabCard
val CyberGlassHighlight = LabOrangeTint

val CyberNeonRed = LabOrange
val CyberNeonRedBright = LabOrangeDeep
val CyberNeonRedGlow = LabOrangeTint
val CyberNeonBloodOrange = LabOrange
val CyberCrimsonDark = LabOrangeDark

val CyberMatrixGreen = LabGreen
val CyberMatrixGreenGlow = Color(0x1F12804A)
val CyberWarningAmber = LabAmber
val CyberWarningGlow = Color(0x1FC77F00)
val CyberLaserCyan = LabInk
val CyberLaserCyanGlow = LabHairlineFaint

val CyberTextWhite = LabInk
val CyberTextGlow = LabOrange
val CyberTextMuted = LabInkMuted
val CyberTextDim = LabInkDim

val CyberBorderRed = LabHairline
val CyberBorderRedBright = LabInk
val CyberBorderSubtle = LabHairlineFaint

val DeepSpaceNavy = CyberVoidBlack
val SurfaceNavy = CyberSurfaceDark
val CardNavy = CyberCardDark
val CardNavyGlass = CyberGlassSurface

val IsroBlue = LabOrange           // primary-action fill (ink text on top)
val IsroBlueBright = LabOrange
val IsroBlueGlow = LabOrangeTint

// The old secondary "cyan" role becomes plain ink so orange stays reserved for emphasis.
val SignalCyan = LabInk
val SignalCyanBright = LabInk
val SignalCyanGlow = LabHairlineFaint

val NetworkGreen = CyberMatrixGreen
val NetworkGreenGlow = CyberMatrixGreenGlow

val WarningAmber = CyberWarningAmber
val WarningAmberGlow = CyberWarningGlow

val EmergencyRed = LabOrange
val EmergencyRedBright = LabOrangeDeep
val EmergencyRedGlow = LabOrangeTint

val TextWhite = CyberTextWhite
val TextMuted = CyberTextMuted
val TextDim = CyberTextDim

val BorderGlass = CyberBorderRed
val BorderGlassActive = CyberBorderRedBright
val BorderCyanHighlight = CyberBorderRedBright
