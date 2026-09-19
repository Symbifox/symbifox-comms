package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bluefoxconsultant.sms.data.AgendaAlarm
import com.bluefoxconsultant.sms.data.AgendaEvent
import com.bluefoxconsultant.sms.data.AgendaPartner
import com.bluefoxconsultant.sms.data.OdooLinks
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.asString

/**
 * Ce qu'une rencontre porte, et les gestes qu'on peut poser dessus.
 *
 * ⚠️ « Confirmer » écrit l'état de présence et pose une note interne côté
 * serveur. Il ne passe PAS par `do_accept` d'Odoo, qui publie sous le
 * sous-type « Invitation » : sur un statutaire suivi par un client, confirmer
 * depuis un téléphone lui aurait écrit.
 *
 * ⚠️ Ajouter un participant n'envoie PAS d'invitation, sauf si la personne
 * coche la case. Odoo l'enverrait d'office ; le serveur la retient à moins
 * qu'on le lui demande, et c'est ici que la demande se fait, en toutes lettres.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FicheRencontre(
    event: AgendaEvent,
    zone: ZoneId,
    snoozeMinutes: List<Int>,
    rsvpOffert: Boolean,
    busy: Boolean,
    api: Int,
    partenaires: List<AgendaPartner>,
    onChercherPartenaires: (String) -> Unit,
    onAjouterParticipant: (Int, Boolean) -> Unit,
    onRetirerParticipant: (Int) -> Unit,
    onSnooze: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRsvp: (String) -> Unit,
    onSkipAgenda: (Boolean) -> Unit,
    onSkipDashboard: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var ajout by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (marques(event).confirmee) {
                    PastilleConfirmee(
                        encre = MaterialTheme.colorScheme.primary,
                        fond = MaterialTheme.colorScheme.onPrimary,
                        taille = 18.dp,
                    )
                }
                Text(event.name, style = MaterialTheme.typography.titleLarge)
            }
            Text(quand(event, zone), style = MaterialTheme.typography.bodyMedium)

            if (event.calendar.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val teinte = hexOuNull(event.color)
                    if (teinte != null) {
                        Surface(color = teinte,
                            modifier = Modifier.size(10.dp).clip(CircleShape)) {}
                    }
                    Text(event.calendar, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (event.location.isNotBlank()) {
                Text(event.location, style = MaterialTheme.typography.bodySmall)
            }
            if (event.videocall.isNotBlank()) {
                TextButton(
                    onClick = { ouvrir(context, event.videocall) },
                    contentPadding = PaddingValues(0.dp),
                ) { Text(stringResource(R.string.meeting_join_video)) }
            }

            HorizontalDivider()

            SectionRappel(
                event = event,
                zone = zone,
                api = api,
                snoozeMinutes = snoozeMinutes,
                busy = busy,
                onSnooze = onSnooze,
                onDismiss = onDismiss,
            )

            if (rsvpOffert && event.attendees > 1) {
                HorizontalDivider()
                Text(stringResource(R.string.meeting_attendance), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { onRsvp("accepted") }) {
                        Text(stringResource(R.string.meeting_rsvp_accept))
                    }
                    OutlinedButton(enabled = !busy, onClick = { onRsvp("tentative") }) {
                        Text(stringResource(R.string.meeting_rsvp_tentative))
                    }
                    OutlinedButton(enabled = !busy, onClick = { onRsvp("declined") }) {
                        Text(stringResource(R.string.meeting_rsvp_decline))
                    }
                }
                if (event.myState.isNotBlank()) {
                    Text(
                        stringResource(R.string.meeting_rsvp_current, etat(event.myState)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()
            // 🔴 L'ABSENCE se dit, elle aussi. Une section qui n'apparaît que
            // lorsqu'il y a un OdJ laisse la question sans réponse : on ne
            // sait pas si la rencontre n'en a pas ou si l'écran n'a pas su le
            // lire. C'est exactement ce qu'Olivier a reproché à la v1.
            Text(stringResource(R.string.meeting_agenda), style = MaterialTheme.typography.labelLarge)
            val odj = event.agenda
            if (odj != null) {
                Text(odj.name, style = MaterialTheme.typography.bodyMedium)
                Text(etatDoc(event.agendaState),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary)
                odj.topics.forEach { sujet ->
                    Text("• $sujet", style = MaterialTheme.typography.bodySmall)
                }
            } else if (event.skipAgenda) {
                Text(stringResource(R.string.meeting_agenda_skipped),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.meeting_no_agenda),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text(stringResource(R.string.meeting_minutes), style = MaterialTheme.typography.labelLarge)
            val cr = event.minutes
            if (cr != null) {
                if (cr.summary.isNotBlank()) {
                    Text(cr.summary, style = MaterialTheme.typography.bodySmall)
                }
                if (cr.decisions.isNotEmpty()) {
                    Text(
                        stringResource(R.string.meeting_decisions),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    cr.decisions.forEach { d ->
                        Text("• $d", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Text(stringResource(R.string.meeting_no_minutes),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text(stringResource(R.string.meeting_follow_up), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = event.skipAgenda,
                    enabled = !busy,
                    onClick = { onSkipAgenda(!event.skipAgenda) },
                    label = { Text(stringResource(R.string.meeting_flag_no_formal_agenda)) },
                )
                FilterChip(
                    selected = event.skipDashboard,
                    enabled = !busy,
                    onClick = { onSkipDashboard(!event.skipDashboard) },
                    label = { Text(stringResource(R.string.meeting_flag_off_dashboard)) },
                )
            }
            Text(
                stringResource(R.string.meeting_flags_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (event.attendeeList.isNotEmpty() || event.canEditAttendees) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.meeting_attendees),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (event.canEditAttendees) {
                        TextButton(enabled = !busy, onClick = { ajout = true }) {
                            Icon(
                                Icons.Filled.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.common_add))
                        }
                    }
                }
                event.attendeeList.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (p.state == "accepted") {
                            PastilleConfirmee(
                                encre = MaterialTheme.colorScheme.primary,
                                fond = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Spacer(Modifier.size(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (p.isMe) stringResource(R.string.meeting_attendee_me, p.name) else p.name,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                etat(p.state) + if (p.email.isNotBlank()) " · ${p.email}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // L'organisateur ne se retire pas : sans lui la
                        // rencontre sortirait de « mon agenda ». Le serveur le
                        // refuse aussi ; ne pas offrir le bouton évite qu'un
                        // refus se lise comme une panne.
                        val organisateur = p.partnerId != 0 &&
                            p.partnerId == event.organizerPartnerId
                        if (event.canEditAttendees && p.partnerId != 0 && !organisateur) {
                            IconButton(
                                enabled = !busy,
                                onClick = { onRetirerParticipant(p.partnerId) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.meeting_remove_attendee, p.name),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            TextButton(
                onClick = { OdooLinks.openRecord(context, "calendar", event.id) },
                contentPadding = PaddingValues(0.dp),
            ) { Text(stringResource(R.string.common_open_in_odoo)) }
        }
    }

    if (ajout) {
        DialogueAjoutParticipant(
            partenaires = partenaires,
            dejaLa = event.attendeeList.map { it.partnerId }.filter { it != 0 }.toSet(),
            busy = busy,
            onChercher = onChercherPartenaires,
            onAjouter = { id, inviter ->
                onAjouterParticipant(id, inviter)
                ajout = false
            },
            onFermer = { ajout = false },
        )
    }
}

/**
 * La section « Rappel » : ce qui est configuré, et les gestes SEULEMENT quand
 * ils ont un sens. Voir [etatRappel] pour la règle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionRappel(
    event: AgendaEvent,
    zone: ZoneId,
    api: Int,
    snoozeMinutes: List<Int>,
    busy: Boolean,
    onSnooze: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val etat = remember(event, api) { etatRappel(event, api, Instant.now()) }
    Text(stringResource(R.string.meeting_reminder), style = MaterialTheme.typography.labelLarge)

    event.snoozedUntil?.let {
        Text(
            stringResource(R.string.meeting_reminder_snoozed_until, heureCourte(it, zone)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (event.dismissedAt != null && event.snoozedUntil == null) {
        Text(
            stringResource(R.string.meeting_reminder_dismissed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    /** [avecHeure] porte l'heure et le délai, [sansHeure] le délai seul. */
    @Composable
    fun lignes(alarms: List<AgendaAlarm>, @StringRes avecHeure: Int, @StringRes sansHeure: Int) {
        alarms.forEach { a ->
            val quand = a.notifyAt?.let { heureCourte(it, zone) }
            val delai = libelleDelai(a.minutes).asString()
            Text(
                if (quand != null) stringResource(avecHeure, quand, delai)
                else stringResource(sansHeure, delai),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    @Composable
    fun gestes() {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            snoozeMinutes.forEach { minutes ->
                AssistChip(
                    enabled = !busy,
                    onClick = { onSnooze(minutes) },
                    label = { Text(libelleReport(minutes)) },
                )
            }
            AssistChip(
                enabled = !busy,
                onClick = onDismiss,
                label = { Text(stringResource(R.string.meeting_reminder_seen)) },
            )
        }
    }

    when (etat) {
        EtatRappel.Ancien -> gestes()
        EtatRappel.Aucun -> Text(
            stringResource(R.string.meeting_no_reminder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is EtatRappel.AVenir ->
            lignes(etat.alarms, R.string.meeting_alarm_rings_at, R.string.meeting_alarm_rings)
        is EtatRappel.Sonne -> {
            lignes(etat.alarms, R.string.meeting_alarm_rang_at, R.string.meeting_alarm_rang)
            gestes()
        }
        is EtatRappel.Passe -> Text(
            stringResource(R.string.meeting_ended),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Chercher quelqu'un et l'ajouter.
 *
 * La case « envoyer l'invitation » est DÉCOCHÉE par défaut, et c'est le
 * point : Odoo enverrait d'office, le serveur retient, et c'est la personne
 * qui décide, en voyant la case, si le courriel part. Une invitation partie
 * par réflexe vers un client est le genre d'accident qu'on ne rattrape pas.
 */
@Composable
private fun DialogueAjoutParticipant(
    partenaires: List<AgendaPartner>,
    dejaLa: Set<Int>,
    busy: Boolean,
    onChercher: (String) -> Unit,
    onAjouter: (Int, Boolean) -> Unit,
    onFermer: () -> Unit,
) {
    var terme by remember { mutableStateOf("") }
    var inviter by remember { mutableStateOf(false) }
    // Une frappe, puis un souffle, puis la requête : chercher à chaque lettre
    // ferait dix requêtes pour un nom.
    LaunchedEffect(terme) {
        delay(300)
        onChercher(terme)
    }
    AlertDialog(
        onDismissRequest = onFermer,
        confirmButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.common_close)) }
        },
        title = { Text(stringResource(R.string.meeting_add_attendee)) },
        text = {
            Column(
                Modifier.imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = terme,
                    onValueChange = { terme = it },
                    label = { Text(stringResource(R.string.meeting_attendee_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = inviter, onCheckedChange = { inviter = it })
                    Text(
                        stringResource(R.string.meeting_send_invitation),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (terme.trim().length < 2) {
                    Text(
                        stringResource(R.string.meeting_search_min_chars),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (partenaires.isEmpty()) {
                    Text(
                        stringResource(R.string.meeting_search_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(partenaires, key = { it.id }) { p ->
                        val present = p.id in dejaLa
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                if (p.email.isNotBlank()) {
                                    Text(
                                        p.email,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(
                                enabled = !busy && !present,
                                onClick = { onAjouter(p.id, inviter) },
                            ) {
                                Text(
                                    stringResource(
                                        if (present) R.string.meeting_attendee_already_added
                                        else R.string.common_add,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun ouvrir(context: android.content.Context, url: String) {
    runCatching {
        androidx.browser.customtabs.CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, android.net.Uri.parse(url))
    }
}

/** La date dans la langue du téléphone, l'heure en chiffres : voir `DatesLocales`. */
@Composable
private fun quand(event: AgendaEvent, zone: ZoneId): String {
    val jour = formateurDate("EEEEdMMMM")
    if (event.allday) {
        val date = event.startInstant?.atZone(ZoneId.of("UTC"))?.toLocalDate()
        return stringResource(R.string.meeting_all_day_on, date?.let { jour.format(it) } ?: "")
    }
    val start = event.startAt(zone) ?: return ""
    val stop = event.stopAt(zone)
    val base = capitaliser(jour.format(start), jour.locale) + ", " + HEURE.format(start)
    return if (stop == null) base else "$base – ${HEURE.format(stop)}"
}

/** « 13:45 », ou « mar. 15 sept. 13:45 » si ce n'est pas aujourd'hui. */
@Composable
private fun heureCourte(raw: String, zone: ZoneId): String {
    val jourCourt = formateurDate("EEEdMMM")
    val instant = com.bluefoxconsultant.sms.data.parseInstant(raw) ?: return raw
    val quand = instant.atZone(zone)
    val aujourdhui = Instant.now().atZone(zone).toLocalDate()
    return if (quand.toLocalDate() == aujourdhui) {
        HEURE.format(quand)
    } else {
        jourCourt.format(quand) + " " + HEURE.format(quand)
    }
}

@Composable
private fun libelleReport(minutes: Int): String = when {
    minutes < 60 -> pluralStringResource(R.plurals.agenda_duration_minutes, minutes, minutes)
    minutes == 60 -> pluralStringResource(R.plurals.agenda_duration_hours, 1, 1)
    else -> pluralStringResource(R.plurals.agenda_duration_hours, minutes / 60, minutes / 60)
}

/** L'état de présence en mots ; une valeur inconnue du serveur passe telle quelle. */
@Composable
private fun etat(state: String): String = when (state) {
    "accepted" -> stringResource(R.string.meeting_attendee_state_accepted)
    "declined" -> stringResource(R.string.meeting_attendee_state_declined)
    "tentative" -> stringResource(R.string.meeting_attendee_state_tentative)
    "needsAction" -> stringResource(R.string.meeting_attendee_state_no_answer)
    else -> if (state.isBlank()) stringResource(R.string.meeting_attendee_state_no_answer) else state
}

@Composable
private fun etatDoc(etat: String): String = when (etat) {
    "draft" -> stringResource(R.string.meeting_doc_state_draft)
    "reviewed" -> stringResource(R.string.meeting_doc_state_reviewed)
    "sent" -> stringResource(R.string.meeting_doc_state_sent)
    "skipped" -> stringResource(R.string.meeting_doc_state_skipped)
    else -> stringResource(R.string.meeting_doc_state_missing)
}
