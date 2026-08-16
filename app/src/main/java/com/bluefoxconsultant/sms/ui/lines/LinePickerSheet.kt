@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.lines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluefoxconsultant.sms.data.Line
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

/**
 * Picks which of the account's numbers to act from.
 *
 * Deliberately generic rather than an SMS detail: "send from which number" and
 * "call from which number" are the same question, and voice is coming. The
 * caller decides what the choice means — this only renders the lines and
 * reports the pick.
 *
 * @param disabledReason when non-null, called per line to grey out ones that
 *   can't serve the current purpose (a line without SMS, later one without
 *   voice) and say why.
 */
@Composable
fun LinePickerSheet(
    lines: List<Line>,
    selectedLineId: Int?,
    title: String = "Envoyer depuis",
    subtitle: String? = null,
    disabledReason: (Line) -> String? = { null },
    onDismiss: () -> Unit,
    onPick: (Line) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            lines.forEach { line ->
                val reason = disabledReason(line)
                LineRow(
                    line = line,
                    selected = line.id == selectedLineId,
                    disabledReason = reason,
                    onClick = { if (reason == null) onPick(line) },
                )
            }
        }
    }
}

@Composable
private fun LineRow(
    line: Line,
    selected: Boolean,
    disabledReason: String?,
    onClick: () -> Unit,
) {
    val enabled = disabledReason == null
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Phone,
            contentDescription = null,
            tint = if (enabled) BrandAccent else contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = line.label.ifBlank { line.did },
                fontSize = 15.sp,
                color = contentColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = disabledReason ?: line.did,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Sélectionné", tint = BrandAccent)
        }
    }
}
