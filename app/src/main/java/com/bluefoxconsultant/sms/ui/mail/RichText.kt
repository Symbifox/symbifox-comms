package com.bluefoxconsultant.sms.ui.mail

/**
 * A deliberately small rich-text layer: a markdown-lite body the user edits as
 * text, converted to HTML on send.
 *
 * A true WYSIWYG editor does not exist in Compose and writing one is a project
 * of its own. This gets bold, italic and lists — which is what business mail
 * actually uses — while the composer stays an ordinary text field, so
 * selection, autocorrect, dictation and paste all keep working. Those are
 * worth more on a phone than inline rendering.
 *
 * The output is still sanitized server-side; nothing here is a security
 * boundary.
 */
object RichText {

    /** Wrap the current selection, or insert markers for the user to type into. */
    fun applyMarker(text: String, start: Int, end: Int, marker: String): Pair<String, Int> {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        val selected = text.substring(safeStart, safeEnd)
        val wrapped = "$marker$selected$marker"
        val updated = text.replaceRange(safeStart, safeEnd, wrapped)
        // Caret between the markers when nothing was selected, after the
        // wrapped run otherwise.
        val caret = if (selected.isEmpty()) safeStart + marker.length else safeStart + wrapped.length
        return updated to caret
    }

    /** Prefix the line the caret sits on — for bullets. */
    fun applyLinePrefix(text: String, caret: Int, prefix: String): Pair<String, Int> {
        val safeCaret = caret.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (safeCaret - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        if (text.startsWith(prefix, lineStart)) {
            return text.removeRange(lineStart, lineStart + prefix.length) to
                (safeCaret - prefix.length).coerceAtLeast(0)
        }
        return text.replaceRange(lineStart, lineStart, prefix) to safeCaret + prefix.length
    }

    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val ITALIC = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")

    /**
     * markdown-lite → HTML. Escapes first, so anything the user typed that
     * looks like markup stays text; only our own markers become tags.
     */
    fun toHtml(source: String): String {
        if (source.isBlank()) return ""
        val escaped = source
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        val blocks = StringBuilder()
        var bulletsOpen = false
        for (rawLine in escaped.split("\n")) {
            val line = inline(rawLine.trim())
            val isBullet = rawLine.trimStart().startsWith("- ")
            if (isBullet) {
                if (!bulletsOpen) { blocks.append("<ul>"); bulletsOpen = true }
                blocks.append("<li>").append(inline(rawLine.trim().removePrefix("- "))).append("</li>")
                continue
            }
            if (bulletsOpen) { blocks.append("</ul>"); bulletsOpen = false }
            if (line.isBlank()) continue
            blocks.append("<p style=\"margin:0 0 12px 0;\">").append(line).append("</p>")
        }
        if (bulletsOpen) blocks.append("</ul>")
        return blocks.toString()
    }

    private fun inline(text: String): String =
        ITALIC.replace(BOLD.replace(text) { "<strong>${it.groupValues[1]}</strong>" }) {
            "<em>${it.groupValues[1]}</em>"
        }

    /** True when the body carries formatting worth sending as HTML. */
    fun hasFormatting(source: String): Boolean =
        BOLD.containsMatchIn(source) || ITALIC.containsMatchIn(source) ||
            source.lineSequence().any { it.trimStart().startsWith("- ") }
}
