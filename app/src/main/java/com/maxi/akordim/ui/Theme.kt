package com.maxi.akordim.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** "Night stage" palette: deep indigo, coral for what is playing, mint for highlights. */
object Stage {
    val Paper = Color(0xFF13142A)      // screen background
    val PaperDark = Color(0xFF2B2E52)  // borders, tracks, secondary surfaces
    val Cream = Color(0xFF1E2040)      // cards
    val Bar = Color(0xFF0A0B1A)        // top / bottom bars
    val Ink = Color(0xFFEEEDFF)        // main text
    val InkSoft = Color(0xFF9C9FCB)    // secondary text
    val Mustard = Color(0xFF3DDBB3)    // mint highlight
    val Rust = Color(0xFFFF6B5A)       // coral accent
    val Olive = Color(0xFF8BE07B)
    val Teal = Color(0xFF7DB2FF)       // chords you edited
    val OnAccent = Color(0xFF14101C)
    val KeyWhite = Color(0xFFF4F3FF)
    val KeyBlack = Color(0xFF0A0B1A)
}

private val Serif = FontFamily.SansSerif

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 56.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun AkordimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Stage.Rust,
            onPrimary = Stage.OnAccent,
            primaryContainer = Stage.Mustard,
            onPrimaryContainer = Stage.OnAccent,
            secondary = Stage.Olive,
            onSecondary = Stage.OnAccent,
            secondaryContainer = Stage.PaperDark,
            onSecondaryContainer = Stage.Ink,
            tertiary = Stage.Teal,
            background = Stage.Paper,
            onBackground = Stage.Ink,
            surface = Stage.Cream,
            onSurface = Stage.Ink,
            surfaceVariant = Stage.PaperDark,
            onSurfaceVariant = Stage.InkSoft,
            surfaceContainer = Stage.Cream,
            surfaceContainerHigh = Stage.Cream,
            surfaceContainerHighest = Stage.Cream,
            surfaceContainerLow = Stage.Paper,
            outline = Stage.InkSoft,
            outlineVariant = Stage.PaperDark,
        ),
        typography = typography,
        content = content,
    )
}
