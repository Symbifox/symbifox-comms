package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FR_C = Locale.forLanguageTag("fr-CA")

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
    var heure by remember { mutableIntStateOf(9) }
    var duree by remember { mutableIntStateOf(60) }
    var calendrier by remember { mutableStateOf(calendriers.firstOrNull()?.id) }

    ModalBottomSheet(onDismissRequest = onFermer, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Nouvelle rencontre", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = titre,
                onValueChange = { titre = it },
                label = { Text("Titre") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Jour", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { date = date.minusDays(1) }) { Text("−1 j") }
                Text(
                    DateTimeFormatter.ofPattern("EEEE d MMMM", FR_C).format(date)
                        .replaceFirstChar { it.uppercase() },
                    Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { date = date.plusDays(1) }) { Text("+1 j") }
            }

            Text("Début", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(8, 9, 10, 11, 13, 14, 15, 16, 17, 19).forEach { h ->
                    FilterChip(
                        selected = heure == h,
                        onClick = { heure = h },
                        label = { Text("%02d:00".format(h)) },
                    )
                }
            }

            Text("Durée", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60, 90, 120).forEach { m ->
                    FilterChip(
                        selected = duree == m,
                        onClick = { duree = m },
                        label = { Text(if (m < 60) "$m min" else "${m / 60} h") },
                    )
                }
            }

            if (calendriers.isNotEmpty()) {
                Text("Calendrier", style = MaterialTheme.typography.labelLarge)
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
                label = { Text("Lieu (facultatif)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = visio,
                onValueChange = { visio = it },
                label = { Text("Lien de visioconférence (facultatif)") },
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
                        val debut = date.atTime(LocalTime.of(heure, 0))
                            .atZone(zone).toInstant()
                        onCreer(
                            titre.trim(),
                            debut,
                            debut.plusSeconds(duree * 60L),
                            lieu.trim(),
                            visio.trim(),
                            calendrier,
                        )
                    },
                ) { Text("Créer") }
                TextButton(onClick = onFermer) { Text("Annuler") }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}
