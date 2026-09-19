@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import com.bluefoxconsultant.sms.data.DELAIS_ANNULATION
import com.bluefoxconsultant.sms.data.QuickAction
import androidx.compose.foundation.layout.Arrangement
import com.bluefoxconsultant.sms.data.SwipeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.ThemeMode
import com.bluefoxconsultant.sms.push.PushRegistrar
import com.bluefoxconsultant.sms.ui.DialogueDistributeur
import com.bluefoxconsultant.sms.ui.libelleDistributeur
import com.bluefoxconsultant.sms.ui.mail.AttachmentOpener
import com.bluefoxconsultant.sms.ui.theme.BrandAccent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val themeMode by Graph.tokenStore.themeModeFlow.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandAccent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_appearance))
            ThemeOption(stringResource(ThemeMode.SYSTEM.libelleRes), themeMode == ThemeMode.SYSTEM) { vm.setTheme(ThemeMode.SYSTEM) }
            ThemeOption(stringResource(ThemeMode.LIGHT.libelleRes), themeMode == ThemeMode.LIGHT) { vm.setTheme(ThemeMode.LIGHT) }
            ThemeOption(stringResource(ThemeMode.DARK.libelleRes), themeMode == ThemeMode.DARK) { vm.setTheme(ThemeMode.DARK) }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            )

            SectionTitle(stringResource(R.string.settings_account))
            InfoRow(stringResource(R.string.settings_server), vm.serverUrl)
            if (vm.userName.isNotBlank()) InfoRow(stringResource(R.string.settings_user), vm.userName)

            PushSettings()

            Spacer(Modifier.height(20.dp))
            SwipeSettings()
            UndoSendSettings()
            QuickActionSettings()
            HostingSettings()
            Button(
                onClick = {
                    // Cached attachments are business documents; they must not
                    // outlive the session that fetched them.
                    AttachmentOpener.clearCache(context)
                    vm.logout()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(50.dp),
            ) {
                Text(stringResource(R.string.settings_sign_out), fontSize = 16.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = BrandAccent,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.height(0.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}


/**
 * L'app qui livre les notifications, et de quoi en changer.
 *
 * Le choix se pose à la connexion quand plusieurs distributeurs sont installés
 * sans défaut ; ici il se reprend quand on veut — après avoir installé un
 * second distributeur, ou fermé le dialogue sans choisir.
 */
@Composable
private fun PushSettings() {
    val context = LocalContext.current
    var courant by remember { mutableStateOf(PushRegistrar.distributeurCourant(context)) }
    var installes by remember { mutableStateOf(PushRegistrar.distributeursInstalles(context)) }
    var ouvert by remember { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.settings_notifications))
    InfoRow(
        stringResource(R.string.settings_distributor),
        courant?.let { libelleDistributeur(context, it) }
            ?: if (installes.isEmpty()) stringResource(R.string.settings_distributor_none_installed)
            else stringResource(R.string.settings_distributor_none_chosen),
    )
    if (installes.isEmpty()) {
        Text(
            stringResource(R.string.settings_distributor_install_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    } else {
        TextButton(
            onClick = {
                // Relu à l'ouverture : un distributeur a pu être installé ou
                // retiré depuis que l'écran est là.
                installes = PushRegistrar.distributeursInstalles(context)
                ouvert = true
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                if (courant == null) stringResource(R.string.settings_distributor_choose)
                else stringResource(R.string.settings_distributor_change),
            )
        }
    }
    if (ouvert && installes.isNotEmpty()) {
        DialogueDistributeur(
            candidats = installes,
            courant = courant,
            onChoisir = {
                PushRegistrar.utiliser(context, it)
                courant = it
                ouvert = false
            },
            onFermer = { ouvert = false },
        )
    }
}

/**
 * Which gesture does what, per direction and per half.
 *
 * Kept as a plain list of choices rather than a picker dialog: there are four
 * settings and four options, and a phone screen has room for them.
 */
/**
 * Ce que la bannière d'hébergement compte.
 *
 * N'apparaît que si le serveur sert l'hébergement à ce compte : un réglage
 * pour une capacité qu'on n'a pas est du bruit dans un écran de préférences.
 */
@Composable
private fun HostingSettings() {
    val alerts by Graph.hostingStore.state.collectAsState()
    if (!alerts.enabled) return
    val prefs = Graph.uiPrefs
    val withMaintenance by prefs.hostingMaintenanceFlow.collectAsState()
    val context = LocalContext.current

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SectionTitle(stringResource(R.string.settings_hosting))
        Text(
            stringResource(R.string.settings_hosting_explainer),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_hosting_count_maintenance),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = withMaintenance,
                onCheckedChange = {
                    prefs.setHostingMaintenance(it)
                    // Relire tout de suite : sinon le changement ne se voit
                    // qu'au prochain cycle, et le réglage a l'air inopérant.
                    Graph.hostingStore.kick(context)
                },
            )
        }
    }
}

/**
 * Le délai pendant lequel un envoi de courriel peut encore être annulé
 * (#25764). « Aucun » garde l'envoi au toucher, comme avant.
 */
@Composable
private fun UndoSendSettings() {
    val prefs = Graph.uiPrefs
    val delai by prefs.delaiAnnulationFlow.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_undo_send),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        // Défile plutôt que déborder : cinq puces ne tiennent pas à 360 dp ni
        // en grande police.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            DELAIS_ANNULATION.forEach { secondes ->
                FilterChip(
                    selected = delai == secondes,
                    onClick = { prefs.setDelaiAnnulation(secondes) },
                    label = {
                        Text(
                            if (secondes == 0) stringResource(R.string.settings_undo_send_off)
                            else stringResource(R.string.settings_undo_send_seconds, secondes),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SwipeSettings() {
    val prefs = Graph.uiPrefs
    val config by prefs.configFlow.collectAsState()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_swipe_gestures),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        SwipeChoiceRow(
            stringResource(R.string.settings_swipe_mail_right), config.mailStart, SwipeAction.forMail,
        ) { prefs.save(config.copy(mailStart = it)) }
        SwipeChoiceRow(
            stringResource(R.string.settings_swipe_mail_left), config.mailEnd, SwipeAction.forMail,
        ) { prefs.save(config.copy(mailEnd = it)) }
        SwipeChoiceRow(
            stringResource(R.string.settings_swipe_sms_right), config.smsStart, SwipeAction.forSms,
        ) { prefs.save(config.copy(smsStart = it)) }
        SwipeChoiceRow(
            stringResource(R.string.settings_swipe_sms_left), config.smsEnd, SwipeAction.forSms,
        ) { prefs.save(config.copy(smsEnd = it)) }
    }
}

/**
 * Which actions get a permanent button in a conversation's top bar.
 *
 * Capped at two by [QuickAction.MAX_IN_BAR] — beyond that the subject line has
 * nowhere to go. Picking a third drops the oldest rather than refusing the tap,
 * because everything stays reachable under ⋯ either way.
 */
@Composable
private fun QuickActionSettings() {
    val prefs = Graph.uiPrefs
    val quick by prefs.quickActionsFlow.collectAsState()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_quick_buttons),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            pluralStringResource(
                R.plurals.settings_quick_buttons_hint,
                QuickAction.MAX_IN_BAR,
                QuickAction.MAX_IN_BAR,
            ),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            QuickAction.entries.forEach { action ->
                val selected = action in quick
                FilterChip(
                    selected = selected,
                    onClick = { prefs.setQuickActions(QuickAction.toggle(quick, action)) },
                    label = { Text(stringResource(action.labelRes), fontSize = 12.sp) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SwipeChoiceRow(
    label: String,
    current: SwipeAction,
    options: List<SwipeAction>,
    onPick: (SwipeAction) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            options.forEach { option ->
                FilterChip(
                    selected = option == current,
                    onClick = { onPick(option) },
                    label = { Text(stringResource(option.labelRes), fontSize = 12.sp) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}
