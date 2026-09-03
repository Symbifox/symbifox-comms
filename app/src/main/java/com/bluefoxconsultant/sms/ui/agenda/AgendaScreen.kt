package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.data.AgendaEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** L'heure à laquelle la grille s'ouvre : la journée de travail, pas minuit. */
private const val OPEN_AT_HOUR = 7

private val FR = Locale.forLanguageTag("fr-CA")

/**
 * L'agenda, en jour ou en semaine, avec ce que Symbifox ajoute à une rencontre.
 *
 * [onOpenTasks] emmène vers l'onglet des échéances : la pastille d'un jour dit
 * combien il y en a, et le geste naturel est d'aller les voir. Les tâches ne
 * sont PAS posées sur la grille — 131 échéances contre 9 rencontres sur deux
 * semaines noieraient les rencontres au lieu de les situer.
 */
@Composable
fun AgendaScreen(onOpenTasks: () -> Unit) {
    val vm: AgendaViewModel = viewModel()
    val scroll = rememberScrollState()
    val hauteurHeure = vm.hourHeight.dp

    LaunchedEffect(Unit) {
        // Ouvrir sur la journée de travail. Sans ça la grille s'ouvre à minuit
        // et il faut faire défiler avant de voir quoi que ce soit.
        scroll.scrollTo((vm.hourHeight * OPEN_AT_HOUR).toInt())
    }

    // ⚠️ Le zoom change la hauteur totale de la grille. Sans ce rattrapage, la
    // position gardée en points ferait sauter l'écran à une autre heure à
    // chaque pincement, et on perdrait ce qu'on regardait.
    var hauteurPrecedente by remember { mutableFloatStateOf(vm.hourHeight) }
    LaunchedEffect(vm.hourHeight) {
        val rapport = vm.hourHeight / hauteurPrecedente
        if (rapport != 1f) scroll.scrollTo((scroll.value * rapport).toInt())
        hauteurPrecedente = vm.hourHeight
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        AgendaHeader(vm)
        FuseauAvertissement(vm.config.userTz, vm.zone)
        vm.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.clearError(); vm.load() }) { Text("Réessayer") }
                }
            }
        }

        val days = vm.days
        JoursEnTete(days, vm.taskCounts, vm.zone, onOpenTasks)
        JourneeEntiere(days, vm.events, vm.zone) { vm.open(it) }

        Box(
            Modifier
                .weight(1f)
                .pointerInput(Unit) { detecterPincement { vm.zoom(it) } },
        ) {
            Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
                ColonneDesHeures(hauteurHeure)
                days.forEach { day ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(hauteurHeure * 24),
                    ) {
                        LignesDHeures(hauteurHeure)
                        PlacerLesRencontres(day, vm.events, vm.zone, hauteurHeure) {
                            vm.open(it)
                        }
                    }
                }
            }
            if (vm.loading) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(22.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }

        // ⚠️ Posé PAR-DESSUS la grille et non dans l'en-tête : l'en-tête est
        // déjà chargé de deux rangées, et un bouton de plus y aurait rétréci
        // le titre de la période.
        FloatingActionButton(
            onClick = { vm.openComposer() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Nouvelle rencontre")
        }
    }

    if (vm.composing) {
        ComposerRencontre(
            jour = vm.anchor,
            zone = vm.zone,
            calendriers = vm.calendars,
            busy = vm.busy,
            onCreer = { titre, debut, fin, lieu, visio, calendrier ->
                vm.createEvent(titre, debut, fin, lieu, visio, calendrier)
            },
            onFermer = { vm.closeComposer() },
        )
    }

    vm.selected?.let { event ->
        FicheRencontre(
            event = event,
            zone = vm.zone,
            snoozeMinutes = vm.config.snoozeMinutes,
            rsvpOffert = vm.config.features.rsvp,
            busy = vm.busy,
            onSnooze = { vm.snooze(event, it) },
            onDismiss = { vm.dismiss(event) },
            onRsvp = { vm.rsvp(event, it) },
            onSkipAgenda = { vm.setFlags(event, skipAgenda = it) },
            onSkipDashboard = { vm.setFlags(event, skipDashboard = it) },
            onClose = { vm.close() },
        )
    }
}

