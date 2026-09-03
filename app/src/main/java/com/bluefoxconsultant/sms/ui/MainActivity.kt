package com.bluefoxconsultant.sms.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bluefoxconsultant.sms.assist.EXTRA_ASSIST
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.ShareIntake
import com.bluefoxconsultant.sms.data.SharedContent
import com.bluefoxconsultant.sms.sip.CallGap
import com.bluefoxconsultant.sms.sip.rememberCallGaps
import com.bluefoxconsultant.sms.sip.cheminReglage
import com.bluefoxconsultant.sms.sip.settingsIntentFor
import com.bluefoxconsultant.sms.sip.tairePourToujours
import com.bluefoxconsultant.sms.sip.SipEngine
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.push.Notifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluefoxconsultant.sms.ui.compose.ComposeScreen
import com.bluefoxconsultant.sms.ui.conversation.ConversationScreen
import com.bluefoxconsultant.sms.ui.agenda.AgendaScreen
import com.bluefoxconsultant.sms.ui.agenda.TachesScreen
import com.bluefoxconsultant.sms.ui.genfox.GenfoxScreen
import com.bluefoxconsultant.sms.ui.phone.PhoneScreen
import com.bluefoxconsultant.sms.ui.instance.InstanceScreen
import com.bluefoxconsultant.sms.ui.login.LoginScreen
import com.bluefoxconsultant.sms.ui.mail.MailComposeScreen
import com.bluefoxconsultant.sms.ui.mail.MailListScreen
import com.bluefoxconsultant.sms.ui.mail.MailListViewModel
import com.bluefoxconsultant.sms.ui.mail.MailThreadScreen
import com.bluefoxconsultant.sms.ui.settings.SettingsScreen
import com.bluefoxconsultant.sms.ui.share.ShareScreen
import com.bluefoxconsultant.sms.ui.theme.BrandAccent
import com.bluefoxconsultant.sms.ui.theme.BfSmsTheme
import com.bluefoxconsultant.sms.ui.threads.ArchivedScreen
import com.bluefoxconsultant.sms.ui.threads.ThreadsScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    // Push notification → open a conversation.
    private val pendingThread = mutableStateOf<Int?>(null)
    private val pendingMailThread = mutableStateOf<String?>(null)
    private val pendingGenfox = mutableStateOf<Int?>(null)
    private val pendingAssist = mutableStateOf(false)
    private val pendingDial = mutableStateOf<String?>(null)

    // « Envoyer vers » depuis une autre app. Le contenu lui-même vit dans
    // ShareIntake — voir là-bas pourquoi il ne voyage pas dans la route.
    private val pendingShare = mutableStateOf<SharedContent?>(null)

    // Web-login redirect (com.bluefoxconsultant.sms://auth?code=&state=).
    private val pendingAuthUri = mutableStateOf<String?>(null)

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeIntent(intent)
        requestNotificationPermission()
        setContent {
            BfSmsTheme {
                AppRoot(
                    pendingThread, pendingMailThread, pendingGenfox,
                    pendingAssist, pendingDial, pendingAuthUri, pendingShare,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent == null) return
        // Le partage d'abord : une intention ACTION_SEND ne porte ni données
        // ni extras de notification, mais elle arrive sur la même activité que
        // tout le reste et doit être reconnue avant les tests qui suivent.
        //
        // ⚠️ Sauf en revenant par les RÉCENTS : Android y redélivre l'intention
        // d'origine telle quelle, et sans ce test la photo partagée hier
        // reviendrait s'offrir à chaque retour par la liste des applications.
        // Le drapeau n'est examiné que pour le partage : les autres chemins
        // (notification, tel:, assistance) gardent le comportement qu'ils ont.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        ShareIntake.from(intent)?.takeIf { !fromHistory }?.let {
            ShareIntake.offer(it)
            pendingShare.value = it
            return
        }
        val data = intent.data
        if (data != null && data.scheme == AUTH_SCHEME && data.host == AUTH_HOST) {
            pendingAuthUri.value = data.toString()
            return
        }
        // tel: — le numéro est dans la partie spécifique au schéma, pas dans le
        // chemin, et il arrive percent-encodé (le « + » d'un indicatif ressort
        // « %2B »). Un DIAL nu, sans données, ouvre simplement le clavier.
        if (intent.action == Intent.ACTION_DIAL || data?.scheme == "tel") {
            pendingDial.value = data?.schemeSpecificPart?.let(Uri::decode).orEmpty()
            return
        }
        val threadId = intent.getIntExtra(Notifier.EXTRA_THREAD_ID, -1)
        if (threadId > 0) {
            pendingThread.value = threadId
            return
        }
        // A mail push carries the thread key; the batch-summary push carries an
        // empty one, which lands on the mail tab without opening anything.
        if (intent.hasExtra(Notifier.EXTRA_EMAIL_ID)) {
            pendingMailThread.value = intent.getStringExtra(Notifier.EXTRA_THREAD_KEY).orEmpty()
            return
        }
        // Geste d'assistance du système. Distinct de la notification :
        // celle-ci ramène à ce qui vient d'être répondu, alors que le geste
        // veut dire « je te parle maintenant » — donc conversation neuve et
        // micro ouvert, ce que l'écran fait sur ce seul drapeau.
        if (intent.getBooleanExtra(EXTRA_ASSIST, false)) {
            pendingAssist.value = true
            return
        }
        // The assistant finished a turn while the app was away.
        val genfoxSession = intent.getIntExtra(Notifier.EXTRA_GENFOX_SESSION, -1)
        if (genfoxSession > 0) pendingGenfox.value = genfoxSession
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private companion object {
        const val AUTH_SCHEME = "com.bluefoxconsultant.sms"
        const val AUTH_HOST = "auth"
    }
}

