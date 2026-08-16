@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.threads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.SwipeAction
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

@Composable
fun ThreadsScreen(
    onOpenThread: (Int) -> Unit,
    onCompose: () -> Unit,
    onArchived: () -> Unit,
    onSettings: () -> Unit,
    vm: ThreadsViewModel = viewModel(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val swipe by Graph.uiPrefs.configFlow.collectAsState()

    LaunchedEffect(vm.undoable) {
        val undo = vm.undoable ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "Archivé",
            actionLabel = "Annuler",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) undo() else vm.clearUndo()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (vm.searchActive) {
                SearchTopBar(
                    term = vm.searchTerm,
                    onChange = vm::onSearchChange,
                    onClose = vm::closeSearch,
                )
            } else {
                TopAppBar(
                    title = { Text("Messages", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BrandAccent,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                    actions = {
                        IconButton(onClick = { vm.openSearch() }) {
                            Icon(Icons.Filled.Search, contentDescription = "Rechercher")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Archivées") },
                                onClick = { menuOpen = false; onArchived() },
                            )
                            DropdownMenuItem(
                                text = { Text("Paramètres") },
                                onClick = { menuOpen = false; onSettings() },
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCompose,
                containerColor = BrandAccent,
                contentColor = Color.White,
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "Nouveau message")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (vm.lines.size > 1) {
                LineFilterRow(
                    selectedLineId = vm.selectedLineId,
                    lines = vm.lines,
                    onSelect = vm::selectLine,
                )
            }
            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (vm.threads.isEmpty()) {
                    EmptyOrError(vm.error, vm.firstLoadDone)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(vm.threads, key = { it.id }) { thread ->
                            SwipeToArchive(startAction = swipe.smsStart, endAction = swipe.smsEnd, onArchive = { vm.archive(thread.id) }) {
                                ThreadRow(
                                    thread = thread,
                                    showLineLabel = vm.lines.size > 1,
                                    onClick = { onOpenThread(thread.id) },
                                    menuActions = listOf(
                                        ThreadAction(
                                            if (thread.isPinned) "Désépingler" else "Épingler",
                                        ) { vm.togglePin(thread.id) },
                                        ThreadAction("Archiver") { vm.archive(thread.id) },
                                    ),
                                )
                            }
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineFilterRow(
    selectedLineId: Int?,
    lines: List<com.bluefoxconsultant.sms.data.Line>,
    onSelect: (Int?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedLineId == null,
                onClick = { onSelect(null) },
                label = { Text("Toutes les lignes") },
            )
        }
        items(lines) { line ->
            FilterChip(
                selected = selectedLineId == line.id,
                onClick = { onSelect(line.id) },
                label = { Text(line.label) },
            )
        }
    }
}

@Composable
private fun SwipeToArchive(
    startAction: SwipeAction,
    endAction: SwipeAction,
    onArchive: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> startAction
                SwipeToDismissBoxValue.EndToStart -> endAction
                else -> SwipeAction.NONE
            }
            if (action == SwipeAction.NONE) {
                false
            } else {
                onArchive()
                true
            }
        },
        // A deliberate drag, not a flick — see the mail list for the reasoning.
        positionalThreshold = { distance -> distance * 0.55f },
    )
    val dismissing = state.dismissDirection != SwipeToDismissBoxValue.Settled

    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            if (dismissing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BrandAccent)
                        .padding(horizontal = 24.dp),
                    contentAlignment =
                    if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    },
                ) {
                    Icon(
                        Icons.Filled.Archive,
                        contentDescription = "Archiver",
                        tint = Color.White,
                    )
                }
            }
        },
        content = {
            Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
        },
    )
}

@Composable
private fun SearchTopBar(
    term: String,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = BrandAccent,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
        ),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer")
            }
        },
        title = {
            val focus = remember { FocusRequester() }
            TextField(
                value = term,
                onValueChange = onChange,
                placeholder = { Text("Rechercher", color = Color.White.copy(alpha = 0.7f)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
    )
}

@Composable
private fun EmptyOrError(error: String?, firstLoadDone: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val message = when {
            error != null -> error
            firstLoadDone -> "Aucun message."
            else -> ""
        }
        if (message.isNotBlank()) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