@Composable
private fun AgendaHeader(vm: AgendaViewModel) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.step(forward = false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Période précédente")
                }
                Text(
                    titre(vm.days),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { vm.step(forward = true) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Période suivante")
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = vm.mode == AgendaMode.DAY,
                    onClick = { vm.switchMode(AgendaMode.DAY) },
                    label = { Text("Jour") },
                )
                FilterChip(
                    selected = vm.mode == AgendaMode.WEEK,
                    onClick = { vm.switchMode(AgendaMode.WEEK) },
                    label = { Text("Semaine") },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.today() }) { Text("Aujourd'hui") }
            }
        }
    }
}

/**
 * Le compte Odoo peut vivre dans un autre fuseau que l'appareil.
 *
 * L'écran affiche l'heure de l'appareil, qui est celle que la personne lit sur
 * elle. Le dire évite la conclusion silencieuse « l'agenda est décalé ».
 */
@Composable
private fun FuseauAvertissement(userTz: String, zone: ZoneId) {
    if (userTz.isBlank() || userTz == zone.id) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            "Heures affichées dans le fuseau de l'appareil (" + zone.id +
                "). Le compte Odoo est réglé sur " + userTz + ".",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun JoursEnTete(
    days: List<LocalDate>,
    counts: Map<LocalDate, Int>,
    zone: ZoneId,
    onOpenTasks: () -> Unit,
) {
    val today = LocalDate.now(zone)
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(44.dp))
        days.forEach { day ->
            val isToday = day == today
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenTasks)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, FR).take(3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                val n = counts[day] ?: 0
                if (n > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "$n",
                            Modifier.padding(horizontal = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/** Les journées entières, hors grille : elles n'ont pas d'heure à occuper. */
@Composable
private fun JourneeEntiere(
    days: List<LocalDate>,
    events: List<AgendaEvent>,
    zone: ZoneId,
    onOpen: (AgendaEvent) -> Unit,
) {
    val parJour = days.associateWith { day ->
        events.filter { it.allday && it.dayAt(zone) == day }
    }
    if (parJour.values.all { it.isEmpty() }) return
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Spacer(Modifier.width(44.dp))
        days.forEach { day ->
            Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                parJour[day].orEmpty().forEach { event ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .clickable { onOpen(event) },
                    ) {
                        Text(
                            event.name,
                            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColonneDesHeures(hauteurHeure: Dp) {
    Column(Modifier.width(44.dp)) {
        (0..23).forEach { hour ->
            Box(Modifier.height(hauteurHeure).fillMaxWidth()) {
                Text(
                    "%02d:00".format(hour),
                    Modifier.align(Alignment.TopEnd).padding(end = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LignesDHeures(hauteurHeure: Dp) {
    Column(Modifier.fillMaxSize()) {
        (0..23).forEach { _ ->
            Box(
                Modifier
                    .height(hauteurHeure)
                    .fillMaxWidth()
                    .background(Color.Transparent),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

/**
 * Pose les rencontres d'un jour, en partageant la largeur quand elles se
 * chevauchent.
 *
 * ⚠️ Le segment est CALCULÉ par jour, pas pris tel quel : une rencontre de
 * 22:00 à 01:00 appartient à deux colonnes, et lui donner sa hauteur entière
 * dans chacune la ferait déborder de la grille.
 */
@Composable
private fun PlacerLesRencontres(
    day: LocalDate,
    events: List<AgendaEvent>,
    zone: ZoneId,
    hauteurHeure: Dp,
    onOpen: (AgendaEvent) -> Unit,
) {
    val segments = remember(day, events, zone) { segmentsFor(day, events, zone) }
    // ⚠️ La largeur se MESURE. Une fraction passée à `fillMaxWidth` donne bien
    // la bonne taille, mais aucun décalage horizontal : deux rencontres qui se
    // chevauchent se superposeraient alors exactement, et la deuxième serait
    // invisible et incliquable.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val largeur = maxWidth
        segments.forEach { seg ->
            val colonne = largeur / seg.columnCount
            val hauteur = hauteurHeure * (seg.durationMinutes / 60f)
            Box(
                Modifier
                    .width(colonne)
                    .offset(
                        x = colonne * seg.columnIndex,
                        y = hauteurHeure * (seg.startMinutes / 60f),
                    )
                    .height(if (hauteur < 22.dp) 22.dp else hauteur)
                    .padding(horizontal = 1.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(couleurDeLEvenement(seg.event))
                    .clickable { onOpen(seg.event) }
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                val encre = couleurDuTexte(seg.event)
                Column {
                    Text(
                        seg.event.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = encre,
                    )
                    if (seg.durationMinutes >= 45) {
                        Text(
                            heure(seg.event, zone),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = encre,
                        )
                    }
                }
                // Les pastilles disent d'un coup d'œil ce que la rencontre
                // porte : ordre du jour, compte rendu, rappel reporté, et le
                // trait qui marque une rencontre dispensée d'OdJ.
                val pastille = buildString {
                    if (seg.event.hasAgenda) append("OdJ ")
                    if (seg.event.hasMinutes) append("CR ")
                    if (seg.event.snoozedUntil != null) append("⏰ ")
                    if (seg.event.skipAgenda) append("—")
                }
                if (pastille.isNotBlank()) {
                    Text(
                        pastille.trim(),
                        Modifier.align(Alignment.BottomEnd),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = encre,
                    )
                }
            }
        }
    }
}

/**
 * La couleur vient du serveur, qui applique la règle d'Odoo.
 *
 * ⚠️ Ne pas la recalculer ici : il faudrait recopier une palette de 56 tons et
 * la formule `((clé - 1) % 55) + 1`, donc les corriger à deux endroits. Le
 * repli sur le thème ne sert qu'aux instances trop anciennes pour l'envoyer.
 */
@Composable
private fun couleurDeLEvenement(event: AgendaEvent): Color {
    val douce = hexOuNull(event.colorSoft)
    return when {
        // Un rappel déjà vu s'efface sans disparaître : il reste à sa place
        // dans la journée, mais cesse de réclamer l'œil.
        event.dismissedAt != null && douce != null -> douce.copy(alpha = 0.45f)
        event.dismissedAt != null -> MaterialTheme.colorScheme.surfaceVariant
        douce != null -> douce
        event.showAs == "free" -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
}

/** Le texte lisible sur ce fond, décidé par la luminance et non par le thème. */
@Composable
private fun couleurDuTexte(event: AgendaEvent): Color {
    val fond = hexOuNull(event.colorSoft) ?: return MaterialTheme.colorScheme.onPrimaryContainer
    val luminance = 0.299f * fond.red + 0.587f * fond.green + 0.114f * fond.blue
    return if (luminance > 0.6f) Color(0xFF1A1C1E) else Color.White
}

internal fun hexOuNull(brut: String): Color? {
    if (brut.length != 7 || !brut.startsWith("#")) return null
    return runCatching { Color(android.graphics.Color.parseColor(brut)) }.getOrNull()
}

private fun heure(event: AgendaEvent, zone: ZoneId): String {
    val f = DateTimeFormatter.ofPattern("HH:mm")
    val start = event.startAt(zone) ?: return ""
    val stop = event.stopAt(zone)
    return if (stop == null) f.format(start) else "${f.format(start)} – ${f.format(stop)}"
}

private fun titre(days: List<LocalDate>): String {
    val jour = DateTimeFormatter.ofPattern("EEEE d MMMM", FR)
    val court = DateTimeFormatter.ofPattern("d MMM", FR)
    if (days.size == 1) return jour.format(days.first()).replaceFirstChar { it.uppercase() }
    return "${court.format(days.first())} – ${court.format(days.last())} ${days.last().year}"
}
