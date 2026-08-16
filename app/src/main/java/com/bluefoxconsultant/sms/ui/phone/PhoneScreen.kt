package com.bluefoxconsultant.sms.ui.phone

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneScreen(vm: PhoneViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var calling by remember { mutableStateOf(false) }

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
            vm.active.firstOrNull()?.let { call ->
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
                    vm.dialled.isEmpty() -> CallLog(vm.calls) { vm.set(it) }
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
            val enCommunication = vm.active.any { it.isUp }
            Keypad(
                onDigit = { key -> if (enCommunication) vm.sendDtmf(key) else vm.press(key) },
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

@Composable
private fun CallLog(calls: List<CallLogEntry>, onPick: (String) -> Unit) {
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
                    .clickable { onPick(call.number) }
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
