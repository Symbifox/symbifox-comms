package com.bluefoxconsultant.sms.ui.phone

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import com.bluefoxconsultant.sms.data.OdooLinks
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import android.provider.ContactsContract
import android.content.Intent
import com.bluefoxconsultant.sms.sip.SipStatus
import com.bluefoxconsultant.sms.sip.SipEngine
import com.bluefoxconsultant.sms.sip.CallLeg
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.data.CallLogEntry
import com.bluefoxconsultant.sms.data.PhoneContact
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

/**
 * The keypad, in its own section.
 *
 * A phone is a place, not a button hidden in a conversation: dial a number that
 * is in no thread, pick a contact by name, or call back something from the log.
 * The call itself still goes through the PBX — this handset never carries the
 * audio — so the confirmation is the same one the in-conversation button shows.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhoneScreen(vm: PhoneViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var calling by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<CallLogEntry?>(null) }
    val sip by SipEngine.state.collectAsStateWithLifecycle()
    // Le micro n'est demandé qu'ici, au moment où le clavier s'ouvre : un poste
    // qui réclame le micro au lancement de l'app inquiète pour rien.
    val micro = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* refusé : l'appel partira muet, et le dira au premier essai */ }
    LaunchedEffect(sip.status) {
        if (sip.status != SipStatus.UNAVAILABLE) micro.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(sip.error) {
        sip.error?.let {
            snackbar.showSnackbar(it)
            SipEngine.clearError()
        }
    }

    LaunchedEffect(Unit) { vm.refresh() }
    // Le guet s'arrête avec l'écran : rien ne tourne en arrière-plan.
    DisposableEffect(Unit) {
        vm.watch()
        onDispose { vm.stopWatching() }
    }
    LaunchedEffect(vm.error) {
        vm.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Téléphone", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandAccent,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Un appel en cours prend le haut de l'écran : c'est la seule
            // commande dont on a besoin tant qu'il dure, et le combiné ne peut
            // pas la deviner — c'est le PBX qui porte l'appel.
            // Deux barres possibles, jamais les deux : soit l'appel est porté
            // par cet appareil, soit il est porté par le PBX pour un autre.
            sip.call?.let { leg ->
                SipCallBar(
                    leg = leg,
                    muted = sip.muted,
                    speaker = sip.speaker,
                    onMute = { SipEngine.setMuted(!sip.muted) },
                    onSpeaker = { SipEngine.setSpeaker(!sip.speaker) },
                    onAnswer = { SipEngine.answer() },
                    onHangup = { SipEngine.hangup() },
                )
            } ?: vm.active.firstOrNull()?.let { call ->
                InCallBar(
                    call = call,
                    hangingUp = vm.hangingUp,
                    dtmfSent = vm.dtmfSent,
                    onHangup = vm::hangup,
                )
            }
            Box(Modifier.weight(1f)) {
                when {
                    // What you are dialling decides what is useful underneath:
                    // matching contacts while typing, the log when idle.
                    vm.dialled.isNotEmpty() && vm.matches.isNotEmpty() ->
                        ContactList(vm.matches) { vm.set(it.number) }
                    vm.dialled.isEmpty() -> CallLog(
                        calls = vm.calls,
                        refreshing = vm.refreshingCalls,
                        onRefresh = vm::refresh,
                        onPick = { vm.set(it) },
                        onLongPress = { menuFor = it },
                    )
                    else -> Box(Modifier.fillMaxSize())
                }
            }

            Text(
                text = vm.dialled.ifEmpty { "" },
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
            )
            HorizontalDivider()
            // ⚠️ Pendant un appel, une touche répond au menu d'en face ; elle
            // n'écrit pas un numéro. Même clavier, deux sens selon l'état.
            val surCetAppareil = sip.call?.established == true
            val enCommunication = surCetAppareil || vm.active.any { it.isUp }
            Keypad(
                onDigit = { key ->
                    when {
                        // Sur un appel porté ici, les touches partent par la
                        // session SIP : passer par le PBX les jouerait dans une
                        // jambe qui n'existe pas.
                        surCetAppareil -> SipEngine.dtmf(key.toString())
                        enCommunication -> vm.sendDtmf(key)
                        else -> vm.press(key)
                    }
                },
                onBackspace = vm::backspace,
                onClear = { if (enCommunication) vm.clearDtmf() else vm.clear() },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(
                    onClick = { if (vm.callable) calling = true },
                    enabled = vm.callable && !vm.placing && !enCommunication,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            if (vm.callable) CALL_GREEN
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = "Appeler",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }

    menuFor?.let { entree ->
        CallLogSheet(
            entry = entree,
            onDismiss = { menuFor = null },
            onDial = { menuFor = null; vm.set(entree.number) },
            onCall = { menuFor = null; vm.set(entree.number); calling = true },
        )
    }

    if (calling) {
        CallDialog(
            number = vm.dialled,
            display = vm.matchedName.ifBlank { vm.dialled },
            snackbar = snackbar,
            onDismiss = { calling = false; vm.refresh() },
            onPlacing = { vm.placing = it },
        )
    }
}

private val CALL_GREEN = Color(0xFF2E9E5B)

/**
 * L'appel que porte CET appareil.
 *
 * Distincte de [InCallBar], qui montre ce que le PBX porte ailleurs : ici il y
 * a un micro à couper et un haut-parleur à basculer, et un appel entrant se
 * décroche. Rien de tout ça n'a de sens quand l'audio est sur un autre appareil.
 */
@Composable
private fun SipCallBar(
    leg: CallLeg,
    muted: Boolean,
    speaker: Boolean,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CALL_GREEN.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = leg.peer.ifBlank { "Appel" },
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = when {
                leg.established -> "En communication sur cet appareil"
                leg.incoming -> "Appel entrant"
                else -> "Appel en cours…"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leg.established) {
                TextButton(onClick = onMute) {
                    Icon(
                        if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (muted) "Réactiver" else "Muet")
                }
                TextButton(onClick = onSpeaker) {
                    Icon(
                        if (speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (speaker) "Haut-parleur" else "Écouteur")
                }
            } else if (leg.incoming) {
                TextButton(onClick = onAnswer) {
                    Icon(Icons.Filled.Call, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Répondre")
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onHangup) {
                Icon(
                    Icons.Filled.CallEnd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("Raccrocher", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun InCallBar(
    call: com.bluefoxconsultant.sms.data.ActiveCall,
    hangingUp: Boolean,
    dtmfSent: String,
    onHangup: () -> Unit,
) {
    Surface(color = CALL_GREEN) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (call.isUp) "En communication" else "Sonnerie…",
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Text(
                    listOfNotNull(
                        call.number.ifBlank { null },
                        call.clock.takeIf { call.isUp },
                    ).joinToString(" · "),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (dtmfSent.isNotBlank()) Text(
                    "Touches envoyées : $dtmfSent",
                    color = Color.White.copy(alpha = .85f),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onHangup, enabled = !hangingUp) {
                Icon(
                    Icons.Filled.CallEnd,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (hangingUp) "…" else "Raccrocher",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { key ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .clickable { onDigit(key) },
                    ) {
                        Text(
                            key.toString(),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            // Backspace on tap, whole number on a long press — the standard
            // gesture, so nobody has to hold it down twelve times.
            IconButton(
                onClick = onBackspace,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Backspace, contentDescription = "Effacer un chiffre")
            }
            IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
                Text("C", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ContactList(contacts: List<PhoneContact>, onPick: (PhoneContact) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(contacts, key = { it.id }) { contact ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(contact) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(contact.name, maxLines = 1)
                    Text(
                        contact.number,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CallLog(
    calls: List<CallLogEntry>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onLongPress: (CallLogEntry) -> Unit,
) {
    if (calls.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Aucun appel récent",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        return
    }
    // Tirer pour rafraîchir : le journal est écrit par le PBX, pas par
    // l'appareil, donc rien ne le pousse — il faut pouvoir le redemander.
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(calls, key = { it.id }) { call ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // Fills the field rather than dialling: one tap should never
                    // place a call by itself.
                    .combinedClickable(
                        onClick = { onPick(call.number) },
                        onLongClick = { onLongPress(call) },
                    )
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            ) {
                Icon(
                    when {
                        call.isMissed -> Icons.AutoMirrored.Filled.CallMissed
                        call.isOutgoing -> Icons.AutoMirrored.Filled.CallMade
                        else -> Icons.AutoMirrored.Filled.CallReceived
                    },
                    contentDescription = null,
                    tint = if (call.isMissed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(call.name.ifBlank { call.number }, maxLines = 1)
                    Text(
                        listOfNotNull(
                            call.number.takeIf { call.name.isNotBlank() },
                            call.date.take(16).takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (call.duration > 0) {
                    Text(
                        "${call.duration / 60}:${(call.duration % 60).toString().padStart(2, '0')}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(1.dp))
        }
    }
    }
}

/**
 * Menu contextuel d'une ligne du journal.
 *
 * Un appui long sur un appel doit proposer ce qu'on peut en faire, et
 * « ajouter aux contacts » est la première chose qu'on cherche devant un
 * numéro inconnu. La fiche Odoo n'est offerte que quand elle existe : un
 * bouton qui mène à une page vide est pire que pas de bouton.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallLogSheet(
    entry: CallLogEntry,
    onDismiss: () -> Unit,
    onDial: () -> Unit,
    onCall: () -> Unit,
) {
    val context = LocalContext.current
    val presse = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                entry.name.ifBlank { entry.number },
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (entry.name.isNotBlank()) {
                Text(
                    entry.number,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            SheetAction(Icons.Filled.Call, "Appeler") { onCall() }
            SheetAction(Icons.Filled.Dialpad, "Mettre au clavier") { onDial() }
            SheetAction(Icons.Filled.ContentCopy, "Copier le numéro") {
                presse.setText(AnnotatedString(entry.number))
                onDismiss()
            }
            if (entry.partnerId > 0) {
                SheetAction(Icons.Filled.Person, "Ouvrir la fiche") {
                    OdooLinks.openRecord(context, "res.partner", entry.partnerId)
                    onDismiss()
                }
            } else {
                SheetAction(Icons.Filled.PersonAdd, "Ajouter aux contacts") {
                    // Contacts DU TÉLÉPHONE : c'est le geste attendu d'un
                    // clavier. La fiche Odoo, elle, se crée depuis Odoo.
                    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.PHONE, entry.number)
                        if (entry.name.isNotBlank()) {
                            putExtra(ContactsContract.Intents.Insert.NAME, entry.name)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 13.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
             tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Text(label)
    }
}
