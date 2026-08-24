package com.connectx.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Raw ConnectX brand palette, carried over from the existing React frontend's
 * Tailwind design tokens (connectx-frontend/tailwind.config.js, src/index.css,
 * and the violet/indigo values used throughout components/ and features/auth/).
 * Do not reference these directly from screens/components -- consume them
 * through ConnectXTheme's MaterialTheme.colorScheme and ConnectXExtendedColors
 * instead, so a future palette change only has to happen here.
 */

// Brand -- primary CTA color (React: bg-violet-600 / hover:bg-violet-500)
val ConnectXVioletPrimary = Color(0xFF7C3AED) // violet-600
val ConnectXVioletPrimaryHover = Color(0xFF8B5CF6) // violet-500

// Brand -- secondary/gradient-partner color (React: avatar + sent-bubble gradient start)
val ConnectXIndigoSecondary = Color(0xFF4F46E5) // indigo-600

// Semantic
val ConnectXSuccess = Color(0xFF10B981) // emerald-500 -- also used for online presence
val ConnectXWarning = Color(0xFFF59E0B) // amber-500
val ConnectXError = Color(0xFFF43F5E) // rose-500

// Light theme surfaces/text (React: src/index.css :root)
val ConnectXLightBackground = Color(0xFFF8FAFC) // --bg-main
val ConnectXLightSurface = Color(0xFFFFFFFF) // --bg-card
val ConnectXLightSurfaceVariant = Color(0xFFF1F5F9) // --bg-sidebar
val ConnectXLightOutline = Color(0xFFE2E8F0) // --border-color
val ConnectXLightTextPrimary = Color(0xFF0F172A) // --text-primary
val ConnectXLightTextSecondary = Color(0xFF64748B) // --text-secondary

// Dark theme surfaces/text (React: src/index.css .dark)
val ConnectXDarkBackground = Color(0xFF090D16) // --bg-main
val ConnectXDarkSurface = Color(0xFF1E293B) // --bg-card
val ConnectXDarkSurfaceVariant = Color(0xFF0F172A) // --bg-sidebar / --bg-rail
val ConnectXDarkOutline = Color(0xFF334155) // --border-color
val ConnectXDarkTextPrimary = Color(0xFFF8FAFC) // --text-primary
val ConnectXDarkTextSecondary = Color(0xFF94A3B8) // --text-secondary

// On-color (text/icon on top of a filled primary/error surface)
val ConnectXOnColorLight = Color(0xFFFFFFFF)
