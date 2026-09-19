@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.mail

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.data.Adresses
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailContact
import com.bluefoxconsultant.sms.data.MailIdentity
import com.bluefoxconsultant.sms.data.RecordRef
import com.bluefoxconsultant.sms.data.SharedContent
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.agenda.DialogueDate
import com.bluefoxconsultant.sms.ui.agenda.DialogueHeure
import com.bluefoxconsultant.sms.ui.asString
import com.bluefoxconsultant.sms.ui.resolve
import com.bluefoxconsultant.sms.ui.share.PiecesProposees
import com.bluefoxconsultant.sms.ui.speech.DictateButton
import com.bluefoxconsultant.sms.ui.theme.BrandAccent
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Suppress("UNCHECKED_CAST")
private class ComposeVmFactory(
    private val mode: String,
    private val emailId: Int,
    private val draftId: String,
    private val serverDraftId: Int,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MailComposeViewModel(mode, emailId, draftId, serverDraftId) as T
}

/**
 * [onBack] reçoit vrai quand un brouillon a été gardé au passage : c'est la
 * liste qui le dit, puisque cet écran a déjà disparu au moment de l'annoncer.
 *
 * [onSent] reçoit ce qu'il reste à dire une fois l'écran fermé (envoi
 * programmé, envoi mis en file hors ligne), ou `null`.
 */
