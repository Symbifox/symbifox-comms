package com.bluefoxconsultant.sms.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
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
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.push.Notifier
import com.bluefoxconsultant.sms.ui.compose.ComposeScreen
import com.bluefoxconsultant.sms.ui.conversation.ConversationScreen
import com.bluefoxconsultant.sms.ui.instance.InstanceScreen
import com.bluefoxconsultant.sms.ui.login.LoginScreen
import com.bluefoxconsultant.sms.ui.mail.MailComposeScreen
import com.bluefoxconsultant.sms.ui.mail.MailListScreen
import com.bluefoxconsultant.sms.ui.mail.MailListViewModel
import com.bluefoxconsultant.sms.ui.mail.MailThreadScreen
import com.bluefoxconsultant.sms.ui.settings.SettingsScreen
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
                AppRoot(pendingThread, pendingMailThread, pendingAuthUri)
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
        val data = intent.data
        if (data != null && data.scheme == AUTH_SCHEME && data.host == AUTH_HOST) {
            pendingAuthUri.value = data.toString()
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
        }
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
    pendingAuthUri: MutableState<String?>,
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
                pendingMailThread = pendingMailThread,
                pendingAuthUri = pendingAuthUri,
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
    pendingAuthUri: MutableState<String?>,
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

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val onRoot = route == Tabs.SMS || route == Tabs.MAIL

    // A push for one tab switches to it before opening the detail screen.
    LaunchedEffect(pendingThread.value, tabs) {
        val id = pendingThread.value ?: return@LaunchedEffect
        if (Service.SMS !in tabs) return@LaunchedEffect
        nav.navigate("${Tabs.CONVERSATION}/$id")
        pendingThread.value = null
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
                ComposeScreen(
                    onBack = { nav.popBackStack() },
                    onSent = { id ->
                        nav.navigate("${Tabs.CONVERSATION}/$id") {
                            popUpTo(Tabs.SMS_COMPOSE) { inclusive = true }
                        }
                    },
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
                route = "${Tabs.MAIL_COMPOSE}/{mode}/{emailId}",
                arguments = listOf(
                    navArgument("mode") { type = NavType.StringType },
                    navArgument("emailId") { type = NavType.IntType },
                ),
            ) { entry ->
                MailComposeScreen(
                    mode = entry.arguments?.getString("mode") ?: "new",
                    emailId = entry.arguments?.getInt("emailId") ?: 0,
                    onBack = { nav.popBackStack() },
                    onSent = { nav.popBackStack() },
                )
            }
        }

        // Shown on every root screen whenever the server offers both halves —
        // including before the second is connected, so the way across is
        // always visible rather than something you have to already know about.
        if (tabs.size > 1 && onRoot) {
            NavigationBar {
                tabs.forEach { service ->
                    val tabRoute = if (service == Service.MAIL) Tabs.MAIL else Tabs.SMS
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
                                if (service == Service.MAIL) Icons.Filled.MailOutline
                                else Icons.AutoMirrored.Filled.Chat,
                                contentDescription = service.label,
                            )
                        },
                        label = { Text(service.label) },
                    )
                }
            }
        }
    }
}

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
}
