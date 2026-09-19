package com.bluefoxconsultant.sms.ui.genfox

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.Manifest
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluefoxconsultant.sms.data.GenfoxMessage
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.GenfoxTool
import com.bluefoxconsultant.sms.ui.speech.DictateButton
import com.bluefoxconsultant.sms.ui.speech.appendSpoken
import com.bluefoxconsultant.sms.ui.theme.BrandAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bluefoxconsultant.sms.ui.BoutonTheme
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.asString
import com.bluefoxconsultant.sms.ui.resolve
import com.bluefoxconsultant.sms.ui.uiText

/**
 * Ask GenFox from the phone.
 *
 * The answer is not awaited on an open connection: the question is recorded,
 * the screen polls while it is open, and a push arrives if the phone went back
 * in a pocket. Conversations here are read-only on the server side — the
 * assistant can look things up, not change them — which the empty state says
 * out loud rather than leaving to be discovered.
 *
 * [assist] est vrai quand on arrive par le geste d'assistance du système. Ce
 * geste ne veut pas dire « ouvre l'app » mais « je te parle » : la conversation
 * part donc à neuf, et le micro s'ouvre sans qu'on ait à viser un bouton — ce
 * qui est tout l'intérêt d'un assistant qu'on appelle une main sur le volant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenfoxScreen(
    vm: GenfoxViewModel = viewModel(),
    assist: Boolean = false,
    onAssistConsumed: () -> Unit = {},
    openSession: Int? = null,
    onOpenSessionConsumed: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var historyOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handsFree = remember {
        HandsFreeController(
            context = context,
            scope = scope,
            onQuestion = { vm.ask(it) },
            onNotice = { scope.launch { snackbar.showSnackbar(it) } },
        )
    }
    // The engine and the microphone both outlive a recomposition and neither
    // should outlive the screen.
    DisposableEffect(Unit) { onDispose { handsFree.release() } }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) handsFree.start()
        else scope.launch { snackbar.showSnackbar(context.getString(R.string.gen_mic_denied)) }
    }

    // Le bouton de la barre et le geste d'assistance passent par ici : demander
    // la permission à deux endroits, c'est se garantir qu'un des deux l'oublie.
    fun startHandsFree() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) handsFree.start() else askPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Le geste d'assistance : conversation neuve, micro ouvert. Consommé tout
    // de suite, sinon un simple retour à l'onglet repartirait à zéro et
    // effacerait la question qu'on venait de poser.
    //
    // ⚠️ On ATTEND que la fenêtre soit RESUMED avant d'ouvrir le micro. Le
    // geste d'assistance démarre l'app depuis un service, donc depuis
    // l'arrière-plan, et Android refuse les permissions « pendant
    // l'utilisation » — dont RECORD_AUDIO — tant qu'aucune activité visible ne
    // les justifie. Le symptôme n'est PAS une erreur : l'enregistrement
    // démarre, n'entend rien, et se solde trente secondes plus tard par
    // « Rien entendu ». Ce qui se lit, à raison, comme « il ne m'écoute pas ».
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(assist) {
        if (!assist) return@LaunchedEffect
        vm.startFresh()
        lifecycleOwner.lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) { }
        startHandsFree()
        onAssistConsumed()
    }

    // La notification d'une réponse ouvre SA conversation, pas la dernière.
    LaunchedEffect(openSession) {
        val id = openSession ?: return@LaunchedEffect
        vm.open(id)
        onOpenSessionConsumed()
    }

    // A finished turn is what drives the loop forward: say it, then listen again.
    val last = vm.messages.lastOrNull()
    LaunchedEffect(last?.state, last?.content) {
        if (!handsFree.isOn || last == null || last.isUser) return@LaunchedEffect
        when {
            last.isPending -> handsFree.waiting()
            // Arrêter veut dire « tais-toi » : la boucle ne relit rien et ne
            // rouvre pas le micro.
            last.isStopped -> handsFree.stop()
            last.isError -> handsFree.failed()
            last.content.isNotBlank() -> handsFree.answered(last.content)
        }
    }

    LaunchedEffect(vm.error) {
        vm.error?.let {
            snackbar.showSnackbar(it.resolve(context))
            vm.clearError()
        }
    }
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    // Ligne d'état du tour en cours (BF #25718). ⚠️ Les bulles sont indexées
    // par hashCode() : chaque sondage recrée celle du tour, et tout `remember`
    // posé dedans repartirait à zéro toutes les 700 ms. Le début du tour, le
    // dernier changement d'étape et les étapes dépliées vivent donc ici.
    val enCours = vm.messages.lastOrNull()?.takeIf { it.isPending }
    var debutTour by remember { mutableStateOf(0L) }
    var derniereEtapeA by remember { mutableStateOf(0L) }
    var maintenant by remember { mutableStateOf(System.currentTimeMillis()) }
    val deplies = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(enCours != null) {
        if (enCours == null) return@LaunchedEffect
        debutTour = System.currentTimeMillis()
        derniereEtapeA = debutTour
        while (true) {
            maintenant = System.currentTimeMillis()
            delay(1000)
        }
    }
    LaunchedEffect(enCours?.tools?.size, enCours?.tools?.lastOrNull()?.detail) {
        if (enCours != null) derniereEtapeA = System.currentTimeMillis()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.tab_gen), fontWeight = FontWeight.SemiBold, maxLines = 1)
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
                    BoutonTheme()
                    IconButton(
                        onClick = {
                            if (handsFree.isOn) handsFree.stop() else startHandsFree()
                        },
                    ) {
                        Icon(
                            if (handsFree.isOn) Icons.Filled.Hearing else Icons.Filled.HeadsetMic,
                            contentDescription = if (handsFree.isOn) stringResource(R.string.gen_hands_free_stop)
                            else stringResource(R.string.gen_hands_free),
                        )
                    }
                    IconButton(onClick = { vm.reset() }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.gen_new_conversation))
                    }
                    IconButton(onClick = { vm.refreshSessions(); historyOpen = true }) {
                        Icon(Icons.Filled.History, contentDescription = stringResource(R.string.gen_conversations))
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
                        items(vm.messages, key = { it.hashCode() }) { message ->
                            Bubble(
                                message,
                                attente = if (message.isPending) Attente(
                                    secondes = (maintenant - debutTour) / 1000,
                                    depuisEtape = (maintenant - derniereEtapeA) / 1000,
                                ) else null,
                                deplie = deplies[message.id] == true,
                                onDeplier = { deplies[message.id] = deplies[message.id] != true },
                                onSpeak = { handsFree.say(message.content) },
                            )
                        }
                    }
                }
            }
            if (handsFree.isOn) HandsFreeBand(
                state = handsFree.state,
                heard = handsFree.heard,
                phaseSince = handsFree.phaseSince,
            )
            val config by Graph.genfoxStore.config.collectAsStateWithLifecycle()
            Asker(
                asking = vm.asking,
                // Sans serveur qui sache arrêter, le bouton reste le témoin
                // d'attente d'avant : un Arrêter qui ne fait rien serait pire.
                canStop = config.canStop,
                stopEnabled = vm.tourArretable != null && !vm.stopping,
                stopping = vm.stopping,
                questionRendue = vm.questionRendue,
                onQuestionRestituee = vm::questionRestituee,
                snackbar = snackbar,
                onAsk = vm::ask,
                onStop = vm::stop,
            )
        }
    }

    if (historyOpen) {
        ModalBottomSheet(onDismissRequest = { historyOpen = false }) {
            Text(
                stringResource(R.string.gen_conversations),
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
                    Column(Modifier.weight(1f)) {
                        Text(
                            session.name.ifBlank { stringResource(R.string.gen_untitled) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (session.id == vm.sessionId) FontWeight.SemiBold
                            else FontWeight.Normal,
                        )
                        // Plusieurs conversations travaillent à la fois : la
                        // liste dit où une réponse va tomber.
                        if (session.busy) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    strokeWidth = 1.5.dp,
                                    modifier = Modifier.size(10.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.gen_session_busy),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    IconButton(onClick = { vm.delete(session.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.gen_delete))
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
            stringResource(R.string.gen_empty_title),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.gen_empty_text),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bubble(
    message: GenfoxMessage,
    attente: Attente?,
    deplie: Boolean,
    onDeplier: () -> Unit,
    onSpeak: () -> Unit,
) {
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
        // Les étapes, repliées au-dessus de la réponse comme au bureau : une
        // pastille par outil faisait 12 pastilles par tour en médiane.
        if (!mine && message.tools.isNotEmpty()) {
            Etapes(message.tools, attente, deplie, onDeplier)
        }
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
            Column {
                // Une bulle d'erreur posée par l'app porte son texte à part, traduit.
                // Un tour arrêté avant tout mot n'a que le repli du serveur
                // (« (No response) ») : il ne se montre pas.
                val contenu = message.avis?.asString()
                    ?: message.content.takeUnless { message.isStopped && it == NO_RESPONSE }
                    ?: ""
                if (contenu.isNotBlank() || (!message.isPending && !message.isStopped)) {
                    SelectionContainer {
                        Text(
                            // Markdown, because that is what the assistant writes for
                            // the desktop panel. A caret while it streams, so a pause
                            // reads as thinking rather than as a finished answer.
                            text = renderMarkdown(
                                contenu + if (message.isPending) "▌" else "",
                            ),
                            color = foreground,
                            fontSize = 15.sp,
                        )
                    }
                }
                if (attente != null) {
                    if (contenu.isNotBlank()) Spacer(Modifier.size(6.dp))
                    LigneEtat(foreground, libelleAttente(message, attente).asString(), attente.secondes)
                }
                if (message.isStopped) {
                    if (contenu.isNotBlank()) Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.gen_stopped),
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = foreground.copy(alpha = 0.75f),
                    )
                }
            }
        }
        if (!mine && !message.isPending && !message.isError) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSpeak, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.gen_read_aloud),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (message.usage.hasAny) Text(
                    usageLabel(message),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * « 12,3 k jetons · 0,040 $ · 4,2 s » — ce que le tour a réellement consommé.
 *
 * ⚠️ Les jetons affichés sont les NEUFS, pas le total : le total additionne le
 * contexte relu, qui n'est pas du travail neuf et pesait ~93 % du volume. Même
 * grandeur que le Cockpit Odoo et que le panneau web — si ce chiffre change
 * ici, il doit changer aux trois endroits, sinon les écrans se contredisent.
 */
