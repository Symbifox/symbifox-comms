package com.bluefoxconsultant.sms.ui.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.bluefoxconsultant.sms.sip.SipEngine
import com.bluefoxconsultant.sms.data.PhoneConfig
import com.bluefoxconsultant.sms.network.ApiException
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

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

    IconButton(onClick = { asking = true }, enabled = enabled && !placing) {
        Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.common_call))
    }

    if (asking) {
        CallDialog(
            number = number,
            display = display,
            snackbar = snackbar,
            onDismiss = { asking = false },
            onPlacing = { placing = it },
        )
    }
}

/**
 * The confirmation, shared by the in-conversation button and the keypad.
 *
 * It states the two things nobody can guess: something else rings first, and
 * the number the other end sees is not this phone's. When both a callback
 * number and a desk extension exist, it also asks which one should ring.
 */
@Composable
fun CallDialog(
    number: String,
    display: String,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
    onPlacing: (Boolean) -> Unit = {},
) {
    val config by Graph.phoneStore.config.collectAsStateWithLifecycle()
    val sip by SipEngine.state.collectAsStateWithLifecycle()
    // Quand le poste de l'appareil est prêt, c'est LUI qu'on préselectionne :
    // c'est le seul mode où la conversation a lieu sur l'appareil qu'on tient.
    // Les autres restent offerts — on peut vouloir faire sonner son cellulaire.
    var ring by remember(config.defaultRing, sip.ready) {
        mutableStateOf(if (sip.ready) RING_DEVICE else config.defaultRing)
    }
    var placing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Le snackbar part d'une coroutine, hors composition : la phrase s'y lit
    // dans les ressources du contexte plutôt que par `stringResource`.
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!placing) onDismiss() },
        title = { Text(stringResource(R.string.call_dialog_title, display.ifBlank { number })) },
        text = {
            Column {
                Text(
                    stringResource(
                        if (ring == RING_DEVICE) R.string.call_dialog_on_device
                        else R.string.call_dialog_via_pbx,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // The carrier only lets a trunk present a number the account
                // owns, so the ringing phone shows the business line — never the
                // person being called. Unexpected, and worth saying before the
                // phone rings rather than after it went unanswered.
                if (ring == PhoneConfig.RING_CALLBACK && config.callbackShowsAs.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.call_dialog_callback_shows_as, config.callbackShowsAs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Un seul mode possible = aucun choix à faire. Les options ne
                // s'affichent qu'à partir de deux.
                val modes = listOfNotNull(
                    if (sip.ready) RING_DEVICE else null,
                    if (config.canRingCallback) PhoneConfig.RING_CALLBACK else null,
                    if (config.canRingExtension) PhoneConfig.RING_EXTENSION else null,
                )
                if (modes.size > 1) {
                    if (RING_DEVICE in modes) {
                        RingChoice(
                            label = stringResource(R.string.call_ring_device),
                            selected = ring == RING_DEVICE,
                            onSelect = { ring = RING_DEVICE },
                        )
                    }
                    if (PhoneConfig.RING_CALLBACK in modes) {
                        RingChoice(
                            label = stringResource(R.string.call_ring_callback, config.callbackNumber),
                            selected = ring == PhoneConfig.RING_CALLBACK,
                            onSelect = { ring = PhoneConfig.RING_CALLBACK },
                        )
                    }
                    if (PhoneConfig.RING_EXTENSION in modes) {
                        RingChoice(
                            label = stringResource(R.string.call_ring_extension, config.extension),
                            selected = ring == PhoneConfig.RING_EXTENSION,
                            onSelect = { ring = PhoneConfig.RING_EXTENSION },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !placing,
                onClick = {
                    // ⚠️ Le poste de l'appareil ne passe PAS par /call : il
                    // compose lui-même par SIP. Le clic-pour-appeler monte deux
                    // jambes (le PBX rappelle, puis compose) ; ici il n'en faut
                    // qu'une, et elle part tout de suite.
                    if (ring == RING_DEVICE) {
                        SipEngine.call(number)
                        onDismiss()
                        return@TextButton
                    }
                    placing = true
                    onPlacing(true)
                    scope.launch {
                        try {
                            val response = Graph.phone.call(number, ring)
                            onDismiss()
                            snackbar.showSnackbar(
                                if (response.showsAs.isNotBlank())
                                    context.getString(
                                        R.string.call_pbx_ringing_shows_as,
                                        response.ringLabel,
                                        response.showsAs,
                                    )
                                else context.getString(R.string.call_pbx_ringing, response.ringLabel),
                            )
                        } catch (e: ApiException) {
                            // The server sends a sentence, not a code: unknown
                            // number, no callback number set, rate limit. Show it.
                            onDismiss()
                            snackbar.showSnackbar(e.err)
                        } catch (e: Exception) {
                            onDismiss()
                            snackbar.showSnackbar(context.getString(R.string.call_failed))
                        } finally {
                            placing = false
                            onPlacing(false)
                        }
                    }
                },
            ) {
                Text(stringResource(if (placing) R.string.call_placing else R.string.common_call))
            }
        },
        dismissButton = {
            TextButton(enabled = !placing, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Le mode « l'appareil est le poste ».
 *
 * Il ne voyage jamais jusqu'au serveur : /call ne connaît que `callback` et
 * `extension`. C'est une valeur d'interface qui signifie « ne demande rien au
 * PBX, compose toi-même ».
 */
private const val RING_DEVICE = "device"

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
