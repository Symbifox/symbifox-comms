package com.bluefoxconsultant.sms.ui.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.PhoneConfig
import com.bluefoxconsultant.sms.network.ApiException
import kotlinx.coroutines.launch

/**
 * Call button for a top app bar, with the confirmation the act deserves.
 *
 * The handset places nothing itself: the PBX rings the user's own device, and
 * only once that is answered does it dial [number], so the correspondent sees
 * the business line. Two consequences the dialog states plainly, because
 * neither is guessable: something else is about to ring, and the number shown
 * to the other end is not this phone's.
 *
 * Renders nothing when the instance has no phone module, when the user is not
 * a phone user, or when the PBX link is unconfigured — [PhoneConfig.enabled]
 * folds all three into one answer, decided server-side.
 */
@Composable
fun CallAction(
    number: String,
    display: String,
    snackbar: SnackbarHostState,
    enabled: Boolean = true,
) {
    val config by Graph.phoneStore.config.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Graph.phoneStore.ensureLoaded() }

    if (!config.enabled || number.isBlank()) return

    var asking by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf(false) }
    var ring by remember(config.defaultRing) { mutableStateOf(config.defaultRing) }
    val scope = rememberCoroutineScope()

    IconButton(onClick = { asking = true }, enabled = enabled && !placing) {
        Icon(Icons.Filled.Call, contentDescription = "Appeler")
    }

    if (!asking) return

    AlertDialog(
        onDismissRequest = { if (!placing) asking = false },
        title = { Text("Appeler ${display.ifBlank { number }}") },
        text = {
            Column {
                Text(
                    "Le PBX fait d'abord sonner votre appareil. Décrochez, et il " +
                        "compose le numéro en affichant la ligne d'affaires.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Only worth a choice when both targets exist. With one, the
                // dialog stays a plain confirmation.
                if (config.canRingCallback && config.canRingExtension) {
                    RingChoice(
                        label = "Mon numéro de rappel (${config.callbackNumber})",
                        selected = ring == PhoneConfig.RING_CALLBACK,
                        onSelect = { ring = PhoneConfig.RING_CALLBACK },
                    )
                    RingChoice(
                        label = "Mon poste ${config.extension}",
                        selected = ring == PhoneConfig.RING_EXTENSION,
                        onSelect = { ring = PhoneConfig.RING_EXTENSION },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !placing,
                onClick = {
                    placing = true
                    scope.launch {
                        try {
                            val response = Graph.phone.call(number, ring)
                            asking = false
                            snackbar.showSnackbar(
                                "Le PBX fait sonner ${response.ringLabel}.")
                        } catch (e: ApiException) {
                            asking = false
                            // The server sends a sentence, not a code: unknown
                            // number, no callback number set, rate limit. Show it.
                            snackbar.showSnackbar(e.err)
                        } catch (e: Exception) {
                            asking = false
                            snackbar.showSnackbar("Appel impossible pour l'instant.")
                        } finally {
                            placing = false
                        }
                    }
                },
            ) { Text(if (placing) "Un instant…" else "Appeler") }
        },
        dismissButton = {
            TextButton(enabled = !placing, onClick = { asking = false }) {
                Text("Annuler")
            }
        },
    )
}

@Composable
private fun RingChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .selectable(selected = selected, onClick = onSelect)
            .padding(top = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
