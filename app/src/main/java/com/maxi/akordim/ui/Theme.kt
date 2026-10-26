package com.maxi.akordim.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Vintage {
    val Paper = Color(0xFFF3E9D2)
    val PaperDark = Color(0xFFE6D6B4)
    val Cream = Color(0xFFFBF6EA)
    val Ink = Color(0xFF3B2A1A)
    val InkSoft = Color(0xFF7A6248)
    val Mustard = Color(0xFFC8963E)
    val Rust = Color(0xFFA4462B)
    val Olive = Color(0xFF6B7F3A)
    val Teal = Color(0xFF3F6E6A)
}

private val Serif = FontFamily.Serif

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
        colorScheme = lightColorScheme(
            primary = Vintage.Rust,
            onPrimary = Vintage.Cream,
            primaryContainer = Vintage.Mustard,
            onPrimaryContainer = Vintage.Ink,
            secondary = Vintage.Olive,
            onSecondary = Vintage.Cream,
            secondaryContainer = Vintage.PaperDark,
            onSecondaryContainer = Vintage.Ink,
            tertiary = Vintage.Teal,
            background = Vintage.Paper,
            onBackground = Vintage.Ink,
            surface = Vintage.Cream,
            onSurface = Vintage.Ink,
            surfaceVariant = Vintage.PaperDark,
            onSurfaceVariant = Vintage.InkSoft,
            surfaceContainer = Vintage.PaperDark,
            surfaceContainerHigh = Vintage.Cream,
            surfaceContainerHighest = Vintage.Cream,
            surfaceContainerLow = Vintage.Paper,
            outline = Vintage.InkSoft,
            outlineVariant = Vintage.PaperDark,
        ),
        typography = typography,
        content = content,
    )
}