@Composable
fun MailComposeScreen(
    mode: String,
    emailId: Int,
    draftId: String = "",
    /** Le brouillon du poste qu'on reprend, ou 0 (#25579). */
    serverDraftId: Int = 0,
    /** Ce qu'une autre app vient de partager, s'il y a lieu. */
    shared: SharedContent? = null,
    onBack: (Boolean) -> Unit,
    onSent: (UiText?) -> Unit,
) {
    val vm: MailComposeViewModel = viewModel(
        key = "$mode-$emailId-$draftId-$serverDraftId",
        factory = ComposeVmFactory(mode, emailId, draftId, serverDraftId),
    )
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val voirOriginal by Graph.uiPrefs.voirOriginalFlow.collectAsState()

    var menuOuvert by remember { mutableStateOf(false) }
    var programmer by remember { mutableStateOf(false) }
    var jeter by remember { mutableStateOf(false) }
    var classer by remember { mutableStateOf(false) }

    // Une seule fois, sur le contenu lui-même : recomposer ne doit pas
    // proposer la pièce jointe une deuxième fois. Rien n'est lu ici : les
    // fichiers attendent « Joindre » ou « Envoyer ».
    LaunchedEffect(shared) {
        shared?.let { vm.adopt(context, it) }
    }
    // OpenMultipleDocuments, not GetMultipleContents: it returns a durable,
    // readable URI for anything the system document picker can reach, which
    // GetContent does not guarantee across providers.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach { vm.attach(context, it) } }

    // Quitter, c'est mettre de côté — pas jeter. Le même chemin pour la
    // flèche et pour le geste système : n'en câbler qu'un revient à perdre le
    // texte par celui qu'on a oublié, qui est justement le plus utilisé.
    fun leave() = onBack(vm.saveDraft())

    BackHandler { leave() }

    LaunchedEffect(vm.error) {
        vm.error?.let {
            snackbar.showSnackbar(it.resolve(context))
            vm.dismissError()
        }
    }
    LaunchedEffect(vm.info) {
        vm.info?.let {
            snackbar.showSnackbar(it.resolve(context))
            vm.dismissInfo()
        }
    }
    LaunchedEffect(voirOriginal, vm.aUnOriginal) {
        if (voirOriginal && vm.aUnOriginal) vm.chargerOriginal()
    }

    // Le poste a bougé pendant qu'on écrivait. Deux issues, et pas trois :
    // reprendre ce que le poste porte, ou garder son texte et retourner au
    // composeur. Écraser l'autre bord d'un seul geste n'est pas offert — le
    // poste a l'écran qu'il faut pour trancher, le téléphone non.
    vm.conflict?.let { remote ->
        AlertDialog(
            onDismissRequest = { vm.dismissConflict() },
            title = { Text(stringResource(R.string.mail_compose_conflict_title)) },
            text = {
                Text(stringResource(R.string.mail_compose_conflict_body, remote.label.asString()))
            },
            confirmButton = {
                TextButton(onClick = { vm.adoptServerVersion() }) {
                    Text(stringResource(R.string.mail_compose_use_desktop_version))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissConflict() }) {
                    Text(stringResource(R.string.mail_compose_keep_my_text))
                }
            },
        )
    }

    if (jeter) {
        AlertDialog(
            onDismissRequest = { jeter = false },
            title = { Text(stringResource(R.string.mail_compose_discard_title)) },
            text = {
                Text(
                    stringResource(
                        if (vm.isServerDraft) R.string.mail_compose_discard_body_server
                        else R.string.mail_compose_discard_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    jeter = false
                    vm.jeter { onBack(false) }
                }) { Text(stringResource(R.string.mail_compose_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { jeter = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (programmer) {
        DialogueProgrammer(
            onChoisir = { ms ->
                programmer = false
                vm.send(context, onSent, scheduledMs = ms)
            },
            onFermer = { programmer = false },
        )
    }

    if (classer) {
        RoutePickerDialog(
            config = vm.config,
            title = stringResource(R.string.mail_compose_file_on_record_title),
            onDismiss = { classer = false },
            onPickRecord = { record ->
                classer = false
                vm.classerSur(record)
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(vm.titleRes), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.common_attach_file))
                    }
                    if (vm.sending) {
                        Box(Modifier.padding(horizontal = 12.dp)) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = { vm.send(context, onSent) }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.common_send))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOuvert = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mail_compose_more))
                        }
                        DropdownMenu(expanded = menuOuvert, onDismissRequest = { menuOuvert = false }) {
                            if (vm.complet) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.mail_compose_schedule)) },
                                    enabled = !vm.sending,
                                    onClick = { menuOuvert = false; programmer = true },
                                )
                            }
                            if (vm.aUnOriginal) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.mail_compose_show_original)) },
                                    trailingIcon = {
                                        if (voirOriginal) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOuvert = false
                                        Graph.uiPrefs.setVoirOriginal(!voirOriginal)
                                    },
                                )
                            }
                            if (vm.complet && vm.isNew && vm.config.routableModels.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.mail_compose_file_on_record)) },
                                    onClick = { menuOuvert = false; classer = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mail_compose_discard)) },
                                onClick = { menuOuvert = false; jeter = true },
                            )
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
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (vm.identites.size >= 2) {
                LigneDe(
                    identites = vm.identites,
                    choisie = vm.identite,
                    onChoisir = vm::choisirIdentite,
                )
            }

            if (vm.champsDestinataires) {
                RecipientField(
                    label = stringResource(R.string.mail_compose_to),
                    field = "to",
                    value = vm.to,
                    chips = vm.toChips,
                    vm = vm,
                    trailing = if (!vm.copiesVisibles && !vm.isServerDraft) {
                        {
                            TextButton(onClick = vm::montrerCopies) {
                                Text(
                                    stringResource(
                                        if (vm.complet) R.string.mail_compose_show_copies
                                        else R.string.mail_compose_cc,
                                    ),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    } else null,
                )
                if (vm.suggestions.isNotEmpty() && vm.suggestingFor == "to") {
                    SuggestionList(vm.suggestions) { vm.pickSuggestion(it) }
                }
                if (vm.copiesVisibles) {
                    RecipientField(
                        label = stringResource(R.string.mail_compose_cc),
                        field = "cc",
                        value = vm.cc,
                        chips = vm.ccChips,
                        vm = vm,
                    )
                    if (vm.suggestions.isNotEmpty() && vm.suggestingFor == "cc") {
                        SuggestionList(vm.suggestions) { vm.pickSuggestion(it) }
                    }
                    if (vm.complet) {
                        RecipientField(
                            label = stringResource(R.string.mail_compose_bcc),
                            field = "bcc",
                            value = vm.bcc,
                            chips = vm.bccChips,
                            vm = vm,
                        )
                        if (vm.suggestions.isNotEmpty() && vm.suggestingFor == "bcc") {
                            SuggestionList(vm.suggestions) { vm.pickSuggestion(it) }
                        }
                    }
                }
            } else {
                // Recipients are the server's job on a reply that could not be
                // prepared; say so instead of showing an empty field that looks
                // like nothing will be sent.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    if (vm.preparation) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = stringResource(
                            when {
                                vm.preparation -> R.string.mail_compose_preparing_recipients
                                vm.preparationEchouee -> R.string.mail_compose_recipients_unavailable
                                else -> R.string.mail_compose_recipients_from_original
                            },
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.preparationEchouee) {
                        TextButton(onClick = vm::preparer) {
                            Text(stringResource(R.string.common_retry), fontSize = 13.sp)
                        }
                    }
                }
            }

            if (vm.isNew || vm.complet) {
                Field(vm.subject, { vm.subject = it }, stringResource(R.string.mail_compose_subject), "")
            }
            vm.fiche?.let { fiche ->
                AssistChip(
                    onClick = { vm.classerSur(null) },
                    label = {
                        Text(
                            stringResource(R.string.mail_compose_filed_on, fiche.name),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(16.dp)) },
                    trailingIcon = {
                        Icon(Icons.Filled.Close, stringResource(R.string.common_remove), Modifier.size(16.dp))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (vm.attachments.isNotEmpty() || vm.uploading > 0) {
                AttachmentChips(
                    attachments = vm.attachments,
                    uploading = vm.uploading,
                    onRemove = vm::removeAttachment,
                )
            }
            PiecesProposees(
                proposees = vm.proposees,
                onJoindre = { vm.joindreProposee(context, it) },
                onEcarter = vm::ecarterProposee,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()
            FormatBar(
                actifs = vm.stylesActifs,
                puce = vm.puceActive,
                onStyle = vm::basculer,
                onPuce = vm::basculerPuce,
            )
            // Un champ de texte ordinaire, pour que la sélection, la dictée,
            // la correction et le collage restent ceux du clavier ; seul
            // l'AFFICHAGE est rendu (voir RichText).
            val miseEnForme = remember { MiseEnFormeVisible() }
            OutlinedTextField(
                value = vm.corps,
                onValueChange = vm::onCorps,
                visualTransformation = miseEnForme,
                placeholder = { Text(stringResource(R.string.mail_compose_body_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                minLines = 8,
                trailingIcon = {
                    DictateButton(snackbar = snackbar) { spoken -> vm.inserer(spoken) }
                },
            )

            val signature = vm.identite?.signatureText.orEmpty()
            if (vm.complet && signature.isNotBlank()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Text(
                        stringResource(R.string.mail_compose_signature),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        signature,
                        fontSize = 12.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (vm.aUnOriginal) {
                if (voirOriginal) {
                    MessageDOrigine(vm)
                } else {
                    Text(
                        text = stringResource(R.string.mail_compose_original_and_signature),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

/**
 * L'affichage du corps : les marqueurs cachés, le contenu en gras ou en
 * italique, les tirets de puce en « • ». Le texte, lui, ne change pas.
 */
private class MiseEnFormeVisible : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val analyse = RichText.analyser(text.text)
        val builder = AnnotatedString.Builder(analyse.affiche)
        for (p in analyse.portees) {
            val debut = analyse.versAffiche[p.contenuDebut]
            val fin = analyse.versAffiche[p.contenuFin]
            if (fin <= debut) continue
            builder.addStyle(
                when (p.style) {
                    RichText.Style.GRAS -> SpanStyle(fontWeight = FontWeight.Bold)
                    RichText.Style.ITALIQUE -> SpanStyle(fontStyle = FontStyle.Italic)
                },
                debut, fin,
            )
        }
        val longueurSource = text.text.length
        val longueurAffichee = analyse.affiche.length
        val correspondance = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                analyse.versAffiche[offset.coerceIn(0, longueurSource)]

            override fun transformedToOriginal(offset: Int): Int =
                analyse.versSource[offset.coerceIn(0, longueurAffichee)]
        }
        return TransformedText(builder.toAnnotatedString(), correspondance)
    }
}

/** Gras, italique, liste — avec l'état de là où se trouve le curseur. */
@Composable
private fun FormatBar(
    actifs: Set<RichText.Style>,
    puce: Boolean,
    onStyle: (RichText.Style) -> Unit,
    onPuce: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = RichText.Style.GRAS in actifs,
            onClick = { onStyle(RichText.Style.GRAS) },
            label = { Text(stringResource(R.string.mail_compose_bold), fontWeight = FontWeight.Bold) },
        )
        FilterChip(
            selected = RichText.Style.ITALIQUE in actifs,
            onClick = { onStyle(RichText.Style.ITALIQUE) },
            label = { Text(stringResource(R.string.mail_compose_italic), fontStyle = FontStyle.Italic) },
        )
        FilterChip(
            selected = puce,
            onClick = onPuce,
            label = { Text(stringResource(R.string.mail_compose_bullet_list), fontSize = 13.sp) },
        )
    }
}

/** « De » : les adresses vérifiées de la personne, comme au poste. */
@Composable
private fun LigneDe(
    identites: List<MailIdentity>,
    choisie: MailIdentity?,
    onChoisir: (MailIdentity) -> Unit,
) {
    var ouvert by remember { mutableStateOf(false) }
    val couleurs = remember {
        couleursDesComptes(Graph.mailCache.loadConfig()?.accounts.orEmpty())
            .mapNotNull { (id, hex) -> couleurHex(hex)?.let { id to it } }.toMap()
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { ouvert = true }
                .padding(vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.mail_compose_from),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp),
            )
            choisie?.accountId?.let { couleurs[it] }?.let { teinte ->
                Box(Modifier.size(10.dp).background(teinte, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                choisie?.let { libelleIdentite(it) }
                    ?: stringResource(R.string.mail_compose_from_receiving_box),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.mail_compose_from))
        }
        DropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
            identites.forEach { identite ->
                DropdownMenuItem(
                    text = { Text(libelleIdentite(identite), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = identite.accountId?.let { couleurs[it] }?.let { teinte ->
                        { Box(Modifier.size(10.dp).background(teinte, CircleShape)) }
                    },
                    trailingIcon = {
                        if (identite.id == choisie?.id) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        ouvert = false
                        onChoisir(identite)
                    },
                )
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

private fun libelleIdentite(identite: MailIdentity): String =
    if (identite.name.isBlank()) identite.email else "${identite.name} <${identite.email}>"

/**
 * A recipient field: confirmed addresses as chips, plus free text.
 *
 * Free text stays available on purpose — completion accelerates the common
 * case but must never be the only way in, or writing to someone who isn't in
 * Contacts yet would be impossible.
 */
@Composable
private fun RecipientField(
    label: String,
    field: String,
    value: String,
    chips: List<String>,
    vm: MailComposeViewModel,
    trailing: (@Composable () -> Unit)? = null,
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
                        label = { Text(Adresses.libelle(address), fontSize = 12.sp) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, stringResource(R.string.common_remove), Modifier.size(14.dp))
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
            placeholder = {
                Text(
                    if (field == "to") stringResource(R.string.mail_compose_to_placeholder)
                    else stringResource(R.string.mail_compose_optional),
                )
            },
            trailingIcon = trailing,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SuggestionList(
    contacts: List<MailContact>,
    onPick: (MailContact) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        contacts.forEach { contact ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(contact) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    contact.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (contact.isGroup) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    if (contact.isGroup) {
                        pluralStringResource(
                            R.plurals.mail_compose_group_members,
                            contact.members.size, contact.members.size,
                        )
                    } else {
                        contact.subtitle
                    },
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

/**
 * Le message auquel on répond, en lecture seule, sous le texte (#25764).
 *
 * C'est le corps que le fil affiche, images distantes bloquées de la même
 * façon : le montrer ici ne doit pas rallumer un pixel espion que la lecture
 * avait éteint.
 */
@Composable
private fun MessageDOrigine(vm: MailComposeViewModel) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.mail_compose_original_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.mail_compose_quote_note),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            val message = vm.original
            when {
                message != null -> {
                    Text(
                        message.correspondent.asString(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    message.dateMs?.let { ms ->
                        Text(
                            formaterMoment(ms),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        message.displaySubject.asString(),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(6.dp))
                    MailBodyView(html = message.bodyHtml.orEmpty(), allowRemoteContent = false)
                }
                vm.originalIndisponible -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.mail_compose_original_unavailable),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::chargerOriginal) {
                        Text(stringResource(R.string.common_retry), fontSize = 13.sp)
                    }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.mail_compose_original_loading),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formaterMoment(ms: Long): String =
    DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault())
        .format(java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * Programmer l'envoi : quelques créneaux usuels, ou une date et une heure
 * choisies. Rend l'heure en millisecondes epoch.
 */
@Composable
private fun DialogueProgrammer(
    onChoisir: (Long) -> Unit,
    onFermer: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val maintenant = remember { ZonedDateTime.now(zone) }
    val creneaux = remember { CreneauxEnvoi.proposer(maintenant) }
    var etape by remember { mutableStateOf(0) }
    var date by remember { mutableStateOf(maintenant.toLocalDate()) }
    var tropTot by remember { mutableStateOf(false) }
    val format = remember { DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault()) }

    when (etape) {
        1 -> DialogueDate(
            initiale = date,
            onChoisir = { date = it; etape = 2 },
            onFermer = { if (etape == 1) onFermer() },
        )
        2 -> DialogueHeure(
            initiale = LocalTime.of(8, 0),
            onChoisir = { heure ->
                val quand = date.atTime(heure).atZone(zone)
                if (CreneauxEnvoi.acceptable(quand, ZonedDateTime.now(zone))) {
                    onChoisir(quand.toInstant().toEpochMilli())
                } else {
                    tropTot = true
                    etape = 0
                }
            },
            onFermer = { if (etape == 2) onFermer() },
        )
        else -> AlertDialog(
            onDismissRequest = onFermer,
            title = { Text(stringResource(R.string.mail_compose_schedule_title)) },
            text = {
                Column {
                    if (tropTot) {
                        Text(
                            stringResource(R.string.mail_compose_schedule_too_soon),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    creneaux.forEach { c ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onChoisir(c.quand.toInstant().toEpochMilli()) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                stringResource(
                                    when (c.cle) {
                                        CreneauxEnvoi.Cle.PLUS_TARD -> R.string.mail_compose_schedule_later_today
                                        CreneauxEnvoi.Cle.DEMAIN_MATIN -> R.string.mail_compose_schedule_tomorrow_morning
                                        CreneauxEnvoi.Cle.DEMAIN_APRES_MIDI -> R.string.mail_compose_schedule_tomorrow_afternoon
                                        CreneauxEnvoi.Cle.LUNDI_MATIN -> R.string.mail_compose_schedule_monday_morning
                                    },
                                ),
                                fontSize = 15.sp,
                            )
                            Text(
                                format.format(c.quand),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.mail_compose_schedule_pick),
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { etape = 1 }
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onFermer) { Text(stringResource(R.string.common_cancel)) }
            },
        )
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
                    Icon(Icons.Filled.Close, stringResource(R.string.common_remove), Modifier.size(16.dp))
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        repeat(uploading) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.mail_compose_uploading), fontSize = 12.sp) },
                leadingIcon = {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

/** Les unités se traduisent aussi : « Mo » en français, « MB » en anglais. */
@Composable
private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> stringResource(R.string.size_megabytes, bytes / 1_048_576.0)
    bytes >= 1024 -> stringResource(R.string.size_kilobytes, bytes / 1024)
    else -> stringResource(R.string.size_bytes, bytes)
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