@Composable
private fun usageLabel(message: GenfoxMessage): String {
    val usage = message.usage
    val tokens = if (usage.displayTokens > 0) usage.displayTokens else usage.outputTokens
    val parts = mutableListOf<String>()
    parts += if (tokens >= 1000) stringResource(R.string.gen_usage_thousand_tokens, tokens / 1000.0)
    else pluralStringResource(R.plurals.gen_usage_tokens, tokens, tokens)
    if (usage.costUsd > 0) parts += stringResource(R.string.gen_usage_cost, usage.costUsd)
    if (usage.durationMs > 0) parts += "%.1f s".format(usage.durationMs / 1000.0)
    return parts.joinToString(" · ")
}

/** Le repli que le serveur enregistre quand un tour n'a rien écrit. */
private const val NO_RESPONSE = "(No response)"

/** Où en est le tour en cours, en secondes, lu au niveau de l'écran. */
private data class Attente(val secondes: Long, val depuisEtape: Long)

/**
 * Formules du renard, les mêmes que le panneau web (gen_wait.js). Le mobile ne
 * reçoit pas les pings de réflexion : il les tire quand aucune étape neuve
 * n'est arrivée depuis un moment, et en change toutes les six secondes.
 * ⚠️ Dans l'ordre de `foxPhrase`, et mot pour mot dans les deux langues : les
 * msgid de gen_wait.js en anglais, son `fr_CA.po` en français.
 */
