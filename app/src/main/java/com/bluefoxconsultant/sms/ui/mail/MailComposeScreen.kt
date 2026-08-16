@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

@Suppress("UNCHECKED_CAST")
private class ComposeVmFactory(
    private val mode: String,
    private val emailId: Int,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MailComposeViewModel(mode, emailId) as T
}

@Composable
fun MailComposeScreen(
    mode: String,
    emailId: Int,
    onBack: () -> Unit,
    onSent: () -> Unit,
) {
    val vm: MailComposeViewModel = viewModel(
        key = "$mode-$emailId",
        factory = ComposeVmFactory(mode, emailId),
    )
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    // OpenMultipleDocuments, not GetMultipleContents: it returns a durable,
    // readable URI for anything the system document picker can reach, which
    // GetContent does not guarantee across providers.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach { vm.attach(context, it) } }

    LaunchedEffect(vm.queuedOffline) {
        if (vm.queuedOffline) {
            snackbar.showSnackbar("Hors ligne — message en attente, il partira au retour du réseau.")
        }
    }

    LaunchedEffect(vm.error) {
        vm.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(vm.title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Joindre un fichier")
                    }
                    if (vm.sending) {
                        Box(Modifier.padding(end = 16.dp)) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = { vm.send(onSent) }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer")
                        }
                    }
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
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            if (vm.needsRecipient) {
                RecipientField(
                    label = "À",
                    field = "to",
                    value = vm.to,
                    chips = vm.toChips,
                    vm = vm,
                )
                RecipientField(
                    label = "Cc",
                    field = "cc",
                    value = vm.cc,
                    chips = vm.ccChips,
                    vm = vm,
                )
                if (vm.suggestions.isNotEmpty()) {
                    SuggestionList(vm.suggestions) { vm.pickSuggestion(it) }
                }
            } else {
                // Recipients are the server's job on a reply; say so instead of
                // showing an empty field that looks like nothing will be sent.
                Text(
                    text = "Destinataires repris du message d'origine.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            if (vm.isNew) {
                Field(vm.subject, { vm.subject = it }, "Objet", "")
            }
            if (vm.attachments.isNotEmpty() || vm.uploading > 0) {
                AttachmentChips(
                    attachments = vm.attachments,
                    uploading = vm.uploading,
                    onRemove = vm::removeAttachment,
                )
            }
            HorizontalDivider()
            // The body stays a plain text field so selection, dictation,
            // autocorrect and paste behave normally; the toolbar just inserts
            // markers. Formatting is converted to HTML at send time.
            var bodyField by remember {
                mutableStateOf(TextFieldValue(vm.body))
            }
            FormatBar { marker, isLinePrefix ->
                val (updated, caret) = if (isLinePrefix) {
                    RichText.applyLinePrefix(bodyField.text, bodyField.selection.start, marker)
                } else {
                    RichText.applyMarker(
                        bodyField.text, bodyField.selection.start,
                        bodyField.selection.end, marker,
                    )
                }
                bodyField = TextFieldValue(updated, TextRange(caret))
                vm.body = updated
            }
            OutlinedTextField(
                value = bodyField,
                onValueChange = { bodyField = it; vm.body = it.text },
                placeholder = { Text("Votre message…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                minLines = 8,
            )
            if (!vm.isNew) {
                Text(
                    text = "Le message d'origine et votre signature sont ajoutés à l'envoi.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * A recipient field: confirmed addresses as chips, plus free text.
 *
 * Free text stays available on purpose — completion accelerates the common
 * case but must never be the only way in, or writing to someone who isn't in
 * Contacts yet would be impossible.
 */
/** Bold / italic / bullet. Three, because business mail uses three. */
@Composable
private fun FormatBar(onApply: (marker: String, isLinePrefix: Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onApply("**", false) }) {
            Text("G", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { onApply("*", false) }) {
            Text("I", fontStyle = FontStyle.Italic)
        }
        TextButton(onClick = { onApply("- ", true) }) { Text("• Liste", fontSize = 13.sp) }
    }
}

@Composable
private fun RecipientField(
    label: String,
    field: String,
    value: String,
    chips: List<String>,
    vm: MailComposeViewModel,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (chips.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chips.forEach { address ->
                    AssistChip(
                        onClick = { vm.removeChip(field, address) },
                        label = { Text(address, fontSize = 12.sp) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, "Retirer", Modifier.size(14.dp))
                        },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { vm.onRecipientInput(field, it) },
            label = { Text(label) },
            placeholder = { Text(if (field == "to") "nom ou adresse…" else "facultatif") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SuggestionList(
    contacts: List<com.bluefoxconsultant.sms.data.MailContact>,
    onPick: (com.bluefoxconsultant.sms.data.MailContact) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        contacts.forEach { contact ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(contact) }
                    .padding(vertical = 8.dp),
            ) {
                Text(contact.name, fontSize = 14.sp, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                Text(
                    contact.subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun AttachmentChips(
    attachments: List<com.bluefoxconsultant.sms.data.StagedUpload>,
    uploading: Int,
    onRemove: (com.bluefoxconsultant.sms.data.StagedUpload) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { staged ->
            AssistChip(
                onClick = { onRemove(staged) },
                label = { Text("${staged.name} · ${humanSize(staged.size)}", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.AttachFile, null, Modifier.size(16.dp))
                },
                trailingIcon = {
                    Icon(Icons.Filled.Close, "Retirer", Modifier.size(16.dp))
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        repeat(uploading) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("Téléversement…", fontSize = 12.sp) },
                leadingIcon = {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f Mo".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%d ko".format(bytes / 1024)
    else -> "$bytes o"
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
