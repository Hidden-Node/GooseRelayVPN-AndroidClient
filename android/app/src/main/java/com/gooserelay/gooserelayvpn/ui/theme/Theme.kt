package com.gooserelay.gooserelayvpn.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import com.gooserelay.gooserelayvpn.R

val ConnectedGreen = MdvColor.PrimaryContainer
val DisconnectedRed = MdvColor.Error
val ConnectingAmber = MdvColor.PrimaryDim

private val StitchColorScheme = darkColorScheme(
    primary = MdvColor.Primary,
    onPrimary = Color(0xFF00363D),
    primaryContainer = MdvColor.PrimaryContainer,
    onPrimaryContainer = Color(0xFF001F24),
    secondary = MdvColor.Secondary,
    onSecondary = Color(0xFF243141),
    background = MdvColor.Background,
    onBackground = MdvColor.OnSurface,
    surface = MdvColor.Surface,
    onSurface = MdvColor.OnSurface,
    surfaceVariant = MdvColor.SurfaceHigh,
    onSurfaceVariant = MdvColor.OnSurfaceVariant,
    error = MdvColor.Error,
    onError = MdvColor.OnError,
    errorContainer = MdvColor.ErrorContainer,
    onErrorContainer = MdvColor.Error
)

private val VazirmatnFontFamily = FontFamily(
    Font(R.font.vazirmatn_ui_fd_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_ui_fd_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_ui_fd_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_ui_fd_bold, FontWeight.Bold)
)

private fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.5.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.4.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.2.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.5.sp
    )
)

@Composable
fun GooseRelayVPNTheme(
    content: @Composable () -> Unit
) {
    val locales = ConfigurationCompat.getLocales(LocalConfiguration.current)
    val isPersian = locales.get(0)?.language == "fa"
    MaterialTheme(
        colorScheme = StitchColorScheme,
        typography = if (isPersian) AppTypography.withFontFamily(VazirmatnFontFamily) else AppTypography,
        content = content
    )
}
