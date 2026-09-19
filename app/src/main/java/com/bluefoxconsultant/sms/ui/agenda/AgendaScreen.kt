@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluefoxconsultant.sms.ui.BanniereFraicheur
import com.bluefoxconsultant.sms.ui.RelirePendantQuOnRegarde
import com.bluefoxconsultant.sms.data.AgendaEvent
import com.bluefoxconsultant.sms.ui.BoutonTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import com.bluefoxconsultant.sms.data.Graph
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.asString

/** L'heure à laquelle la grille s'ouvre : la journée de travail, pas minuit. */
private const val OPEN_AT_HOUR = 7

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

    // Relire au retour sur l'onglet, au retour d'arrière-plan, puis à la minute.
    RelirePendantQuOnRegarde { vm.tick() }

    // ⚠️ Le zoom change la hauteur totale de la grille. Sans ce rattrapage, la
    // position gardée en points ferait sauter l'écran à une autre heure à
    // chaque pincement, et on perdrait ce qu'on regardait.
    var hauteurPrecedente by remember { mutableFloatStateOf(vm.hourHeight) }
    LaunchedEffect(vm.hourHeight) {
        val rapport = vm.hourHeight / hauteurPrecedente
        if (rapport != 1f) scroll.scrollTo((scroll.value * rapport).toInt())
        hauteurPrecedente = vm.hourHeight
    }

    // 🔴 Un `Scaffold`, comme tous les autres écrans. Sans lui, rien ne peint le
    // fond : c'est celui de la fenêtre Android qui traverse, blanc, et le texte
    // prévu pour un fond sombre devient illisible dessus. Le bouton d'ajout y
    // passe aussi, plutôt que d'être aligné à la main dans une Box.
    Scaffold(
        // ⚠️ La couleur est POSÉE, pas héritée d'un défaut de la bibliothèque.
        // Le fond blanc venait déjà d'une couleur que personne n'avait choisie ;
        // s'en remettre au `containerColor` implicite de Scaffold serait
        // reprendre le même pari avec un autre dé.
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.openComposer() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.agenda_new_meeting))
            }
        },
    ) { insets ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(insets),
    ) {
        AgendaHeader(vm)
        FuseauAvertissement(vm.config.userTz, vm.zone)
        BanniereFraicheur(vm.lu, vm.verifieA, vm.zone) { vm.refresh() }
        vm.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message.asString(),
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.clearError(); vm.load() }) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }

        val days = vm.days
        // ⚠️ Le geste ne prend que sur un enfant qui défile, et la grille
        // s'ouvre à 07:00 : tirer n'y relit qu'une fois remonté à minuit. Il
        // sert surtout à la vue liste. Les deux autres commandes de relecture
        // sont le pictogramme du jour et le mode déjà choisi qu'on retouche.
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f),
        ) {
        if (vm.mode == AgendaMode.LIST) {
            Box(Modifier.fillMaxSize()) {
                VueListe(days, vm.events, vm.taskCounts, vm.zone,
                    onOpen = { vm.open(it) }, onOpenTasks = onOpenTasks)
                if (vm.loading) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(22.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        } else {
        Column(Modifier.fillMaxSize()) {
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
                        // Posé APRÈS les rencontres pour passer par-dessus :
                        // dessiné avant, le trait disparaîtrait sous le bloc
                        // de la rencontre en cours, c'est-à-dire précisément
                        // au moment où on le cherche.
                        TraitDeMaintenant(day, vm.zone, hauteurHeure)
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
        }
        }
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
            api = Graph.agendaStore.ping.value.api,
            partenaires = vm.partenaires,
            onChercherPartenaires = { vm.chercherPartenaires(it) },
            onAjouterParticipant = { id, inviter -> vm.ajouterParticipant(event, id, inviter) },
            onRetirerParticipant = { vm.retirerParticipant(event, it) },
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.agenda_previous_period))
                }
                Text(
                    titre(vm.days),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { vm.step(forward = true) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.agenda_next_period))
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
                    label = { Text(stringResource(R.string.agenda_mode_day)) },
                )
                FilterChip(
                    selected = vm.mode == AgendaMode.WEEK,
                    onClick = { vm.switchMode(AgendaMode.WEEK) },
                    label = { Text(stringResource(R.string.agenda_mode_week)) },
                )
                FilterChip(
                    selected = vm.mode == AgendaMode.LIST,
                    onClick = { vm.switchMode(AgendaMode.LIST) },
                    label = { Text(stringResource(R.string.agenda_mode_list)) },
                )
                Spacer(Modifier.weight(1f))
                BoutonTheme()
                // ⚠️ Un pictogramme et non « Aujourd'hui » : depuis l'ajout du
                // troisième mode et du bouton de thème, le libellé repassait à
                // la ligne et coupait le mot en deux.
                IconButton(onClick = { vm.today() }) {
                    Icon(Icons.Filled.Today, contentDescription = stringResource(R.string.agenda_today))
                }
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
            stringResource(R.string.agenda_time_zone_notice, zone.id, userTz),
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
    val locale = localeAffichage()
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
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).take(3),
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
                // 🔴 Tout est EMPILÉ, rien n'est superposé. La pastille était
                // posée en bas à droite par-dessus la colonne de texte : sur un
                // bloc court, le titre, l'heure et « OdJ CR » se chevauchaient
                // et devenaient illisibles tous les trois.
                //
                // Ce qui rentre est décidé par la HAUTEUR : une ligne tient
                // dans une quinzaine de minutes, et promettre trois lignes dans
                // un bloc d'un quart d'heure revient à n'en montrer aucune.
                val lignes = (seg.durationMinutes / 15).coerceIn(1, 4)
                // Les marques sont des pictogrammes, pas des lettres : « OdJ CR »
                // à 8 sp se lisait comme du bruit. Le « C » de confirmée se pose
                // à côté du titre, dès la première ligne — c'est la marque qu'on
                // cherche en balayant la semaine.
                val m = marques(seg.event)
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (m.confirmee) {
                            PastilleConfirmee(
                                encre = encre,
                                fond = couleurDeLEvenement(seg.event),
                                taille = 10.dp,
                            )
                        }
                        Text(
                            seg.event.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            maxLines = if (lignes >= 3) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            color = encre,
                        )
                    }
                    if (lignes >= 3) {
                        Text(
                            heure(seg.event, zone),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            maxLines = 1,
                            color = encre,
                        )
                    }
                    if (lignes >= 4 && !(m.copy(confirmee = false)).vide) {
                        PictosRencontre(m, encre = encre.copy(alpha = 0.8f), taille = 10.dp)
                    }
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
    val f = HEURE
    val start = event.startAt(zone) ?: return ""
    val stop = event.stopAt(zone)
    return if (stop == null) f.format(start) else "${f.format(start)} – ${f.format(stop)}"
}

