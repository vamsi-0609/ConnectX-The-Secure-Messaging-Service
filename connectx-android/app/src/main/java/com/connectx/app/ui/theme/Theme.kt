package com.connectx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * ConnectX design tokens that Material3's ColorScheme has no slot for
 * (success, warning, online-presence, and the two message-bubble surfaces).
 * Exposed the same way MaterialTheme.colorScheme is, via a CompositionLocal,
 * so future screens read `ConnectXTheme.extendedColors.xxx` instead of
 * hardcoding a raw Color value.
 */
data class ConnectXExtendedColors(
    val success: Color,
    val warning: Color,
    val presenceOnline: Color,
    /** Sent-message bubble background (React: gradient indigo-600 -> violet-600). */
    val messageSentGradient: Brush,
    val onMessageSent: Color,
    /**
     * Received-message bubble background. NOTE: the React frontend's
     * MessageBubble.tsx uses a fixed dark-slate bubble (bg-slate-800/95) for
     * received messages with no separate light-mode class -- i.e. this specific
     * value could not be confidently determined from the existing UI for light
     * mode. Rather than copy a dark bubble onto a light background (which the
     * React app itself may simply not have addressed), this token uses the
     * theme's own surfaceVariant for light mode and the React app's literal
     * slate-800 value for dark mode. This is a NEW native decision, not a
     * carried-over one -- revisit if/when N5 (Messaging) implements real
     * message bubbles and this gap can be resolved against actual product intent.
     */
    val messageReceived: Color,
    val onMessageReceived: Color
)

private val LightExtendedColors = ConnectXExtendedColors(
    success = ConnectXSuccess,
    warning = ConnectXWarning,
    presenceOnline = ConnectXSuccess,
    messageSentGradient = Brush.linearGradient(listOf(ConnectXIndigoSecondary, ConnectXVioletPrimary)),
    onMessageSent = ConnectXOnColorLight,
    messageReceived = ConnectXLightSurfaceVariant,
    onMessageReceived = ConnectXLightTextPrimary
)

private val DarkExtendedColors = ConnectXExtendedColors(
    success = ConnectXSuccess,
    warning = ConnectXWarning,
    presenceOnline = ConnectXSuccess,
    messageSentGradient = Brush.linearGradient(listOf(ConnectXIndigoSecondary, ConnectXVioletPrimary)),
    onMessageSent = ConnectXOnColorLight,
    messageReceived = Color(0xFF262F41), // React: bg-slate-800/95 flattened over --bg-chat
    onMessageReceived = ConnectXDarkTextPrimary
)

private val LocalConnectXExtendedColors = staticCompositionLocalOf { LightExtendedColors }

private val ConnectXLightColorScheme = lightColorScheme(
    primary = ConnectXVioletPrimary,
    onPrimary = ConnectXOnColorLight,
    secondary = ConnectXIndigoSecondary,
    onSecondary = ConnectXOnColorLight,
    background = ConnectXLightBackground,
    onBackground = ConnectXLightTextPrimary,
    surface = ConnectXLightSurface,
    onSurface = ConnectXLightTextPrimary,
    surfaceVariant = ConnectXLightSurfaceVariant,
    onSurfaceVariant = ConnectXLightTextSecondary,
    outline = ConnectXLightOutline,
    error = ConnectXError,
    onError = ConnectXOnColorLight
)

private val ConnectXDarkColorScheme = darkColorScheme(
    primary = ConnectXVioletPrimaryHover,
    onPrimary = ConnectXOnColorLight,
    secondary = ConnectXIndigoSecondary,
    onSecondary = ConnectXOnColorLight,
    background = ConnectXDarkBackground,
    onBackground = ConnectXDarkTextPrimary,
    surface = ConnectXDarkSurface,
    onSurface = ConnectXDarkTextPrimary,
    surfaceVariant = ConnectXDarkSurfaceVariant,
    onSurfaceVariant = ConnectXDarkTextSecondary,
    outline = ConnectXDarkOutline,
    error = ConnectXError,
    onError = ConnectXOnColorLight
)

/**
 * ConnectX Material theme.
 *
 * Unlike the stock Compose template this project started from, Android 12+
 * dynamic color (deriving the palette from the user's wallpaper) is
 * deliberately NOT supported here at all -- it would override the ConnectX
 * brand identity (the violet/indigo palette above) with an arbitrary
 * per-device palette, directly contradicting this phase's goal of preserving
 * ConnectX's existing visual identity. This is a deliberate N1.8 decision,
 * not an oversight.
 */
@Composable
fun ConnectXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ConnectXDarkColorScheme else ConnectXLightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalConnectXExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ConnectXTypography,
            shapes = ConnectXShapes,
            content = content
        )
    }
}

/** Access point for ConnectX design tokens not covered by MaterialTheme.colorScheme. */
object ConnectXExtendedTheme {
    val colors: ConnectXExtendedColors
        @Composable
        get() = LocalConnectXExtendedColors.current
}
