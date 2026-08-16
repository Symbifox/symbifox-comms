package com.bluefoxconsultant.sms.ui

import android.text.format.DateUtils
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// Deterministic, legible avatar palette (readable with white initials, light + dark).
private val AvatarPalette = listOf(
    Color(0xFF29ABE2), // bf blue
    Color(0xFF2E7D32), // green
    Color(0xFFC2185B), // pink
    Color(0xFF6A4FB6), // indigo
    Color(0xFFE65100), // deep orange
    Color(0xFF00838F), // teal
    Color(0xFF5D4037), // brown
    Color(0xFFAD1457), // magenta
    Color(0xFF3949AB), // blue-indigo
    Color(0xFF00695C), // dark teal
)

fun avatarColor(key: String): Color {
    if (key.isBlank()) return AvatarPalette.first()
    return AvatarPalette[abs(key.hashCode()) % AvatarPalette.size]
}

fun relativeTime(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0) return ""
    return DateUtils.getRelativeTimeSpanString(
        epochMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

fun clockTime(epochMs: Long): String {
    if (epochMs <= 0) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
}

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> ""
        parts.size == 1 -> parts[0].filter { it.isLetterOrDigit() }.take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}
