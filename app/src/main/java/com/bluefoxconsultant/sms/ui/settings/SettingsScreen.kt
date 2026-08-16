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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import com.bluefoxconsultant.sms.data.QuickAction
import com.bluefoxconsultant.sms.data.SwipeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.ThemeMode
import com.bluefoxconsultant.sms.ui.mail.AttachmentOpener
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

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
                title = { Text("Paramètres", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
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
            SectionTitle("Apparence")
            ThemeOption("Système", themeMode == ThemeMode.SYSTEM) { vm.setTheme(ThemeMode.SYSTEM) }
            ThemeOption("Clair", themeMode == ThemeMode.LIGHT) { vm.setTheme(ThemeMode.LIGHT) }
            ThemeOption("Sombre", themeMode == ThemeMode.DARK) { vm.setTheme(ThemeMode.DARK) }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            )

            SectionTitle("Compte")
            InfoRow("Serveur", vm.serverUrl)
            if (vm.userName.isNotBlank()) InfoRow("Utilisateur", vm.userName)

            Spacer(Modifier.height(20.dp))
            SwipeSettings()
            QuickActionSettings()
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
                Text("Déconnexion", fontSize = 16.sp)
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
 * Which gesture does what, per direction and per half.
 *
 * Kept as a plain list of choices rather than a picker dialog: there are four
 * settings and four options, and a phone screen has room for them.
 */
@Composable
private fun SwipeSettings() {
    val prefs = Graph.uiPrefs
    val config by prefs.configFlow.collectAsState()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Gestes de balayage",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        SwipeChoiceRow(
            "Courriel — vers la droite", config.mailStart, SwipeAction.forMail,
        ) { prefs.save(config.copy(mailStart = it)) }
        SwipeChoiceRow(
            "Courriel — vers la gauche", config.mailEnd, SwipeAction.forMail,
        ) { prefs.save(config.copy(mailEnd = it)) }
        SwipeChoiceRow(
            "Messages — vers la droite", config.smsStart, SwipeAction.forSms,
        ) { prefs.save(config.copy(smsStart = it)) }
        SwipeChoiceRow(
            "Messages — vers la gauche", config.smsEnd, SwipeAction.forSms,
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
            "Boutons rapides (courriel)",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            "Jusqu'à ${QuickAction.MAX_IN_BAR} actions hors du menu ⋯.",
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
                    label = { Text(action.label, fontSize = 12.sp) },
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
                    label = { Text(option.label, fontSize = 12.sp) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}
