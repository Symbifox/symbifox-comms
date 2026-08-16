package com.bluefoxconsultant.sms.ui.genfox

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Just enough Markdown for a chat bubble: bold, italic, inline code, bullets
 * and headings. The assistant writes Markdown because the desktop panel renders
 * it, so a phone showing raw asterisks would be the odd one out.
 *
 * ⚠️ It must survive **partial** input: while the answer streams in, a bold run
 * is routinely half-written. An unclosed marker is therefore printed as-is
 * rather than swallowing the rest of the paragraph.
 */
fun renderMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    val lines = source.split("\n")
    lines.forEachIndexed { index, rawLine ->
        var line = rawLine
        var bulletPrefix = ""
        var heading = false

        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        when {
            trimmed.startsWith("### ") || trimmed.startsWith("## ") ||
                trimmed.startsWith("# ") -> {
                heading = true
                line = trimmed.substringAfter(" ")
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                bulletPrefix = " ".repeat(indent) + "• "
                line = trimmed.drop(2)
            }
            Regex("^\\d+\\. ").containsMatchIn(trimmed) -> {
                bulletPrefix = " ".repeat(indent) + trimmed.substringBefore(" ") + " "
                line = trimmed.substringAfter(" ")
            }
        }

        if (bulletPrefix.isNotEmpty()) append(bulletPrefix)
        if (heading) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(line) }
        } else {
            inline(line)
        }
        if (index < lines.lastIndex) append("\n")
    }
}

/** Bold, italic and code inside one line. */
private fun AnnotatedString.Builder.inline(line: String) {
    var i = 0
    while (i < line.length) {
        val rest = line.substring(i)
        val marker = MARKERS.firstOrNull { rest.startsWith(it.open) }
        if (marker == null) {
            append(line[i])
            i++
            continue
        }
        val closeAt = line.indexOf(marker.close, startIndex = i + marker.open.length)
        if (closeAt < 0) {
            // Half-written while streaming: show the marker rather than
            // treating the rest of the line as styled.
            append(marker.open)
            i += marker.open.length
            continue
        }
        val inner = line.substring(i + marker.open.length, closeAt)
        withStyle(marker.style) { append(inner) }
        i = closeAt + marker.close.length
    }
}

private data class Marker(val open: String, val close: String, val style: SpanStyle)

// Order matters: ** must be tried before *, or bold would parse as two italics.
private val MARKERS = listOf(
    Marker("**", "**", SpanStyle(fontWeight = FontWeight.Bold)),
    Marker("`", "`", SpanStyle(fontFamily = FontFamily.Monospace)),
    Marker("*", "*", SpanStyle(fontStyle = FontStyle.Italic)),
    Marker("_", "_", SpanStyle(fontStyle = FontStyle.Italic)),
)
