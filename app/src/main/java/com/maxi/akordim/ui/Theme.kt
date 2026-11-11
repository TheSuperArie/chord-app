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

/**
 * "Night stage" palette, tuned for small low-contrast screens: near-black blue background,
 * clearly lighter cards, bright text, warm amber for what is playing, turquoise for highlights.
 */
object Stage {
    val Paper = Color(0xFF0E1018)      // screen background
    val PaperDark = Color(0xFF363D58)  // borders, tracks, secondary surfaces
    val Cream = Color(0xFF1B2030)      // cards
    val Bar = Color(0xFF07080D)        // top / bottom bars
    val Ink = Color(0xFFF5F6FA)        // main text
    val InkSoft = Color(0xFFB6BCD2)    // secondary text
    val Mustard = Color(0xFF35D6C0)    // turquoise highlight
    val Rust = Color(0xFFFFB23F)       // amber accent
    val Olive = Color(0xFF9BE36B)
    val Teal = Color(0xFF6FB4FF)       // chords you edited
    val OnAccent = Color(0xFF10121A)
    val KeyWhite = Color(0xFFF5F6FA)
    val KeyBlack = Color(0xFF07080D)
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
    ) {
        // text without an explicit colour must be light on the dark background
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Stage.Ink,
            content = content,
        )
    }
}


fun sectionColor(kind: com.maxi.akordim.analysis.SectionKind): Color = when (kind) {
    com.maxi.akordim.analysis.SectionKind.CHORUS -> Color(0xFFFF7A6B)
    com.maxi.akordim.analysis.SectionKind.VERSE -> Color(0xFF6FB4FF)
    com.maxi.akordim.analysis.SectionKind.PRECHORUS -> Color(0xFFC39BFF)
    com.maxi.akordim.analysis.SectionKind.BRIDGE -> Color(0xFFF58BC8)
    com.maxi.akordim.analysis.SectionKind.INTRO, com.maxi.akordim.analysis.SectionKind.OUTRO -> Color(0xFF9AA3BF)
    com.maxi.akordim.analysis.SectionKind.PART -> Stage.Mustard
}
