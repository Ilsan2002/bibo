package com.bibo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibo.R

/*
 * Bibo's visual identity. Dynamic (Monet) color is deliberately OFF: it produced a
 * black/grey/generic-blue app with no personality, indistinguishable from an unstyled
 * template. The app is a sharp personal coach — the palette is a deep blue-black with
 * a single hot amber accent that means "energy / action", green for completion, and
 * muted steel for secondary data. One accent, used with intent.
 */

// Space Grotesk (OFL), a variable font: Compose sets the wght axis from the weight on
// API 26+, so one file covers every weight below.
val Grotesk = FontFamily(
    Font(R.font.space_grotesk, weight = FontWeight.Normal),
    Font(R.font.space_grotesk, weight = FontWeight.Medium),
    Font(R.font.space_grotesk, weight = FontWeight.SemiBold),
    Font(R.font.space_grotesk, weight = FontWeight.Bold),
)

private val Amber = Color(0xFFFF6B3D)
private val AmberDeep = Color(0xFFE2521F)
private val Mint = Color(0xFF4FD1A0)
private val Coral = Color(0xFFFF5C5C)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A0A04),
    primaryContainer = Color(0xFF9E3E18),
    onPrimaryContainer = Color(0xFFFFE0D4),
    secondary = Color(0xFF9BB0D0),
    onSecondary = Color(0xFF0F1626),
    secondaryContainer = Color(0xFF26314A),
    onSecondaryContainer = Color(0xFFD5E0F5),
    tertiary = Mint,
    onTertiary = Color(0xFF00301F),
    tertiaryContainer = Color(0xFF0F3A2A),
    onTertiaryContainer = Color(0xFFA8F0D2),
    error = Coral,
    onError = Color(0xFF2B0606),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFB4B4),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFEEF1F7),
    surface = Color(0xFF0B0D12),
    onSurface = Color(0xFFEEF1F7),
    surfaceVariant = Color(0xFF232938),
    onSurfaceVariant = Color(0xFFA7B0C2),
    surfaceContainerLowest = Color(0xFF07080C),
    surfaceContainerLow = Color(0xFF10131A),
    surfaceContainer = Color(0xFF151922),
    surfaceContainerHigh = Color(0xFF1C2130),
    surfaceContainerHighest = Color(0xFF232938),
    outline = Color(0xFF4A536A),
    outlineVariant = Color(0xFF2C3344),
    inverseSurface = Color(0xFFEEF1F7),
    inverseOnSurface = Color(0xFF15181F),
    inversePrimary = AmberDeep,
)

private val LightScheme = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCCF),
    onPrimaryContainer = Color(0xFF3D1A0E),
    secondary = Color(0xFF4A5A78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE4F5),
    onSecondaryContainer = Color(0xFF141C2C),
    tertiary = Color(0xFF117A56),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBFF0DC),
    onTertiaryContainer = Color(0xFF00301F),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF8F5),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFFAF8F5),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE6E4E0),
    onSurfaceVariant = Color(0xFF4F5561),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F2EE),
    surfaceContainer = Color(0xFFEFECE8),
    surfaceContainerHigh = Color(0xFFE9E6E1),
    surfaceContainerHighest = Color(0xFFE3E0DA),
    outline = Color(0xFF7B8090),
    outlineVariant = Color(0xFFCBCFD8),
    inverseSurface = Color(0xFF2F3138),
    inverseOnSurface = Color(0xFFF1F0EC),
    inversePrimary = Amber,
)

private fun style(size: Int, weight: FontWeight, lineHeight: Int, letterSpacing: Float = 0f) =
    TextStyle(
        fontFamily = Grotesk, fontWeight = weight,
        fontSize = size.sp, lineHeight = lineHeight.sp, letterSpacing = letterSpacing.sp,
    )

// Big numbers and screen titles are set tight and heavy — that's where the app's
// confidence shows ("6h 55m", "Focus"). Body stays airy for long mentor messages.
private val BiboTypography = Typography(
    displayLarge = style(54, FontWeight.Bold, 60, -1.5f),
    displayMedium = style(44, FontWeight.Bold, 50, -1f),
    displaySmall = style(36, FontWeight.Bold, 42, -0.5f),
    headlineLarge = style(32, FontWeight.Bold, 38, -0.5f),
    headlineMedium = style(28, FontWeight.Bold, 34, -0.25f),
    headlineSmall = style(24, FontWeight.Bold, 30, -0.25f),
    titleLarge = style(21, FontWeight.SemiBold, 27),
    titleMedium = style(17, FontWeight.SemiBold, 23),
    titleSmall = style(15, FontWeight.SemiBold, 20),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(14, FontWeight.Normal, 21),
    bodySmall = style(13, FontWeight.Normal, 18),
    labelLarge = style(14, FontWeight.Medium, 20),
    labelMedium = style(12, FontWeight.Medium, 16, 0.3f),
    labelSmall = style(11, FontWeight.Medium, 15, 0.4f),
)

private val BiboShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun BiboTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = BiboTypography,
        shapes = BiboShapes,
        content = content,
    )
}
