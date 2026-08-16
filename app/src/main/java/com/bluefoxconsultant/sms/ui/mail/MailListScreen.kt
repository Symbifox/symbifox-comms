@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailFilter
import com.bluefoxconsultant.sms.data.SwipeAction
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

@Composable
fun MailListScreen(
    onOpenThread: (String) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    vm: MailListViewModel = viewModel(),
) {
    val snackbar = remember { SnackbarHostState() }
    var sheetFor by remember { mutableStateOf<MailMessage?>(null) }
    var routeFor by remember { mutableStateOf<MailMessage?>(null) }
    val listState = rememberLazyListState()
    val swipe by Graph.uiPrefs.configFlow.collectAsState()
    val threadView by Graph.uiPrefs.threadViewFlow.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    // Infinite scroll: ask for the next page a few rows before the end so the
    // spinner rarely shows.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, vm.threads.size) {
        if (nearEnd) vm.loadMore()
    }

    // Opening a thread marks it read on the server, so coming back to a list
    // rendered before that leaves a row still looking unread and a stale badge.
    // Refresh on resume — skipped on first composition, where init() just ran.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && vm.firstLoadDone) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vm.notice, vm.error) {
        val message = vm.error ?: vm.notice
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.dismissNotice()
        }
    }

    // Undo window. Short is ~4 s of visible snackbar; the action is reversed
    // rather than deferred, so leaving the screen never loses it.
    LaunchedEffect(vm.undoable) {
        val undo = vm.undoable ?: return@LaunchedEffect
        val label = vm.undoLabel.orEmpty()
        val result = snackbar.showSnackbar(
            message = label,
            actionLabel = "Annuler",
            withDismissAction = false,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) undo() else vm.clearUndo()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (vm.searchActive) {
                MailSearchBar(
                    term = vm.searchTerm,
                    onChange = vm::onSearchChange,
                    onClose = vm::closeSearch,
                )
            } else {
                TopAppBar(
                    title = { Text("Courriel", fontWeight = FontWeight.SemiBold) },
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
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (threadView) "Vue par conversation"
                                        else "Vue par message",
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (threadView) Icons.Filled.CheckBox
                                        else Icons.Filled.CheckBoxOutlineBlank,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    Graph.uiPrefs.setThreadView(!threadView)
                                    // The grouping key changes, so the page is
                                    // a different shape — refetch rather than
                                    // regroup what is already on screen.
                                    vm.refresh()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Réglages") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                },
                                onClick = { menuOpen = false; onSettings() },
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose, containerColor = BrandAccent) {
                Icon(Icons.Filled.Edit, contentDescription = "Nouveau courriel", tint = Color.White)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.offline || vm.queued > 0) {
                OfflineBanner(offline = vm.offline, queued = vm.queued)
            }
            FilterRow(
                selected = vm.filter,
                counts = vm.counts,
                onSelect = vm::selectFilter,
            )
            HorizontalDivider()
            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    !vm.firstLoadDone -> CenteredSpinner()
                    vm.threads.isEmpty() -> EmptyState(vm.filter, vm.searchTerm)
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(vm.threads, key = { it.threadKey }) { thread ->
                            SwipeRow(
                                // Already handled: swiping should put it back,
                                // not archive something that already is.
                                restore = thread.isHandled,
                                startAction = swipe.mailStart,
                                endAction = swipe.mailEnd,
                                onAction = { action ->
                                    when {
                                        thread.isHandled -> vm.restore(thread)
                                        action == SwipeAction.ARCHIVE -> vm.archive(thread)
                                        action == SwipeAction.MARK_READ -> vm.markRead(thread)
                                        action == SwipeAction.SNOOZE ->
                                            vm.config.snoozePresets
                                                .firstOrNull { it.key == "tomorrow" }
                                                ?.let { vm.snooze(thread, it.untilMs) }
                                        else -> Unit
                                    }
                                },
                            ) {
                                MailRow(
                                    message = thread,
                                    onClick = { onOpenThread(thread.threadKey) },
                                    onLongClick = { sheetFor = thread },
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                        if (vm.loadingMore) {
                            item { CenteredSpinner(compact = true) }
                        }
                    }
                }
            }
        }
    }

    sheetFor?.let { target ->
        MailActionsSheet(
            message = target,
            config = vm.config,
            onDismiss = { sheetFor = null },
            onArchive = { vm.archive(target); sheetFor = null },
            onRestore = { vm.restore(target); sheetFor = null },
            onMarkRead = { vm.markRead(target); sheetFor = null },
            onSnooze = { until -> vm.snooze(target, until); sheetFor = null },
            onSpawn = { kind -> vm.spawn(target, kind); sheetFor = null },
            onRoute = { routeFor = target; sheetFor = null },
        )
    }

    routeFor?.let { target ->
        RoutePickerDialog(
            config = vm.config,
            onDismiss = { routeFor = null },
            onPick = { model, recordId ->
                routeFor = null
                vm.route(target, model, recordId)
            },
        )
    }
}