private val FORMULES_RENARD = listOf(
    R.string.gen_fox_0,
    R.string.gen_fox_1,
    R.string.gen_fox_2,
    R.string.gen_fox_3,
    R.string.gen_fox_4,
    R.string.gen_fox_5,
    R.string.gen_fox_6,
    R.string.gen_fox_7,
    R.string.gen_fox_8,
    R.string.gen_fox_9,
    R.string.gen_fox_10,
    R.string.gen_fox_11,
    R.string.gen_fox_12,
)

/** Une étape neuve reste à l'écran ce temps-là avant de céder la place au renard. */
private const val ETAPE_RECENTE_S = 8L

private fun libelleAttente(message: GenfoxMessage, attente: Attente): UiText {
    val derniere = message.tools.lastOrNull()
    if (derniere != null && attente.depuisEtape < ETAPE_RECENTE_S) return derniere.texte
    val rang = (message.id + attente.secondes / 6).toInt()
    return uiText(FORMULES_RENARD[Math.floorMod(rang, FORMULES_RENARD.size)])
}

/** « 42 s », « 1 min 05 s » : le même format que le panneau web. */
private fun duree(secondes: Long): String =
    if (secondes < 60) "$secondes s" else "${secondes / 60} min ${"%02d".format(secondes % 60)} s"

@Composable
private fun Etapes(
    tools: List<GenfoxTool>,
    attente: Attente?,
    deplie: Boolean,
    onDeplier: () -> Unit,
) {
    val couleur = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.widthIn(max = 320.dp).padding(bottom = 3.dp)) {
        // Pendant que Gen réfléchit, le résumé rappelle la dernière étape : la
        // ligne d'état est alors passée au renard.
        val rappel = if (attente != null && attente.depuisEtape >= ETAPE_RECENTE_S) {
            " · " + tools.last().texte.asString()
        } else {
            ""
        }
        Text(
            (if (deplie) "▾ " else "▸ ") +
                pluralStringResource(R.plurals.gen_steps, tools.size, tools.size) + rappel,
            fontSize = 12.sp,
            color = couleur,
            maxLines = if (deplie) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onDeplier).padding(vertical = 2.dp),
        )
        if (deplie) {
            tools.forEachIndexed { index, tool ->
                Text(
                    "${index + 1}. ${tool.texte.asString()}",
                    fontSize = 12.sp,
                    color = couleur,
                    modifier = Modifier.padding(start = 10.dp, top = 1.dp),
                )
            }
        }
    }
}

