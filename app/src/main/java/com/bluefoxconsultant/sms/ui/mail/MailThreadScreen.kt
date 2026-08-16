@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.mail

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailAttachment
import com.bluefoxconsultant.sms.data.QuickAction
import com.bluefoxconsultant.sms.data.MailConfig
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.ui.clockTime
import com.bluefoxconsultant.sms.ui.relativeTime
import com.bluefoxconsultant.sms.ui.theme.BrandAccent
import kotlinx.coroutines.launch

@Suppress("UNCHECKED_CAST")
private class ThreadVmFactory(private val threadKey: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MailThreadViewModel(threadKey) as T
}

@Composable
fun MailThreadScreen(
    threadKey: String,
    config: MailConfig,
    onBack: () -> Unit,
    onReply: (Int, String) -> Unit,
) {
    val vm: MailThreadViewModel = viewModel(
        key = threadKey,
        factory = ThreadVmFactory(threadKey),
    )
    val snackbar = remember { SnackbarHostState() }
    var sheetFor by remember { mutableStateOf<MailMessage?>(null) }
    var routeFor by remember { mutableStateOf<MailMessage?>(null) }
    val quickActions by Graph.uiPrefs.quickActionsFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun openAttachment(message: MailMessage, attachment: MailAttachment) {
        scope.launch {
            val result = AttachmentOpener.open(context, message.id, attachment)
            if (result is AttachmentOpener.Result.Failed) {
                snackbar.showSnackbar(result.message)
            }
        }
    }

    LaunchedEffect(vm.notice, vm.error) {
        val message = vm.error ?: vm.notice
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.dismissNotice()
        }
    }

    val last = vm.messages.lastOrNull()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        vm.subject.ifBlank { "(sans objet)" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (last != null) {
                        quickActions.forEach { action ->
                            IconButton(onClick = {
                                when (action) {
                                    QuickAction.ARCHIVE -> vm.archive(last) { onBack() }
                                    QuickAction.MARK_READ -> vm.markRead(last)
                                    QuickAction.ROUTE -> routeFor = last
                                    QuickAction.TASK -> vm.spawn(last, "task")
                                    QuickAction.SNOOZE ->
                                        config.snoozePresets
                                            .firstOrNull { it.key == "tomorrow" }
                                            ?.let { vm.snooze(last, it.untilMs) { onBack() } }
                                }
                            }) {
                                Icon(
                                    when (action) {
                                        QuickAction.ARCHIVE -> Icons.Filled.Archive
                                        QuickAction.MARK_READ -> Icons.Filled.Drafts
                                        QuickAction.ROUTE -> Icons.Filled.Link
                                        QuickAction.TASK -> Icons.AutoMirrored.Filled.PlaylistAdd
                                        QuickAction.SNOOZE -> Icons.Filled.Schedule
                                    },
                                    contentDescription = action.label,
                                )
                            }
                        }
                        IconButton(onClick = { sheetFor = last }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Options")
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
        bottomBar = {
            if (last != null) ReplyBar(onPick = { mode -> onReply(last.id, mode) })
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                vm.loading && vm.messages.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BrandAccent)
                    }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (vm.offline) {
                        item { InfoBanner("Hors ligne — version en cache de ce fil.") }
                    }
                    if (vm.truncated) {
                        item { TruncatedBanner() }
                    }
                    items(vm.messages, key = { it.id }) { message ->
                        MessageCard(
                            message = message,
                            expanded = message.id in vm.expanded,
                            imagesAllowed = vm.imagesAllowedFor(message),
                            onToggle = { vm.toggle(message) },
                            onLoadImages = { vm.loadImages(message) },
                            onOpenAttachment = { msg, att -> openAttachment(msg, att) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }

    sheetFor?.let { target ->
        MailActionsSheet(
            message = target,
            config = config,
            onDismiss = { sheetFor = null },
            onArchive = { sheetFor = null; vm.archive(target) { onBack() } },
            onRestore = { sheetFor = null; vm.restore(target) { onBack() } },
            onMarkRead = { sheetFor = null; vm.markRead(target) },
            onSnooze = { until -> sheetFor = null; vm.snooze(target, until) { onBack() } },
            onSpawn = { kind -> sheetFor = null; vm.spawn(target, kind) },
            onRoute = { routeFor = target; sheetFor = null },
        )
    }

    routeFor?.let { target ->
        RoutePickerDialog(
            config = config,
            onDismiss = { routeFor = null },
            onPick = { model, recordId ->
                routeFor = null
                vm.route(target, model, recordId)
            },
        )
    }
}

@Composable
private fun InfoBanner(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TruncatedBanner() {
    Text(
        text = "Fil long : seuls les messages les plus récents sont affichés.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun MessageCard(
    message: MailMessage,
    expanded: Boolean,
    imagesAllowed: Boolean,
    onToggle: () -> Unit,
    onLoadImages: () -> Unit,
    onOpenAttachment: (MailMessage, MailAttachment) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.correspondent,
                        fontWeight = if (message.isUnread) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = message.dateMs?.let { clockTime(it) }.orEmpty(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (message.isOutgoing) "à ${message.to.ifBlank { "…" }}"
                    else relativeTime(message.dateMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!expanded) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = message.preview,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (expanded) {
            if (message.blockedImages > 0 && !imagesAllowed) {
                BlockedImagesBar(count = message.blockedImages, onLoad = onLoadImages)
            }
            val body = message.bodyHtml
            if (body == null) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = BrandAccent) }
            } else {
                MailBodyView(
                    html = body,
                    allowRemoteContent = imagesAllowed,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (message.attachments.isNotEmpty()) {
                AttachmentList(message, onOpen = { onOpenAttachment(message, it) })
            }
        }
    }
}

@Composable
private fun BlockedImagesBar(count: Int, onLoad: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (count == 1) "1 image distante bloquée"
                else "$count images distantes bloquées",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onLoad) { Text("Afficher", fontSize = 12.sp) }
    }
}

@Composable
private fun AttachmentList(
    message: MailMessage,
    onOpen: (MailAttachment) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        message.attachments.forEach { attachment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(attachment) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = BrandAccent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        attachment.name,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        humanSize(attachment.size),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f Mo".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%d ko".format(bytes / 1024)
    else -> "$bytes o"
}

@Composable
private fun ReplyBar(onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReplyButton(Icons.AutoMirrored.Filled.Reply, "Répondre") { onPick("reply") }
        ReplyButton(Icons.AutoMirrored.Filled.ReplyAll, "À tous") { onPick("reply_all") }
        ReplyButton(Icons.AutoMirrored.Filled.Forward, "Transférer") { onPick("forward") }
    }
}

@Composable
private fun ReplyButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = BrandAccent)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp)
        }
    }
}
