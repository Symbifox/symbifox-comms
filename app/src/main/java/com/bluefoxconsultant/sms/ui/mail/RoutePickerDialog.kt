@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailConfig
import com.bluefoxconsultant.sms.data.RecordRef
import kotlinx.coroutines.delay

/**
 * Picks the Odoo record an email should be filed into.
 *
 * Model choices come from `routable_models` in `/config` — the server's
 * allowlist, filtered to what this instance has installed and the user may
 * read — so the dialog can never offer a target that `/route` will refuse.
 */
@Composable
fun RoutePickerDialog(
    config: MailConfig,
    onDismiss: () -> Unit,
    onPick: (model: String, recordId: Int) -> Unit,
) {
    val models = config.routableModels
    if (models.isEmpty()) {
        onDismiss()
        return
    }

    var model by remember { mutableStateOf(models.first().model) }
    var term by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RecordRef>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // Debounced search; under two characters the server returns nothing anyway.
    LaunchedEffect(model, term) {
        if (term.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        results = try {
            Graph.mail.records(model, term.trim())
        } catch (e: Exception) {
            emptyList()
        } finally {
            searching = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        title = { Text("Router vers un dossier") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    models.take(4).forEach { candidate ->
                        FilterChip(
                            selected = candidate.model == model,
                            onClick = { model = candidate.model; results = emptyList() },
                            label = { Text(candidate.label, fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text("Rechercher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                when {
                    searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Recherche…", fontSize = 13.sp)
                    }
                    term.trim().length < 2 -> Text(
                        "Tapez au moins deux caractères.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    results.isEmpty() -> Text(
                        "Aucun résultat.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(results, key = { it.id }) { record ->
                            Text(
                                text = record.name,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(model, record.id) }
                                    .padding(vertical = 12.dp),
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        },
    )
}
