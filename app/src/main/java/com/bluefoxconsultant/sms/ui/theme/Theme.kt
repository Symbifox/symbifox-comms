package com.bluefoxconsultant.sms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.data.Brand
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.ThemeMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

/**
 * Lexend — the Blue Fox typeface, the same one the branded documents use, so
 * the app reads as part of the same house rather than as a generic Android
 * app. Bundled rather than downloaded: it must render identically offline and
 * on a device with no Play Services.
 */
private val Lexend = FontFamily(
    Font(R.font.lexend_regular, FontWeight.Normal),
    Font(R.font.lexend_semibold, FontWeight.SemiBold),
    Font(R.font.lexend_semibold, FontWeight.Medium),
    Font(R.font.lexend_bold, FontWeight.Bold),
)

/**
 * Applied across the whole type scale rather than to a few headings: a mixed
 * app is more conspicuous than a fully unbranded one. Lexend runs slightly
 * wide, so tracking is eased very slightly on the dense list styles.
 */
private val BfTypography = Typography().let { base ->
    fun TextStyle.lexend(tracking: Float = 0f) =
        copy(fontFamily = Lexend, letterSpacing = (tracking).sp)
    Typography(
        displayLarge = base.displayLarge.lexend(),
        displayMedium = base.displayMedium.lexend(),
        displaySmall = base.displaySmall.lexend(),
        headlineLarge = base.headlineLarge.lexend(),
        headlineMedium = base.headlineMedium.lexend(),
        headlineSmall = base.headlineSmall.lexend(),
        titleLarge = base.titleLarge.lexend(),
        titleMedium = base.titleMedium.lexend(),
        titleSmall = base.titleSmall.lexend(),
        bodyLarge = base.bodyLarge.lexend(),
        bodyMedium = base.bodyMedium.lexend(),
        bodySmall = base.bodySmall.lexend(-0.1f),
        labelLarge = base.labelLarge.lexend(),
        labelMedium = base.labelMedium.lexend(-0.1f),
        labelSmall = base.labelSmall.lexend(-0.1f),
    )
}

/**
 * The instance's accent colour, readable from any composable.
 *
 * Deliberately a CompositionLocal rather than a constant: the same build runs
 * against instances belonging to different companies, and each should see its
 * own colours. Reading `MaterialTheme.colorScheme.primary` works too — this
 * exists so call sites read as intent ("the brand accent") rather than as a
 * Material slot that something else might legitimately want to change.
 */
val LocalBrandAccent = compositionLocalOf { Color(Brand.SYMBIFOX_PRIMARY) }

val BrandAccent: Color
    @Composable get() = LocalBrandAccent.current

// Fixed Blue Fox scheme. NO dynamic color / Material You — the primary is always bf_blue.
private fun lightScheme(accent: Color, accentDark: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent,
    onPrimaryContainer = Color.White,
    secondary = accentDark,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = BfAnthracite,
    surface = Color.White,
    onSurface = BfAnthracite,
    surfaceVariant = SurfaceGrey,
    onSurfaceVariant = SubtitleGrey,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private fun darkScheme(accent: Color, accentDark: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accentDark,
    onPrimaryContainer = Color.White,
    secondary = accent,
    onSecondary = Color.White,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
fun BfSmsTheme(content: @Composable () -> Unit) {
    val mode by Graph.tokenStore.themeModeFlow.collectAsState()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val brand by Graph.brandStore.brandFlow.collectAsState()
    val accent = Color(brand.primary)
    val accentDark = Color(brand.dark)

    MaterialTheme(
        colorScheme = if (dark) darkScheme(accent, accentDark)
        else lightScheme(accent, accentDark),
        typography = BfTypography,
    ) {
        CompositionLocalProvider(LocalBrandAccent provides accent, content = content)
    }
}
