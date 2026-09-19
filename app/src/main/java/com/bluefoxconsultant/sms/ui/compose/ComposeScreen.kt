@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.data.Contact
import com.bluefoxconsultant.sms.data.MediaPrep
import com.bluefoxconsultant.sms.data.OutgoingMedia
import com.bluefoxconsultant.sms.data.SharedContent
import com.bluefoxconsultant.sms.ui.phone.CallAction
import com.bluefoxconsultant.sms.ui.share.PiecesProposees
import com.bluefoxconsultant.sms.ui.speech.DictateButton
import com.bluefoxconsultant.sms.ui.speech.appendSpoken
import com.bluefoxconsultant.sms.ui.theme.BrandAccent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.asString

@Composable
fun ComposeScreen(
    onBack: () -> Unit,
    onSent: (Int) -> Unit,
    /** Ce qu'une autre app vient de partager, s'il y a lieu. */
    shared: SharedContent? = null,
    vm: ComposeViewModel = viewModel(),
) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Une seule fois, sur le contenu lui-même : recomposer ne doit pas
    // proposer la photo une deuxième fois. Rien n'est lu ici : les fichiers
    // attendent « Joindre » ou « Envoyer ».
    LaunchedEffect(shared) {
        shared?.let { vm.adopt(context, it) }
    }

    // OpenMultipleDocuments plutôt que GetMultipleContents : l'URI rendue est
    // lisible quel que soit le fournisseur, ce que GetContent ne garantit pas.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach { vm.attach(context, it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_new_message), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // Same field serves both: whoever you were about to text is
                    // whoever you were about to call.
                    CallAction(
                        number = vm.recipient,
                        display = vm.recipientName ?: vm.recipient,
                        snackbar = snackbar,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandAccent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (vm.lines.size > 1) {
                LineSelector(
                    lineLabel = vm.lines.firstOrNull { it.id == vm.selectedLineId }?.label
                        ?: stringResource(R.string.sms_compose_line),
                    lines = vm.lines,
                    onSelect = vm::selectLine,
                )
                Spacer(Modifier.height(14.dp))
            }

            OutlinedTextField(
                value = vm.recipient,
                onValueChange = vm::onRecipientChange,
                label = { Text(stringResource(R.string.sms_compose_to)) },
                placeholder = { Text(stringResource(R.string.sms_compose_recipient_hint)) },
                singleLine = true,
                supportingText = { vm.recipientName?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )

            if (vm.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        vm.suggestions.forEach { contact ->
                            SuggestionRow(contact) { vm.pickContact(contact) }
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = vm.message,
                onValueChange = vm::onMessageChange,
                label = { Text(stringResource(R.string.sms_compose_message_label)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    DictateButton(snackbar = snackbar) { spoken ->
                        vm.onMessageChange(appendSpoken(vm.message, spoken))
                    }
                },
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ⚠️ Grisé, jamais caché, quand la ligne ne fait pas de MMS :
                // un bouton absent laisserait croire que l'app ne sait pas
                // joindre de fichier, alors que c'est la LIGNE qui ne peut pas.
                IconButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = vm.lineDoesMms && vm.canAttachMore,
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.common_attach_file))
                }
                Text(
                    when {
                        !vm.lineDoesMms -> stringResource(R.string.sms_compose_line_no_mms)
                        vm.attachments.isEmpty() -> pluralStringResource(
                            R.plurals.sms_compose_attachments_up_to,
                            MediaPrep.MAX_PARTS,
                            MediaPrep.MAX_PARTS,
                        )
                        else -> "MMS · ${vm.attachments.size}/${MediaPrep.MAX_PARTS}"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (vm.attachments.isNotEmpty() || vm.preparing > 0) {
                MediaChips(
                    attachments = vm.attachments,
                    preparing = vm.preparing,
                    onRemove = vm::removeAttachment,
                )
            }
            PiecesProposees(
                proposees = vm.proposees,
                onJoindre = { vm.joindreProposee(context, it) },
                onEcarter = vm::ecarterProposee,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.send(context, onSent) },
                enabled = !vm.sending && vm.preparing == 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                if (vm.sending) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(stringResource(R.string.common_send), fontSize = 16.sp)
                }
            }

            vm.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it.asString(), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LineSelector(
    lineLabel: String,
    lines: List<com.bluefoxconsultant.sms.data.Line>,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            stringResource(R.string.sms_compose_from),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(lineLabel)
                Spacer(Modifier.size(6.dp))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                lines.forEach { line ->
                    DropdownMenuItem(
                        text = { Text(line.label) },
                        onClick = {
                            onSelect(line.id)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(contact: Contact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                contact.name.ifBlank { contact.bestNumber },
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (contact.bestNumber.isNotBlank()) {
                Text(
                    contact.bestNumber,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Les pièces jointes du MMS, avec leur poids APRÈS mise au gabarit.
 *
 * Le poids affiché est celui qui partira réellement : une photo de 4 Mo
 * ressort à quelques centaines de kilo-octets, et montrer le poids d'origine
 * ferait craindre un refus qui n'arrivera pas.
 */
@Composable
private fun MediaChips(
    attachments: List<OutgoingMedia>,
    preparing: Int,
    onRemove: (OutgoingMedia) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { media ->
            AssistChip(
                onClick = { onRemove(media) },
                label = {
                    Text("${media.filename} · ${humanSize(media.sizeBytes)}", fontSize = 12.sp)
                },
                leadingIcon = { Icon(Icons.Filled.AttachFile, null, Modifier.size(16.dp)) },
                trailingIcon = {
                    Icon(Icons.Filled.Close, stringResource(R.string.common_remove), Modifier.size(16.dp))
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        repeat(preparing) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.sms_compose_preparing), fontSize = 12.sp) },
                leadingIcon = {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

/** L'unité suit la langue (Mo, MB) et la décimale aussi (1,5 ou 1.5). */
@Composable
private fun humanSize(bytes: Int): String = when {
    bytes >= 1_048_576 -> stringResource(R.string.size_megabytes, bytes / 1_048_576.0)
    bytes >= 1024 -> stringResource(R.string.size_kilobytes, bytes / 1024)
    else -> stringResource(R.string.size_bytes, bytes)
}