/**
 * Says plainly that what is on screen is a snapshot, and how many actions are
 * still owed to the server. A mail app that shows stale content as if it were
 * current is worse than one that admits it is offline — the reader would
 * otherwise conclude nothing new has arrived.
 */
@Composable
private fun OfflineBanner(offline: Boolean, queued: Int) {
    val text = when {
        offline && queued > 0 ->
            "Hors ligne — affichage en cache · $queued action(s) en attente"
        offline -> "Hors ligne — affichage de la dernière synchronisation"
        else -> "$queued action(s) en attente d'envoi"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (offline) Icons.Filled.CloudOff else Icons.Filled.Schedule,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/**
 * Swipe row with a configurable action per direction.
 *
 * A direction set to [SwipeAction.NONE] refuses the gesture outright rather
 * than swallowing it: a row that slides away and then springs back with
 * nothing having happened reads as a bug.
 */
@Composable
private fun SwipeRow(
    restore: Boolean,
    startAction: SwipeAction,
    endAction: SwipeAction,
    onAction: (SwipeAction) -> Unit,
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
                onAction(action)
                true
            }
        },
        // Default threshold fires on a flick. Archiving moves the message on
        // the real mail server, so it should take a deliberate drag past the
        // halfway mark, not a brush of the thumb.
        positionalThreshold = { distance -> distance * 0.55f },
    )
    // ⚠️ `progress` is 1.0 when SETTLED — it measures the distance between the
    // current anchor and the target, which are the same at rest. Reading it
    // without checking the direction painted the "armed" background behind
    // every row permanently, which is what put a blue wash and a stray archive
    // icon under the list.
    val dismissing = state.dismissDirection != SwipeToDismissBoxValue.Settled
    val armed = dismissing && state.progress.coerceIn(0f, 1f) > 0.5f
    val tint by animateColorAsState(
        if (armed) BrandAccent else MaterialTheme.colorScheme.surfaceVariant,
        label = "swipe-bg",
    )
    val iconScale by animateFloatAsState(if (armed) 1.15f else 0.85f, label = "swipe-icon")

    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            // Drawn only during an actual gesture. At rest there is nothing
            // behind the row at all.
            if (dismissing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tint)
                        .padding(horizontal = 24.dp),
                    contentAlignment =
                    if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    },
                ) {
                    Icon(
                        if (restore) Icons.Filled.Inbox else Icons.Filled.Archive,
                        contentDescription = if (restore) "Remettre" else "Archiver",
                        tint = if (armed) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.scale(iconScale),
                    )
                }
            }
        },
        // Opaque, so nothing behind the row can ever show through it.
        content = {
            Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
        },
    )
}

@Composable
private fun FilterRow(
    selected: MailFilter,
    counts: com.bluefoxconsultant.sms.data.MailCounts,
    onSelect: (MailFilter) -> Unit,
) {
    fun badge(filter: MailFilter): Int? = when (filter) {
        MailFilter.INBOX -> counts.inbox
        MailFilter.UNREAD -> counts.unread
        MailFilter.SNOOZED -> counts.snoozed
        MailFilter.UNROUTED -> counts.unrouted
        else -> null
    }?.takeIf { it > 0 }

    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        items(MailFilter.entries.toList()) { filter ->
            val count = badge(filter)
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = {
                    Text(if (count != null) "${filter.label} · $count" else filter.label)
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun MailSearchBar(term: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = term,
                onValueChange = onChange,
                placeholder = { Text("Rechercher…", color = Color.White.copy(alpha = 0.7f)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color.White,
                ),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = Color.White)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandAccent),
    )
}

@Composable
private fun CenteredSpinner(compact: Boolean = false) {
    Box(
        modifier = if (compact) Modifier.fillMaxWidth().padding(16.dp) else Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = BrandAccent)
    }
}

@Composable
private fun EmptyState(filter: MailFilter, search: String) {
    val message = when {
        search.isNotBlank() -> "Aucun résultat pour « $search »."
        filter == MailFilter.INBOX -> "Boîte de réception vide."
        else -> "Rien dans « ${filter.label} »."
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row {
            Spacer(Modifier.width(24.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(24.dp))
        }
    }
}
