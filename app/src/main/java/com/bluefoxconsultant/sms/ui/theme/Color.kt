package com.bluefoxconsultant.sms.ui.theme

import androidx.compose.ui.graphics.Color

// The accent is NOT a constant any more: it comes from the connected Odoo
// instance (see BrandStore) and falls back to Symbifox. Use `BrandAccent`
// from Theme.kt in composables — never a literal.
val BfAnthracite = Color(0xFF2D3031)

val IncomingBubble = Color(0xFFECEFF1)
val IncomingText = Color(0xFF1A1C1E)
val SurfaceGrey = Color(0xFFF2F4F5)
val SubtitleGrey = Color(0xFF6B7174)

// Dark scheme (bf_blue stays the accent).
val DarkBackground = Color(0xFF121417)
val DarkSurface = Color(0xFF1A1D21)
val DarkSurfaceVariant = Color(0xFF2A2F34)
val DarkOnSurface = Color(0xFFE3E5E8)
val DarkOnSurfaceVariant = Color(0xFF9BA1A6)
