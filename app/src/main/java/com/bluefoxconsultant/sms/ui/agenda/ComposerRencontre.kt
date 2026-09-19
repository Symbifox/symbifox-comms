package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bluefoxconsultant.sms.data.AgendaCalendar
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

/**
 * Poser une rencontre depuis le téléphone.
 *
 * ⚠️ Le calendrier de destination est un CHOIX, pas un défaut silencieux. Une
 * rencontre posée sans calendrier resterait dans Odoo seul : la synchro ne
 * pousse que ce qui porte une configuration, donc elle n'apparaîtrait ni sur
 * le téléphone en CalDAV ni chez les autres. Les pastilles reprennent les
 * couleurs réelles des calendriers, pour qu'on choisisse par la couleur qu'on
 * connaît plutôt que par un nom.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposerRencontre(
    jour: LocalDate,
    zone: ZoneId,
    calendriers: List<AgendaCalendar>,
    busy: Boolean,
    onCreer: (String, java.time.Instant, java.time.Instant, String, String, Int?) -> Unit,
    onFermer: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var titre by remember { mutableStateOf("") }
    var lieu by remember { mutableStateOf("") }
    var visio by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(jour) }
    var heure by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var duree by remember { mutableIntStateOf(60) }
    var choixDate by remember { mutableStateOf(false) }
    var choixHeure by remember { mutableStateOf(false) }
    var calendrier by remember { mutableStateOf(calendriers.firstOrNull()?.id) }
    val jourCourt = formateurDate("EEEdMMM")

    ModalBottomSheet(onDismissRequest = onFermer, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // ⚠️ La feuille vit dans sa propre fenêtre : la colonne de
                // l'accueil ne consomme pas le clavier pour elle. Sans ce
                // rembourrage, le champ du bas passe sous le clavier.
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.agenda_new_meeting), style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = titre,
                onValueChange = { titre = it },
                label = { Text(stringResource(R.string.common_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Les sélecteurs d'Android, pas des chips : « −1 j / +1 j » et dix
            // heures rondes obligeaient à sortir de l'app pour une rencontre
            // à 09:30 dans trois semaines. Voir `Selecteurs.kt`.
            Text(stringResource(R.string.meeting_when), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { choixDate = true }) {
                    Text(capitaliser(jourCourt.format(date), jourCourt.locale))
                }
                OutlinedButton(onClick = { choixHeure = true }) {
                    Text(HEURE.format(heure))
                }
            }

            Text(stringResource(R.string.meeting_duration), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60, 90, 120).forEach { m ->
                    FilterChip(
                        selected = duree == m,
                        onClick = { duree = m },
                        label = {
                            Text(
                                if (m < 60) pluralStringResource(R.plurals.agenda_duration_minutes, m, m)
                                else pluralStringResource(R.plurals.agenda_duration_hours, m / 60, m / 60),
                            )
                        },
                    )
                }
            }

            if (calendriers.isNotEmpty()) {
                Text(stringResource(R.string.meeting_calendar), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    calendriers.forEach { c ->
                        FilterChip(
                            selected = calendrier == c.id,
                            onClick = { calendrier = c.id },
                            leadingIcon = {
                                val teinte = hexOuNull(c.color)
                                if (teinte != null) {
                                    Surface(
                                        color = teinte,
                                        modifier = Modifier.size(12.dp).clip(CircleShape),
                                    ) {}
                                }
                            },
                            label = { Text(c.name) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = lieu,
                onValueChange = { lieu = it },
                label = { Text(stringResource(R.string.meeting_location_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = visio,
                onValueChange = { visio = it },
                label = { Text(stringResource(R.string.meeting_video_link_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = titre.isNotBlank() && !busy,
                    onClick = {
                        // L'heure est saisie dans le fuseau de l'appareil, qui
                        // est celui affiché ; la conversion en UTC se fait ici,
                        // une fois, plutôt que d'envoyer une heure locale que
                        // le serveur devrait deviner.
                        val debut = date.atTime(heure).atZone(zone).toInstant()
                        onCreer(
                            titre.trim(),
                            debut,
                            debut.plusSeconds(duree * 60L),
                            lieu.trim(),
                            visio.trim(),
                            calendrier,
                        )
                    },
                ) { Text(stringResource(R.string.common_create)) }
                TextButton(onClick = onFermer) { Text(stringResource(R.string.common_cancel)) }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }

    if (choixDate) {
        DialogueDate(initiale = date, onChoisir = { date = it }, onFermer = { choixDate = false })
    }
    if (choixHeure) {
        DialogueHeure(initiale = heure, onChoisir = { heure = it }, onFermer = { choixHeure = false })
    }
}