/** Trois points qui respirent, ce que Gen fait, et depuis combien de temps. */
@Composable
private fun LigneEtat(color: Color, libelle: String, secondes: Long) {
    val transition = rememberInfiniteTransition(label = "reflexion")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 180, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "point$index",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .background(color.copy(alpha = alpha), CircleShape),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            libelle,
            fontSize = 13.sp,
            color = color.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(duree(secondes), fontSize = 12.sp, color = color.copy(alpha = 0.7f), maxLines = 1)
    }
}

@Composable
private fun Asker(
    asking: Boolean,
    canStop: Boolean,
    stopEnabled: Boolean,
    stopping: Boolean,
    questionRendue: String?,
    onQuestionRestituee: () -> Unit,
    snackbar: SnackbarHostState,
    onAsk: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // Une question que le serveur n'a pas prise revient ici, devant ce qu'on
    // aurait commencé à taper entre-temps.
    LaunchedEffect(questionRendue) {
        val rendue = questionRendue ?: return@LaunchedEffect
        text = if (text.isBlank()) rendue else rendue + "\n" + text
        onQuestionRestituee()
    }

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
                placeholder = { Text(stringResource(R.string.gen_question_hint)) },
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
            if (asking && canStop) {
                // Pendant que Gen répond ICI, le bouton d'envoi devient Arrêter
                // (#25734). La zone de saisie reste libre : on prépare la
                // question suivante, ou on la pose dans une autre conversation.
                IconButton(
                    onClick = onStop,
                    enabled = stopEnabled,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .size(48.dp)
                        .background(
                            // Le disque sombre au carré blanc des assistants,
                            // pas le rouge d'une erreur : arrêter est un geste
                            // ordinaire, pas un incident.
                            color = if (stopEnabled) MaterialTheme.colorScheme.inverseSurface
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape,
                        ),
                ) {
                    if (stopping) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.gen_stop),
                            tint = if (stopEnabled) MaterialTheme.colorScheme.inverseOnSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
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
                            contentDescription = stringResource(R.string.common_send),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/**
 * La bande d'état du mode mains libres.
 *
 * Elle porte trois choses que le premier essai sur le terrain a réclamées : le
 * temps écoulé dans l'étape en cours (une transcription qui prend vingt
 * secondes sans rien bouger se lit comme un gel), une barre qui avance pendant
 * que Gen travaille, et le texte compris — la seule façon de savoir, avant que
 * la réponse arrive, que la question posée est bien celle qu'on a dite.
 *
 * Le compteur est remis à zéro par [phaseSince] plutôt que par un `remember`
 * sur l'étape : deux tours de suite passent par le même état, et un `remember`
 * clé sur l'énumération ne verrait pas le second commencer.
 */
@Composable
private fun HandsFreeBand(state: HandsFreeState, heard: String?, phaseSince: Long) {
    val label = when (state) {
        HandsFreeState.Listening -> stringResource(R.string.gen_hands_free_listening)
        HandsFreeState.Sending -> stringResource(R.string.gen_hands_free_sending)
        HandsFreeState.Waiting -> stringResource(R.string.gen_hands_free_waiting)
        HandsFreeState.Speaking -> stringResource(R.string.gen_hands_free_speaking)
        HandsFreeState.Off -> ""
    }
    if (label.isBlank()) return

    // Le décompte des secondes n'a de sens que pendant une attente : pendant
    // qu'on parle ou que le téléphone parle, il ne ferait que presser.
    val counts = state == HandsFreeState.Sending || state == HandsFreeState.Waiting
    var seconds by remember(phaseSince) { mutableIntStateOf(0) }
    LaunchedEffect(phaseSince, counts) {
        if (!counts) return@LaunchedEffect
        while (true) {
            delay(1_000)
            seconds = ((SystemClock.elapsedRealtime() - phaseSince) / 1_000).toInt()
        }
    }

    Surface(color = BrandAccent) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (counts) Icons.Filled.HourglassTop else Icons.Filled.Hearing,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (counts && seconds > 0) Text(
                    "$seconds s",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Ce qui a été compris, dès que ça existe — donc pendant que Gen
            // réfléchit, pas seulement quand il a fini.
            if (!heard.isNullOrBlank() && state != HandsFreeState.Listening) {
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.gen_heard, heard),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (counts) {
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
            }
        }
    }
}
