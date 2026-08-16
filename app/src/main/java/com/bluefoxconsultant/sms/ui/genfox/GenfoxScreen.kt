package com.bluefoxconsultant.sms.ui.genfox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.bluefoxconsultant.sms.data.GenfoxMessage
import com.bluefoxconsultant.sms.ui.speech.DictateButton
import com.bluefoxconsultant.sms.ui.speech.appendSpoken
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

/**
 * Ask GenFox from the phone.
 *
 * The answer is not awaited on an open connection: the question is recorded,
 * the screen polls while it is open, and a push arrives if the phone went back
 * in a pocket. Conversations here are read-only on the server side — the
 * assistant can look things up, not change them — which the empty state says
 * out loud rather than leaving to be discovered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenfoxScreen(vm: GenfoxViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var historyOpen by remember { mutableStateOf(false) }

    LaunchedEffect(vm.error) {
        vm.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GenFox", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (vm.sessionName.isNotBlank()) {
                            Text(
                                vm.sessionName,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reset() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Nouvelle conversation")
                    }
                    IconButton(onClick = { vm.refreshSessions(); historyOpen = true }) {
                        Icon(Icons.Filled.History, contentDescription = "Conversations")
                    }
                },
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
                .padding(padding)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f)) {
                when {
                    vm.loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                    vm.messages.isEmpty() -> EmptyState(Modifier.align(Alignment.Center))
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(vm.messages, key = { it.hashCode() }) { Bubble(it) }
                    }
                }
            }
            Asker(asking = vm.asking, snackbar = snackbar, onAsk = vm::ask)
        }
    }

    if (historyOpen) {
        ModalBottomSheet(onDismissRequest = { historyOpen = false }) {
            Text(
                "Conversations",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            vm.sessions.forEach { session ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = session.id == vm.sessionId,
                            onClick = { vm.open(session.id); historyOpen = false },
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text(
                        session.name.ifBlank { "Sans titre" },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    IconButton(onClick = { vm.delete(session.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Demandez quelque chose",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Depuis le téléphone, GenFox consulte — il ne modifie rien. " +
                "La réponse peut prendre une minute ; vous serez prévenu.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bubble(message: GenfoxMessage) {
    val mine = message.isUser
    val background = when {
        mine -> BrandAccent
        message.isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        mine -> Color.White
        message.isError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    background,
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (mine) 16.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (message.isPending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("GenFox réfléchit…", fontSize = 14.sp, color = foreground)
                }
            } else {
                SelectionContainer {
                    Text(message.content, color = foreground, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun Asker(
    asking: Boolean,
    snackbar: SnackbarHostState,
    onAsk: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Votre question") },
                maxLines = 5,
                modifier = Modifier.weight(1f),
                // Asking out loud is the point of having dictation at all.
                trailingIcon = {
                    DictateButton(snackbar = snackbar) { spoken ->
                        text = appendSpoken(text, spoken)
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            val enabled = text.isNotBlank() && !asking
            IconButton(
                onClick = {
                    if (enabled) {
                        onAsk(text)
                        text = ""
                    }
                },
                enabled = enabled,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .size(48.dp)
                    .background(
                        color = if (enabled) BrandAccent
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ),
            ) {
                if (asking) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Envoyer",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
