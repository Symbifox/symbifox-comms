package com.bluefoxconsultant.sms.ui.conversation

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bluefoxconsultant.sms.data.Message
import com.bluefoxconsultant.sms.ui.clockTime
import com.bluefoxconsultant.sms.ui.lines.LinePickerSheet
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    threadId: Int,
    onBack: () -> Unit,
) {
    val vm: ConversationViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ConversationViewModel(threadId) }
        },
    )

    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var linePickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }
    LaunchedEffect(vm.error) {
        vm.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    // Auto-scroll to newest when a message is appended (last id changes), not when prepending.
    LaunchedEffect(vm.messages.lastOrNull()?.id, vm.loading) {
        if (!vm.loading && vm.messages.isNotEmpty()) {
            listState.scrollToItem(vm.messages.lastIndex)
        }
    }

    // Paginate older messages when the user scrolls to the top.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index == 0 && vm.hasMore && !vm.loadingMore && vm.messages.isNotEmpty()) {
                    vm.loadOlder()
                }
            }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = vm.title.ifBlank { "Conversation" },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        // Which number this conversation goes out from. Worth a
                        // permanent line rather than a hidden menu: on a shared
                        // handset, sending from the wrong number is the mistake.
                        if (vm.currentLineLabel.isNotBlank()) {
                            Text(
                                text = vm.currentLineLabel,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (vm.lines.size > 1) {
                        IconButton(onClick = { linePickerOpen = true }) {
                            Icon(
                                Icons.Filled.SwapHoriz,
                                contentDescription = "Changer le numéro d'envoi",
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (vm.loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(
                            items = vm.messages,
                            key = { msg -> if (msg.id != 0) msg.id else msg.hashCode() },
                        ) { message ->
                            MessageBubble(message)
                        }
                    }
                }
                if (vm.loadingMore) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(24.dp),
                    )
                }
            }
            Composer(sending = vm.sending, onSend = { vm.send(it) })
        }
    }

    if (linePickerOpen) {
        LinePickerSheet(
            lines = vm.lines,
            selectedLineId = vm.selectedLineId,
            title = "Envoyer depuis",
            subtitle = "S'applique aux prochains messages de cette conversation.",
            disabledReason = vm::disabledReason,
            onDismiss = { linePickerOpen = false },
            onPick = { line ->
                vm.selectLine(line)
                linePickerOpen = false
            },
        )
    }
}

/**
 * One message bubble, with selectable text.
 *
 * Selection rather than a custom long-press "Copy" action: the two compete for
 * the same gesture, and [SelectionContainer] already gives the platform
 * toolbar — drag handles to grab an address or a confirmation number, "Tout
 * sélectionner" to take the whole message. A second handler would only make
 * one of them unreliable.
 */
@Composable
private fun MessageBubble(message: Message) {
    val outgoing = message.isOutgoing
    val bubbleColor = if (outgoing) BrandAccent else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (outgoing) 16.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 16.dp,
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(bubbleColor, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            SelectionContainer {
                Text(
                    text = message.body.ifBlank { if (message.isMms) "[Pièce jointe]" else "" },
                    color = textColor,
                    fontSize = 15.sp,
                )
            }
        }
        val time = clockTime(message.dateMs)
        if (time.isNotBlank()) {
            Text(
                text = time,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Composer(sending: Boolean, onSend: (String) -> Unit) {
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
                placeholder = { Text("Message texto") },
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val enabled = text.isNotBlank() && !sending
            IconButton(
                onClick = {
                    if (enabled) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = enabled,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .size(48.dp)
                    .background(
                        color = if (enabled) BrandAccent else MaterialTheme.colorScheme.surfaceVariant,
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
            ) {
                if (sending) {
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