/** Le titre de la période, dans la langue du téléphone : voir `DatesLocales`. */
@Composable
private fun titre(days: List<LocalDate>): String {
    val jour = formateurDate("EEEEdMMMM")
    val court = formateurDate("dMMM")
    if (days.size == 1) return capitaliser(jour.format(days.first()), jour.locale)
    return stringResource(
        R.string.agenda_title_range,
        court.format(days.first()),
        court.format(days.last()),
        days.last().year,
    )
}


/**
 * Le trait rouge de l'heure courante, comme dans la vue Calendrier d'Odoo.
 *
 * ⚠️ Il ne se dessine que sur la colonne d'AUJOURD'HUI, dans le fuseau de
 * l'appareil, celui que la grille affiche. Le poser sur chaque colonne en
 * ferait une décoration : ce qu'on lit dans ce trait, c'est « où j'en suis »,
 * pas « quelle heure il est ».
 *
 * L'heure est relue chaque minute. Un trait figé à l'ouverture serait faux dès
 * la minute suivante, et faux sans le dire.
 */
@Composable
private fun TraitDeMaintenant(day: LocalDate, zone: ZoneId, hauteurHeure: Dp) {
    if (day != LocalDate.now(zone)) return

    var minutes by remember { mutableIntStateOf(minutesDepuisMinuit(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            minutes = minutesDepuisMinuit(zone)
            // Calé sur le début de la minute suivante plutôt que sur un délai
            // fixe : un réveil toutes les 60 s dérive et finit par sauter des
            // minutes entières.
            delay(((60 - (System.currentTimeMillis() / 1000 % 60)) * 1000L).coerceAtLeast(1000L))
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .offset(y = hauteurHeure * (minutes / 60f) - 1.dp)
            .height(2.dp)
            .background(TraitMaintenant),
    )
    Box(
        Modifier
            .offset(y = hauteurHeure * (minutes / 60f) - 4.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(TraitMaintenant),
    )
}

private fun minutesDepuisMinuit(zone: ZoneId): Int {
    val maintenant = java.time.ZonedDateTime.now(zone)
    return maintenant.hour * 60 + maintenant.minute
}

/** Le rouge d'Odoo pour l'heure courante, pas celui des erreurs du thème. */
private val TraitMaintenant = Color(0xFFEA4335)
