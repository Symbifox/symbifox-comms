@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.bluefoxconsultant.sms.ui.relativeTime
import com.bluefoxconsultant.sms.data.MailDraft
import com.bluefoxconsultant.sms.data.ScheduledMail
import com.bluefoxconsultant.sms.data.ServerDraft
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.ui.SwipeActionRow
import com.bluefoxconsultant.sms.ui.theme.BrandAccent
import com.bluefoxconsultant.sms.ui.BoutonTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.asString
import com.bluefoxconsultant.sms.ui.resolve

@Composable
fun MailListScreen(
    onOpenThread: (String) -> Unit,
    onCompose: () -> Unit,
    onOpenDraft: (MailDraft) -> Unit,
    onOpenServerDraft: (ServerDraft) -> Unit,
    onSettings: () -> Unit,
    vm: MailListViewModel = viewModel(),
) {
    val snackbar = remember { SnackbarHostState() }
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    var sheetFor by remember { mutableStateOf<MailMessage?>(null) }
    var routeFor by remember { mutableStateOf<MailMessage?>(null) }
    val listState = rememberLazyListState()
    val swipe by Graph.uiPrefs.configFlow.collectAsState()
    val threadView by Graph.uiPrefs.threadViewFlow.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
            snackbar.showSnackbar(message.resolve(context))
            vm.dismissNotice()
        }
    }

    // Undo window. Short is ~4 s of visible snackbar; the action is reversed
    // rather than deferred, so leaving the screen never loses it.
    LaunchedEffect(vm.undoable) {
        val undo = vm.undoable ?: return@LaunchedEffect
        val label = vm.undoLabel?.resolve(context).orEmpty()
        val result = snackbar.showSnackbar(
            message = label,
            actionLabel = context.getString(R.string.common_undo),
            withDismissAction = false,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) undo() else vm.clearUndo()
    }

    // Le retour arrière sort de la sélection avant de quitter l'écran —
    // sinon un geste réflexe ferme l'app avec vingt courriels cochés.
    BackHandler(enabled = vm.selectionMode) { vm.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (vm.selectionMode) {
                SelectionBar(
                    count = vm.selection.size,
                    // « Traités » et « Envoyés » ne s'archivent pas : c'est
                    // déjà fait. La barre propose la remise en réception.
                    restoring = vm.filter == MailFilter.HANDLED,
                    snoozePreset = vm.config.snoozePresets.firstOrNull { it.key == "tomorrow" },
                    onClose = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                    onArchive = vm::archiveSelected,
                    onRestore = vm::restoreSelected,
                    onMarkRead = vm::markReadSelected,
                    onSnooze = { vm.snoozeSelected(it) },
                    // Une seule ligne cochée : tout le reste (créer une tâche,
                    // router, autres reports) vit déjà dans la feuille d'actions.
                    onMore = if (vm.selection.size == 1) {
                        { vm.selectedMessages.firstOrNull()?.let { sheetFor = it } }
                    } else {
                        null
                    },
                )
            } else if (vm.searchActive) {
                MailSearchBar(
                    term = vm.searchTerm,
                    onChange = vm::onSearchChange,
                    onClose = vm::closeSearch,
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.service_mail), fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BrandAccent,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                    actions = {
                        BoutonTheme()
                        IconButton(onClick = { vm.openSearch() }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.common_search))
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_options))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (threadView) stringResource(R.string.mail_list_conversation_view)
                                        else stringResource(R.string.mail_list_message_view),
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
                                text = { Text(stringResource(R.string.mail_list_settings)) },
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
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.common_new_email),
                    tint = Color.White,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.offline || vm.queued > 0) {
                OfflineBanner(offline = vm.offline, queued = vm.queued)
            }
            // Une pastille par boîte, seulement quand il y en a plusieurs
            // (#25734) : une seule boîte n'a rien à distinguer.
            val comptes = vm.config.accounts
            val couleurs = remember(comptes) { couleursDesComptes(comptes) }
            if (comptes.size > 1) {
                AccountRow(
                    comptes = comptes,
                    couleurs = couleurs,
                    selected = vm.accountId,
                    onSelect = vm::selectAccount,
                )
            }
            FilterRow(
                selected = vm.filter,
                // Les sections comptent la boîte filtrée ; la pastille de
                // l'onglet, elle, reste sur toutes (voir `MailCounts.pourCompte`).
                counts = vm.counts.pourCompte(vm.accountId),
                // Les DEUX piles : la personne compte des brouillons, pas
                // des endroits où ils dorment.
                draftCount = drafts.size + vm.serverDrafts.size,
                onSelect = vm::selectFilter,
            )
            HorizontalDivider()
            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    // Deux piles, une section : ceux de l'appareil et ceux
                    // du poste. Ni pagination ni gestes de tri — rien de ce
                    // que la liste du serveur fait sur des courriels reçus.
                    vm.filter == MailFilter.DRAFTS -> DraftList(
                        drafts = drafts,
                        serverDrafts = vm.serverDrafts,
                        scheduled = vm.scheduled,
                        serverError = vm.serverDraftsError,
                        onOpen = onOpenDraft,
                        onOpenServer = onOpenServerDraft,
                        onDelete = vm::deleteDraft,
                        onDeleteServer = vm::deleteServerDraft,
                        onUnschedule = vm::unschedule,
                    )
                    !vm.firstLoadDone -> CenteredSpinner()
                    vm.threads.isEmpty() -> EmptyState(vm.filter, vm.searchTerm)
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(vm.threads, key = { it.threadKey }) { thread ->
                            SwipeActionRow(
                                // Already handled: swiping should put it back,
                                // not archive something that already is.
                                restore = thread.isHandled,
                                // Pendant une sélection, le glissement est
                                // refusé : viser une case et emporter la ligne
                                // d'à côté serait le pire des deux gestes.
                                startAction = if (vm.selectionMode) SwipeAction.NONE
                                else swipe.mailStart,
                                endAction = if (vm.selectionMode) SwipeAction.NONE
                                else swipe.mailEnd,
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
                                    // Le liseré de la boîte, sauf quand la liste
                                    // n'en montre qu'une : il ne dirait rien.
                                    couleurBoite = if (comptes.size > 1 && vm.accountId == null)
                                        thread.accountId?.let { couleurs[it] }?.let(::couleurHex)
                                    else null,
                                    onClick = {
                                        if (vm.selectionMode) vm.toggleSelect(thread)
                                        else onOpenThread(thread.threadKey)
                                    },
                                    // Appui long : on entre en sélection, comme
                                    // partout ailleurs sur Android. La feuille
                                    // d'actions reste à un tap, sous « ⋮ ».
                                    onLongClick = { vm.toggleSelect(thread) },
                                    selected = thread.threadKey in vm.selection,
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
 * Barre contextuelle de sélection.
 *
 * Elle REMPLACE la barre de titre au lieu de s'y ajouter : c'est ce qui rend
 * évident que les taps ne veulent plus dire « ouvrir », et le retour arrière
 * la ferme comme n'importe quel mode.
 */
@Composable
private fun SelectionBar(
    count: Int,
    restoring: Boolean,
    snoozePreset: com.bluefoxconsultant.sms.data.SnoozePreset?,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onMarkRead: () -> Unit,
    onSnooze: (Long) -> Unit,
    onMore: (() -> Unit)?,
) {
    TopAppBar(
        title = { Text("$count", fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_exit_selection))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = BrandAccent,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.common_select_all))
            }
            IconButton(onClick = onMarkRead) {
                Icon(Icons.Filled.MarkEmailRead, contentDescription = stringResource(R.string.common_mark_read))
            }
            if (snoozePreset != null && !restoring) {
                IconButton(onClick = { onSnooze(snoozePreset.untilMs) }) {
                    Icon(
                        Icons.Filled.Snooze,
                        contentDescription = stringResource(R.string.mail_list_snooze_tomorrow),
                    )
                }
            }
            IconButton(onClick = if (restoring) onRestore else onArchive) {
                Icon(
                    if (restoring) Icons.Filled.Inbox else Icons.Filled.Archive,
                    contentDescription = if (restoring) stringResource(R.string.mail_list_restore)
                    else stringResource(R.string.common_archive),
                )
            }
            if (onMore != null) {
                IconButton(onClick = onMore) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mail_list_more_actions))
                }
            }
        },
    )
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
            pluralStringResource(R.plurals.mail_offline_cached_queued, queued, queued)
        offline -> stringResource(R.string.mail_offline_last_sync)
        else -> pluralStringResource(R.plurals.mail_queued_actions, queued, queued)
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
 * Les brouillons, des deux bords.
 *
 * Deux piles dans une seule section, et rien ne les fusionne. Celle de
 * l'appareil est un fichier local : elle survit à un écran quitté, hors ligne
 * comprise. Celle du poste vient de `bf_email` (#25579) : elle est accrochée
 * à une fiche Odoo, elle s'ouvre, se modifie et s'envoie à distance.
 *
 * Elles restent VISIBLEMENT distinctes, et ce n'est pas décoratif : un
 * brouillon du poste peut partir d'ici, celui de l'appareil doit d'abord être
 * envoyé, et hors ligne l'un s'ouvre quand l'autre non. Les confondre
 * ferait promettre à l'un ce que seul l'autre tient.
 *
 * Deux gestes par ligne, et pas plus : reprendre, ou jeter. Tout ce que la
 * liste du serveur sait faire — archiver, reporter, router vers un
 * enregistrement — suppose un courriel reçu ; un brouillon n'en est pas un.
 */
@Composable
private fun DraftList(
    drafts: List<MailDraft>,
    serverDrafts: List<ServerDraft>,
    scheduled: List<ScheduledMail>,
    serverError: UiText?,
    onOpen: (MailDraft) -> Unit,
    onOpenServer: (ServerDraft) -> Unit,
    onDelete: (MailDraft) -> Unit,
    onDeleteServer: (ServerDraft) -> Unit,
    onUnschedule: (ScheduledMail) -> Unit,
) {
    if (drafts.isEmpty() && serverDrafts.isEmpty() && scheduled.isEmpty() && serverError == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.mail_drafts_empty),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        // Les programmés d'abord : ce sont les seuls qui partiront tout seuls.
        if (scheduled.isNotEmpty()) {
            item(key = "entete-programmes") {
                DraftSectionHeader(stringResource(R.string.mail_scheduled_section))
            }
        }
        items(scheduled, key = { "prog-${it.id}" }) { envoi ->
            ScheduledRow(envoi = envoi, onUnschedule = { onUnschedule(envoi) })
        }
        if (serverDrafts.isNotEmpty()) {
            item(key = "entete-poste") {
                DraftSectionHeader(stringResource(R.string.mail_drafts_started_on_desktop))
            }
        }
        items(serverDrafts, key = { "srv-${it.id}" }) { draft ->
            DraftRow(
                title = draft.label.asString(),
                subtitle = draft.recipients.asString(),
                // La fiche porteuse plutôt que le mode : c'est ce qui situe un
                // brouillon du poste, et ce que le téléphone ne devine pas.
                footer = listOf(draft.record?.name.orEmpty(),
                                relativeTime(draft.savedMs))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                attachmentCount = draft.attachments.size,
                onOpen = { onOpenServer(draft) },
                onDelete = { onDeleteServer(draft) },
            )
        }
        if (serverError != null) {
            item(key = "erreur-poste") {
                Text(
                    serverError.asString(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        if (drafts.isNotEmpty() && serverDrafts.isNotEmpty()) {
            item(key = "entete-appareil") {
                DraftSectionHeader(stringResource(R.string.mail_drafts_on_this_device))
            }
        }
        items(drafts, key = { it.id }) { draft ->
            DraftRow(
                title = draft.label.asString(),
                subtitle = draft.recipients.asString(),
                footer = listOf(draft.kindLabel.asString(), relativeTime(draft.savedMs))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                attachmentCount = draft.attachments.size,
                onOpen = { onOpen(draft) },
                onDelete = { onDelete(draft) },
            )
        }
    }
}

/**
 * Un envoi programmé. Un seul geste : le retenir, ce qui le remet dans les
 * brouillons du poste. Pas d'« envoyer maintenant » : devancer une heure
 * choisie d'un doigt sur un petit écran est trop facile.
 */
@Composable
private fun ScheduledRow(envoi: ScheduledMail, onUnschedule: () -> Unit) {
    val quand = remember(envoi.scheduledMs) {
        java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.getDefault())
            .format(java.time.Instant.ofEpochMilli(envoi.scheduledMs).atZone(java.time.ZoneId.systemDefault()))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Icon(
            Icons.Filled.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(envoi.label.asString(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                envoi.recipients.asString(),
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOf(stringResource(R.string.mail_scheduled_at, quand), envoi.record?.name.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUnschedule) {
            Text(stringResource(R.string.mail_scheduled_unschedule), fontSize = 13.sp)
        }
    }
    HorizontalDivider()
}

@Composable
private fun DraftSectionHeader(label: String) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun DraftRow(
    title: String,
    subtitle: String,
    footer: String,
    attachmentCount: Int,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOf(
                    footer,
                    if (attachmentCount > 0) {
                        pluralStringResource(R.plurals.mail_draft_attachment_count, attachmentCount, attachmentCount)
                    } else {
                        ""
                    },
                )
                    .filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.mail_draft_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun FilterRow(
    selected: MailFilter,
    counts: com.bluefoxconsultant.sms.data.MailCounts,
    draftCount: Int,
    onSelect: (MailFilter) -> Unit,
) {
    fun badge(filter: MailFilter): Int? = when (filter) {
        MailFilter.INBOX -> counts.inbox
        MailFilter.UNREAD -> counts.unread
        MailFilter.SNOOZED -> counts.snoozed
        MailFilter.UNROUTED -> counts.unrouted
        // Compté sur l'appareil, pas dans les compteurs du serveur : c'est ce
        // qui rend la pastille utile, un brouillon oublié se voit de loin.
        MailFilter.DRAFTS -> draftCount
        else -> null
    }?.takeIf { it > 0 }

    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        items(MailFilter.entries.toList()) { filter ->
            val count = badge(filter)
            // Un pictogramme par boîte : sept libellés qui défilent se lisent
            // mal, un pictogramme se reconnaît avant qu'on l'ait lu. La
            // correspondance vit dans `iconeDeBoite`, à côté de l'enum.
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                leadingIcon = {
                    Icon(
                        iconeDeBoite(filter),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = {
                    val libelle = stringResource(filter.labelRes)
                    Text(if (count != null) "$libelle · $count" else libelle)
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

/**
 * Les boîtes, en pastilles de leur couleur. « Toutes » d'abord ; toucher la
 * boîte choisie revient à toutes.
 */
@Composable
private fun AccountRow(
    comptes: List<com.bluefoxconsultant.sms.data.MailAccount>,
    couleurs: Map<Int, String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.mail_accounts_all)) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        items(comptes, key = { it.id }) { compte ->
            val teinte = couleurs[compte.id]?.let(::couleurHex) ?: BrandAccent
            FilterChip(
                selected = selected == compte.id,
                onClick = { onSelect(compte.id) },
                leadingIcon = {
                    Box(Modifier.size(10.dp).background(teinte, CircleShape))
                },
                label = {
                    Text(
                        libelleCompte(compte),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

/** `#RRGGBB` validé en amont par `couleursDesComptes`. */
internal fun couleurHex(hex: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

@Composable
private fun MailSearchBar(term: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = term,
                onValueChange = onChange,
                placeholder = {
                    Text(
                        stringResource(R.string.mail_list_search_placeholder),
                        color = Color.White.copy(alpha = 0.7f),
                    )
                },
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
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close), tint = Color.White)
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
        search.isNotBlank() -> stringResource(R.string.mail_list_no_results_for, search)
        filter == MailFilter.INBOX -> stringResource(R.string.mail_list_inbox_empty)
        else -> stringResource(R.string.mail_list_nothing_in, stringResource(filter.labelRes))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row {
            Spacer(Modifier.width(24.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(24.dp))
        }
    }
}
