@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluefoxconsultant.sms.data.MailConfig
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

/** Human labels for the `kind` values `/config` advertises in `spawn_kinds`. */
private val SPAWN_LABELS = mapOf(
    "task" to "Créer une tâche",
    "ticket" to "Créer un billet",
    "lead" to "Créer une piste",
    "expense" to "Créer une dépense",
    "bill" to "Créer une facture fournisseur",
    "invoice" to "Créer une facture client",
)

/**
 * Long-press menu on a conversation: triage plus the Odoo-side verbs.
 *
 * The creation entries come from `spawn_kinds` rather than a hardcoded list —
 * an instance without Helpdesk or CRM simply doesn't offer those, instead of
 * offering them and failing.
 */
@Composable
fun MailActionsSheet(
    message: MailMessage,
    config: MailConfig,
    onDismiss: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onMarkRead: () -> Unit,
    onSnooze: (Long) -> Unit,
    onSpawn: (String) -> Unit,
    onRoute: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = message.displaySubject,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = message.correspondent,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            if (message.isHandled) {
                SheetItem(Icons.Filled.Inbox, "Remettre en boîte de réception", onRestore)
            } else {
                SheetItem(Icons.Filled.Archive, "Archiver", onArchive)
            }
            if (message.isUnread) {
                SheetItem(Icons.Filled.Drafts, "Marquer comme lu", onMarkRead)
            }

            if (config.snoozePresets.isNotEmpty() && !message.isHandled) {
                HorizontalDivider()
                SectionLabel("Reporter")
                config.snoozePresets.forEach { preset ->
                    SheetItem(Icons.Filled.Schedule, preset.label) { onSnooze(preset.untilMs) }
                }
            }

            if (onRoute != null && config.routableModels.isNotEmpty()) {
                HorizontalDivider()
                SheetItem(Icons.Filled.Link, "Router vers un dossier", onRoute)
            }

            val kinds = config.spawnKinds.filter { it in SPAWN_LABELS }
            if (kinds.isNotEmpty()) {
                HorizontalDivider()
                SectionLabel("Créer dans Odoo")
                kinds.forEach { kind ->
                    SheetItem(Icons.AutoMirrored.Filled.PlaylistAdd, SPAWN_LABELS.getValue(kind)) { onSpawn(kind) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = BrandAccent)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 15.sp)
    }
}

@Composable
fun SheetSpacer() {
    Spacer(Modifier.height(8.dp))
}