@Composable
private fun AppRoot(
    pendingThread: MutableState<Int?>,
    pendingMailThread: MutableState<String?>,
    pendingGenfox: MutableState<Int?>,
    pendingAssist: MutableState<Boolean>,
    pendingDial: MutableState<String?>,
    pendingAuthUri: MutableState<String?>,
    pendingShare: MutableState<SharedContent?>,
) {
    val nav = rememberNavController()
    val tokenStore = Graph.tokenStore
    val tokens by tokenStore.tokensFlow.collectAsState()
    val instance by tokenStore.instanceFlow.collectAsState()

    fun routeFor(signedIn: Boolean, inst: String?): String = when {
        signedIn -> Routes.HOME
        inst != null -> Routes.LOGIN
        else -> Routes.INSTANCE
    }

    val start = routeFor(tokenStore.isSignedIn, tokenStore.instanceUrl)

    // Drive top-level navigation from the (any token, instance) state.
    var lastTarget by remember { mutableStateOf(start) }
    val target = routeFor(tokens.isNotEmpty(), instance)
    LaunchedEffect(target) {
        if (target != lastTarget) {
            nav.navigate(target) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
            lastTarget = target
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.INSTANCE) { InstanceScreen() }
        composable(Routes.LOGIN) {
            LoginScreen(
                instanceUrl = instance ?: tokenStore.instanceUrl.orEmpty(),
                pendingAuthUri = pendingAuthUri.value,
                onAuthConsumed = { pendingAuthUri.value = null },
            )
        }
        composable(Routes.HOME) {
            HomeShell(
                rootNav = nav,
                pendingThread = pendingThread,
                pendingGenfox = pendingGenfox,
                pendingAssist = pendingAssist,
                pendingDial = pendingDial,
                pendingMailThread = pendingMailThread,
                pendingAuthUri = pendingAuthUri,
                pendingShare = pendingShare,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}

/**
 * The two-tab shell. Each tab keeps its own back stack inside a nested
 * NavHost, so switching tabs doesn't unwind the other one, and the tab bar
 * disappears on a detail screen the way it does in Gmail.
 *
 * Only tabs the instance actually advertises **and** that we hold a token for
 * are shown; with one service the bar collapses and the app looks exactly like
 * the single-purpose one it used to be.
 */
@Composable
private fun HomeShell(
    rootNav: NavHostController,
    pendingThread: MutableState<Int?>,
    pendingMailThread: MutableState<String?>,
    pendingGenfox: MutableState<Int?>,
    pendingAssist: MutableState<Boolean>,
    pendingDial: MutableState<String?>,
    pendingAuthUri: MutableState<String?>,
    pendingShare: MutableState<SharedContent?>,
) {
    val tokenStore = Graph.tokenStore
    val tokens by tokenStore.tokensFlow.collectAsState()
    val available by tokenStore.availableFlow.collectAsState()

    // Re-probe on every launch, not only during login. A server can gain the
    // other module after the app was installed — which is exactly what
    // happened here, and an install that only probes at login can never find
    // out.
    LaunchedEffect(Unit) {
        val instance = tokenStore.instanceUrl ?: return@LaunchedEffect
        val pings = withContext(Dispatchers.IO) {
            Service.entries.associateWith { Graph.apiFor(it).pingInfo(instance) }
        }
        val found = pings.filterValues { it != null }.keys
        if (found.isNotEmpty()) tokenStore.saveAvailable(found)
        // Whichever half reports branding wins; absent, the defaults stand.
        pings.values.filterNotNull().firstNotNullOfOrNull { it.branding }?.let {
            Graph.brandStore.save(it.name, it.primary, it.dark)
        }
    }

    // A service earns a tab if the server offers it OR we already hold a token
    // for it. The union matters: relying on the probe alone means one failed
    // /ping — a captive portal, a slow start — hides a half the user is
    // signed in to, with no way back to it. A token is proof enough.
    val tabs = Service.entries.filter { it in available || tokens.containsKey(it) }

    // GenFox is a capability of an existing session, not a Service: it has no
    // login of its own, so it must not join the enum that drives the login
    // screens. It earns a tab only once the server says it is configured.
    val genfox by Graph.genfoxStore.config.collectAsStateWithLifecycle()
    val phone by Graph.phoneStore.config.collectAsStateWithLifecycle()
    // Même raison que GenFox : l'agenda est une capacité de la session en
    // place. Deux onglets en dépendent, l'agenda et les échéances, et ils
    // apparaissent ensemble ou pas du tout — un agenda sans ses échéances
    // laisserait croire que la journée est vide.
    val agenda by Graph.agendaStore.ping.collectAsStateWithLifecycle()
    LaunchedEffect(tokens.isNotEmpty()) {
        if (tokens.isNotEmpty()) {
            Graph.genfoxStore.ensureLoaded()
            // Asked here rather than only from a conversation's call button, so
            // the keypad can earn its own tab.
            Graph.phoneStore.ensureLoaded()
            Graph.agendaStore.ensureLoaded()
        }
    }

    // Le poste SIP démarre avec l'app, pas avec l'onglet du clavier : un poste
    // qui ne s'enregistre qu'une fois l'écran ouvert ne sonnerait jamais pour un
    // appel entrant. Il s'arrête tout seul avec le processus ; on ne le coupe
    // pas au changement d'onglet.
    val context = LocalContext.current
    LaunchedEffect(phone.enabled, phone.extension) {
        if (phone.enabled && phone.extension.isNotBlank()) SipEngine.start(context)
    }

    // La veille du parc démarre avec la session, pas avec un écran : elle n'a
    // pas d'onglet à elle, et le serveur dit lui-même quand s'arrêter (l'usager
    // n'a pas l'hébergement). Elle survit au changement d'onglet, comme le poste.
    val hosting by Graph.hostingStore.state.collectAsStateWithLifecycle()
    val callGaps = rememberCallGaps()
    LaunchedEffect(tokens.isNotEmpty()) {
        if (tokens.isNotEmpty()) Graph.hostingStore.start(context)
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val onRoot = route == Tabs.SMS || route == Tabs.MAIL ||
        route == Tabs.GENFOX || route == Tabs.PHONE ||
        route == Tabs.AGENDA || route == Tabs.TASKS

    // A push for one tab switches to it before opening the detail screen.
    LaunchedEffect(pendingThread.value, tabs) {
        val id = pendingThread.value ?: return@LaunchedEffect
        if (Service.SMS !in tabs) return@LaunchedEffect
        nav.navigate("${Tabs.CONVERSATION}/$id")
        pendingThread.value = null
    }
    // Tapping the assistant's notification lands on its tab. The screen picks
    // the latest conversation itself, which is the one that just answered.
    LaunchedEffect(pendingGenfox.value, genfox.enabled) {
        if (pendingGenfox.value == null) return@LaunchedEffect
        if (!genfox.enabled) return@LaunchedEffect
        nav.navigate(Tabs.GENFOX) { launchSingleTop = true }
        pendingGenfox.value = null
    }
    // Le geste d'assistance amène sur l'onglet ; l'écran, lui, ouvre la
    // conversation neuve et le micro. Le drapeau n'est PAS consommé ici : c'est
    // l'écran qui le fait, une fois qu'il a agi dessus.
    LaunchedEffect(pendingAssist.value, genfox.enabled) {
        if (!pendingAssist.value || !genfox.enabled) return@LaunchedEffect
        nav.navigate(Tabs.GENFOX) { launchSingleTop = true }
    }
    // Un tel: n'a nulle part où aller tant que le poste n'est pas configuré :
    // on garde le numéro plutôt que de l'effacer, l'onglet peut apparaître au
    // retour du /config.
    LaunchedEffect(pendingDial.value, phone.enabled) {
        val number = pendingDial.value ?: return@LaunchedEffect
        if (!phone.enabled) return@LaunchedEffect
        nav.navigate(Tabs.PHONE) { launchSingleTop = true }
        // « Composer », sans numéro : l'onglet suffit, il n'y a rien à consommer
        // côté écran — et laisser le drapeau levé retiendrait le suivant.
        if (number.isBlank()) pendingDial.value = null
    }
    // « Envoyer vers » : l'aiguillage attend de savoir quelles moitiés sont
    // connectées. Sans les deux, l'écran de choix n'a rien à demander et on va
    // droit au composeur ; sans aucune, on GARDE le partage — l'usager peut
    // se connecter et le retrouver, plutôt que de le perdre en silence.
    LaunchedEffect(pendingShare.value, tabs, tokens) {
        pendingShare.value ?: return@LaunchedEffect
        val canSms = tokens.containsKey(Service.SMS)
        val canMail = tokens.containsKey(Service.MAIL)
        when {
            canSms && canMail -> nav.navigate(Tabs.SHARE)
            canSms -> nav.navigate(Tabs.SMS_COMPOSE)
            canMail -> openMailCompose(nav)
            else -> return@LaunchedEffect
        }
        pendingShare.value = null
    }
    LaunchedEffect(pendingMailThread.value, tabs) {
        val key = pendingMailThread.value ?: return@LaunchedEffect
        if (Service.MAIL !in tabs) return@LaunchedEffect
        if (key.isBlank()) {
            nav.navigate(Tabs.MAIL) { launchSingleTop = true }
        } else {
            nav.navigate("${Tabs.MAIL_THREAD}/${URLEncoder.encode(key, "UTF-8")}")
        }
        pendingMailThread.value = null
    }

    val startTab = if (Service.SMS in tabs) Tabs.SMS else Tabs.MAIL

    Column(Modifier.fillMaxSize()) {
        // Au-dessus de tout, sur tous les onglets : une panne ne se range pas
        // dans une section. Sur un écran de détail aussi — c'est justement en
        // lisant autre chose qu'on veut l'apprendre.
        if (hosting.enabled && hosting.hasAny) HostingBanner(hosting)
        // Le poste peut très bien recevoir le push et ne pas sonner pour
        // autant. Montrée seulement quand le compte A un poste : prévenir
        // quelqu'un qui ne reçoit pas d'appels de toute façon serait du bruit.
        // Un seul manque à la fois, le plus grave d'abord.
        if (phone.enabled && phone.extension.isNotBlank()) {
            callGaps.firstOrNull()?.let { CallGapBanner(it) }
        }
        NavHost(
            navController = nav,
            startDestination = startTab,
            modifier = Modifier.weight(1f),
        ) {
            composable(Tabs.SMS) {
                ThreadsScreen(
                    onOpenThread = { id -> nav.navigate("${Tabs.CONVERSATION}/$id") },
                    onCompose = { nav.navigate(Tabs.SMS_COMPOSE) },
                    onArchived = { nav.navigate(Tabs.ARCHIVED) },
                    onSettings = { rootNav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Tabs.SMS_COMPOSE) {
                // Pris à l'entrée sur l'écran, une fois : `remember` est lié à
                // cette entrée de navigation, donc revenir plus tard sur un
                // nouveau message n'y trouve plus rien.
                val shared = remember { ShareIntake.take() }
                ComposeScreen(
                    shared = shared,
                    onBack = { nav.popBackStack() },
                    onSent = { id ->
                        nav.navigate("${Tabs.CONVERSATION}/$id") {
                            popUpTo(Tabs.SMS_COMPOSE) { inclusive = true }
                        }
                    },
                )
            }
            composable(Tabs.SHARE) {
                val shared = ShareIntake.peek()
                if (shared == null) {
                    // Le partage a déjà été pris (retour arrière, rotation) :
                    // rien à choisir, on ne laisse pas un écran vide.
                    LaunchedEffect(Unit) { nav.popBackStack() }
                    return@composable
                }
                ShareScreen(
                    shared = shared,
                    canSms = tokens.containsKey(Service.SMS),
                    canMail = tokens.containsKey(Service.MAIL),
                    onSms = {
                        nav.navigate(Tabs.SMS_COMPOSE) {
                            popUpTo(Tabs.SHARE) { inclusive = true }
                        }
                    },
                    onMail = {
                        openMailCompose(nav, clearing = Tabs.SHARE)
                    },
                    // ⚠️ Renoncer VIDE la réserve. Sans ça, le partage
                    // abandonné referait surface dans le prochain message neuf
                    // composé à la main, ce qui se lit comme un bug.
                    onBack = {
                        ShareIntake.clear()
                        nav.popBackStack()
                    },
                )
            }
            composable(Tabs.GENFOX) {
                GenfoxScreen(
                    assist = pendingAssist.value,
                    onAssistConsumed = { pendingAssist.value = false },
                )
            }
            composable(Tabs.AGENDA) {
                AgendaScreen(
                    onOpenTasks = {
                        nav.navigate(Tabs.TASKS) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Tabs.TASKS) { TachesScreen() }
            composable(Tabs.PHONE) {
                PhoneScreen(
                    prefill = pendingDial.value?.takeIf { it.isNotBlank() },
                    onPrefillConsumed = { pendingDial.value = null },
                )
            }
            composable(Tabs.ARCHIVED) {
                ArchivedScreen(
                    onBack = { nav.popBackStack() },
                    onOpenThread = { id -> nav.navigate("${Tabs.CONVERSATION}/$id") },
                )
            }
            composable(
                route = "${Tabs.CONVERSATION}/{threadId}",
                arguments = listOf(navArgument("threadId") { type = NavType.IntType }),
            ) { entry ->
                ConversationScreen(
                    threadId = entry.arguments?.getInt("threadId") ?: 0,
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Tabs.MAIL) {
                if (!tokens.containsKey(Service.MAIL)) {
                    ConnectServicePane(
                        service = Service.MAIL,
                        pendingAuthUri = pendingAuthUri,
                    )
                    return@composable
                }
                // Hoisted to the tab entry so config (snooze presets, spawn
                // kinds) survives navigating into a thread and back.
                val vm: MailListViewModel = viewModel(viewModelStoreOwner = it)
                MailListScreen(
                    onOpenThread = { key ->
                        nav.navigate("${Tabs.MAIL_THREAD}/${URLEncoder.encode(key, "UTF-8")}")
                    },
                    onCompose = { nav.navigate("${Tabs.MAIL_COMPOSE}/new/0") },
                    // Reprendre un brouillon rouvre le composeur DANS SON MODE :
                    // une réponse gardée doit repartir comme une réponse, pas
                    // comme un message neuf qui perdrait le fil d'origine.
                    onOpenDraft = { draft ->
                        nav.navigate(
                            "${Tabs.MAIL_COMPOSE}/${draft.mode}/${draft.emailId}" +
                                "?draft=${URLEncoder.encode(draft.id, "UTF-8")}",
                        )
                    },
                    onSettings = { rootNav.navigate(Routes.SETTINGS) },
                    vm = vm,
                )
            }
            composable(
                route = "${Tabs.MAIL_THREAD}/{threadKey}",
                arguments = listOf(navArgument("threadKey") { type = NavType.StringType }),
            ) { entry ->
                val encoded = entry.arguments?.getString("threadKey").orEmpty()
                val key = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
                val listEntry = remember(entry) { nav.getBackStackEntry(Tabs.MAIL) }
                val listVm: MailListViewModel = viewModel(viewModelStoreOwner = listEntry)
                MailThreadScreen(
                    threadKey = key,
                    config = listVm.config,
                    onBack = { nav.popBackStack() },
                    onReply = { emailId, mode ->
                        nav.navigate("${Tabs.MAIL_COMPOSE}/$mode/$emailId")
                    },
                )
            }
            composable(
                route = "${Tabs.MAIL_COMPOSE}/{mode}/{emailId}?draft={draft}",
                arguments = listOf(
                    navArgument("mode") { type = NavType.StringType },
                    navArgument("emailId") { type = NavType.IntType },
                    navArgument("draft") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                // Le mot du brouillon gardé revient à la liste, seul écran
                // encore là pour le dire — d'où le détour par son ViewModel.
                val listEntry = remember(entry) { nav.getBackStackEntry(Tabs.MAIL) }
                val listVm: MailListViewModel = viewModel(viewModelStoreOwner = listEntry)
                val encodedDraft = entry.arguments?.getString("draft").orEmpty()
                val mode = entry.arguments?.getString("mode") ?: "new"
                // Une réponse ou un brouillon repris n'a rien à voir avec un
                // partage : seul un message NEUF peut en adopter un.
                val shared = remember(entry) {
                    if (mode == "new" && encodedDraft.isBlank()) ShareIntake.take() else null
                }
                MailComposeScreen(
                    mode = mode,
                    emailId = entry.arguments?.getInt("emailId") ?: 0,
                    shared = shared,
                    draftId = runCatching { URLDecoder.decode(encodedDraft, "UTF-8") }
                        .getOrDefault(encodedDraft),
                    onBack = { saved ->
                        nav.popBackStack()
                        if (saved) listVm.announceDraftSaved()
                    },
                    onSent = { nav.popBackStack() },
                )
            }
        }

        // Shown on every root screen whenever the server offers both halves —
        // including before the second is connected, so the way across is
        // always visible rather than something you have to already know about.
        val bottomTabs = buildList {
            tabs.forEach { service ->
                add(
                    if (service == Service.MAIL) {
                        Triple(Tabs.MAIL, service.label, Icons.Filled.MailOutline)
                    } else {
                        Triple(Tabs.SMS, service.label, Icons.AutoMirrored.Filled.Chat)
                    },
                )
            }
            if (phone.enabled && tokens.isNotEmpty()) {
                add(Triple(Tabs.PHONE, "Téléphone", Icons.Filled.Dialpad))
            }
            if (genfox.enabled && tokens.isNotEmpty()) {
                add(Triple(Tabs.GENFOX, "Gen", Icons.Filled.AutoAwesome))
            }
            if (agenda.enabled && tokens.isNotEmpty()) {
                add(Triple(Tabs.AGENDA, "Agenda", Icons.Filled.CalendarMonth))
                add(Triple(Tabs.TASKS, "Tâches", Icons.Filled.Checklist))
            }
        }
        if (bottomTabs.size > 1 && onRoot) {
            // ⚠️ Au-delà de quatre onglets, Material3 serre les libellés et
            // rétrécit les pictogrammes jusqu'à les rendre illisibles. Passé ce
            // seuil on retire les libellés et on agrandit le picto : la barre
            // porte alors six cibles franches plutôt que six timbres-poste.
            // Le libellé reste en description, donc l'accessibilité n'y perd
            // rien.
            val serree = bottomTabs.size > 4
            NavigationBar {
                bottomTabs.forEach { (tabRoute, label, icon) ->
                    NavigationBarItem(
                        selected = route == tabRoute,
                        onClick = {
                            nav.navigate(tabRoute) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                icon,
                                contentDescription = label,
                                modifier = if (serree) Modifier.size(28.dp) else Modifier,
                            )
                        },
                        label = if (serree) null else ({ Text(label) }),
                        alwaysShowLabel = !serree,
                    )
                }
            }
        }
    }
}

/**
 * Ce qui ne va pas dans le parc, en une ligne.
 *
 * Rouge pour une panne, ambre pour le reste : un disque plein et un service à
 * terre ne demandent pas le même geste, et tout peindre en rouge finit par
 * rendre le rouge illisible. Sans bouton : agir sur l'hébergement depuis un
 * téléphone est le meilleur moyen de relancer une pile au mauvais moment, et
 * le bureau est à deux clics quand il faut vraiment intervenir.
 */
@Composable
private fun HostingBanner(alerts: com.bluefoxconsultant.sms.data.HostingAlerts) {
    var open by remember { mutableStateOf(false) }
    Surface(color = if (alerts.isDown) AlertRed else AlertAmber) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                if (alerts.isDown) "Hébergement — ${alerts.summary}"
                else "Hébergement : ${alerts.summary}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // Repliée par défaut : la ligne suffit à savoir qu'il faut regarder,
            // et une liste de vingt services en haut de chaque écran ne serait
            // plus une bannière.
            if (open) {
                alerts.alerts.forEach { alert ->
                    Text(
                        "${alert.service} — ${alert.detail}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * Ce qui empêche le combiné de sonner, et le réglage qui le débloque.
 *
 * Rouge pour l'intention plein écran : sans elle l'écran d'appel ne monte
 * jamais, donc les appels sont manqués aujourd'hui. Ambre pour l'hibernation :
 * tout marche, mais Android peut l'éteindre après des mois sans usage. Tout
 * peindre en rouge finit par rendre le rouge illisible.
 *
 * ⚠️ La bannière NOMME le réglage. Les deux manques vivent dans deux écrans
 * différents, et régler le premier fait apparaître le second : sans le nom, ça
 * se lit « la bannière n'est pas partie ». Et elle se laisse taire, parce que
 * le système répond parfois faux à une question déjà réglée.
 */
@Composable
private fun CallGapBanner(gap: CallGap) {
    val context = LocalContext.current
    val down = gap == CallGap.FULL_SCREEN
    var deplie by remember(gap) { mutableStateOf(false) }
    var tue by remember(gap) { mutableStateOf(false) }
    if (tue) return

    Surface(color = if (down) AlertRed else AlertAmber) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { deplie = !deplie }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                if (down) "Les appels ne sonneront pas"
                else "Les appels peuvent cesser de sonner",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (down) {
                    "Android n'autorise pas l'écran d'appel par-dessus le " +
                        "verrouillage."
                } else {
                    "Android met l'app en pause si elle reste inutilisée, et " +
                        "plus rien ne lui parvient."
                },
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (deplie) {
                Text(
                    cheminReglage(gap),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            // ⚠️ L'intention peut ne résoudre AUCUNE activité.
                            // On le dit plutôt que d'avaler l'échec : toucher
                            // sans que rien ne se passe faisait croire que le
                            // réglage avait été posé.
                            val intent = settingsIntentFor(context, gap)
                            if (intent == null) {
                                deplie = true
                            } else {
                                runCatching { context.startActivity(intent) }
                            }
                        },
                    ) { Text("Ouvrir le réglage", color = Color.White, fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            tairePourToujours(context, gap)
                            tue = true
                        },
                    ) { Text("C'est déjà réglé", color = Color.White, fontSize = 12.sp) }
                }
            }
        }
    }
}

private val AlertRed = Color(0xFFD32F2F)
private val AlertAmber = Color(0xFFF57C00)

/**
 * Offered when the server has a module the app holds no token for — after an
 * upgrade, typically. Connecting runs a single auth leg and leaves the other
 * tab's session untouched.
 */
@Composable
private fun ConnectServicePane(
    service: Service,
    pendingAuthUri: MutableState<String?>,
    vm: com.bluefoxconsultant.sms.ui.login.AuthViewModel = viewModel(),
) {
    val context = LocalContext.current

    LaunchedEffect(pendingAuthUri.value) {
        val uri = pendingAuthUri.value ?: return@LaunchedEffect
        vm.handleRedirect(context, uri)
        pendingAuthUri.value = null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.MailOutline,
            contentDescription = null,
            tint = BrandAccent,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("${service.label} est disponible sur ce serveur",
             fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Une seule étape : la page de connexion Odoo s'ouvre, puis revient. "
            + "Votre session Messages n'est pas touchée.",
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { vm.connectService(context, service) }, enabled = !vm.loading) {
            Text(if (vm.loading) "Connexion…" else "Connecter ${service.label}")
        }
        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
    }
}

/**
 * Ouvre le composeur de courriel, **en passant par l'onglet**.
 *
 * 🔴 Le détour n'est pas cosmétique. L'écran du composeur va chercher le
 * ViewModel de la LISTE avec `nav.getBackStackEntry(Tabs.MAIL)` — c'est par là
 * qu'il annonce « brouillon enregistré » en repartant. Y aller directement
 * depuis le partage, alors que l'onglet de départ est celui des messages,
 * lèverait une IllegalArgumentException : la destination n'est pas sur la pile.
 * L'app se ferme, sur un partage, donc au pire moment possible.
 *
 * Le passage par l'onglet donne aussi le retour qu'on attend : quitter le
 * composeur ramène à la boîte de réception, pas à l'app d'où venait la photo.
 */
private fun openMailCompose(nav: NavHostController, clearing: String? = null) {
    nav.navigate(Tabs.MAIL) {
        launchSingleTop = true
        clearing?.let { popUpTo(it) { inclusive = true } }
    }
    nav.navigate("${Tabs.MAIL_COMPOSE}/new/0")
}

private object Routes {
    const val INSTANCE = "instance"
    const val LOGIN = "login"
    const val HOME = "home"
    const val SETTINGS = "settings"
}

private object Tabs {
    const val SMS = "sms"
    const val SMS_COMPOSE = "sms_compose"
    const val ARCHIVED = "archived"
    const val CONVERSATION = "conversation"
    const val MAIL = "mail"
    const val MAIL_THREAD = "mail_thread"
    const val MAIL_COMPOSE = "mail_compose"
    const val GENFOX = "genfox"
    const val PHONE = "phone"
    const val AGENDA = "agenda"
    const val TASKS = "taches"
    const val SHARE = "share"
}
