@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.agenda

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.ui.BanniereFraicheur
import com.bluefoxconsultant.sms.ui.RelirePendantQuOnRegarde
import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.OdooLinks
import java.time.LocalDate
import java.time.ZoneId
import com.bluefoxconsultant.sms.ui.BoutonTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluefoxconsultant.sms.data.Graph
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.asString

/**
 * Les échéances, dans leur propre onglet.
 *
 * Elles ne sont PAS posées sur la grille de l'agenda : 131 échéances contre 9
 * rencontres sur quatorze jours, avec des pointes à 25 dans une journée. Les
 * mêler aux rencontres reviendrait à cacher les rencontres.
 */
@Composable
fun TachesScreen() {
    val vm: TachesViewModel = viewModel()
    val context = LocalContext.current

    // Même battement que l'agenda : au retour à l'écran, puis à la minute. Une
    // échéance passée en retard change alors de seau sans qu'on ait à sortir de
    // l'onglet et à y revenir.
    RelirePendantQuOnRegarde { vm.tick() }

    // Une tâche demandée d'ailleurs (un courriel classé dessus) : on l'ouvre
    // et on CONSOMME la demande, sinon la même fiche reviendrait à chaque
    // retour sur l'onglet.
    val demandee by Graph.agendaStore.demandeTache.collectAsStateWithLifecycle()
    LaunchedEffect(demandee) {
        val id = demandee ?: return@LaunchedEffect
        Graph.agendaStore.consommerTache()
        vm.ouvrirParId(id)
    }

    // Le retour arrière ferme la recherche avant de quitter l'onglet.
    BackHandler(enabled = vm.rechercheOuverte) { vm.closeSearch() }

    // Une tâche annulée quitte la liste : la barre du bas dit laquelle, et
    // offre de la rétablir. « Rétablir » et non « Annuler », qui voudrait dire
    // deux choses contraires dans la même phrase.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.annulation) {
        val annulee = vm.annulation ?: return@LaunchedEffect
        val resultat = snackbar.showSnackbar(
            message = context.getString(R.string.tasks_cancelled, annulee.tache.name),
            actionLabel = context.getString(R.string.tasks_cancelled_restore),
            duration = SnackbarDuration.Long,
        )
        if (resultat == SnackbarResult.ActionPerformed) vm.restoreCancelled() else vm.forgetCancelled()
    }

    // 🔴 Un `Scaffold`, comme tous les autres écrans. Sans lui rien ne peint le
    // fond : celui de la fenêtre Android traversait, blanc, et le texte des
    // tâches — prévu pour un fond sombre — devenait presque invisible dessus.
    Scaffold(
        // ⚠️ La couleur est POSÉE, pas héritée d'un défaut de la bibliothèque.
        // Le fond blanc venait déjà d'une couleur que personne n'avait choisie ;
        // s'en remettre au `containerColor` implicite de Scaffold serait
        // reprendre le même pari avec un autre dé.
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.openComposer() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tasks_new_task))
            }
        },
    ) { insets ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(insets),
    ) {
        Surface(tonalElevation = 2.dp) {
            if (vm.rechercheOuverte) {
                BarreRecherche(
                    terme = vm.recherche,
                    cherche = vm.cherche,
                    onChange = vm::onSearchChange,
                    onClose = vm::closeSearch,
                )
            } else Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = vm.horizonDays == 7L,
                    onClick = { vm.setHorizon(7L) },
                    label = { Text(pluralStringResource(R.plurals.tasks_horizon_days, 7, 7)) },
                )
                FilterChip(
                    selected = vm.horizonDays == 14L,
                    onClick = { vm.setHorizon(14L) },
                    label = { Text(pluralStringResource(R.plurals.tasks_horizon_days, 14, 14)) },
                )
                FilterChip(
                    selected = vm.horizonDays == 30L,
                    onClick = { vm.setHorizon(30L) },
                    label = { Text(pluralStringResource(R.plurals.tasks_horizon_days, 30, 30)) },
                )
                Spacer(Modifier.weight(1f))
                if (vm.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                IconButton(onClick = { vm.openSearch() }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.tasks_search))
                }
                BoutonTheme()
            }
        }

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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = { vm.clearError(); vm.load() }) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (vm.enRecherche) {
                // La recherche remplace les seaux le temps qu'elle dure : ses
                // résultats débordent l'horizon choisi, et les mêler aux
                // sections ferait croire qu'ils y tombent.
                resultatsRecherche(vm, context)
                item { Spacer(Modifier.height(72.dp)) }
                return@LazyColumn
            }
            // Les sans-échéance EN TÊTE (#25717) : ce sont celles qu'aucune date
            // ne ramènera jamais sous les yeux. Toujours chargées à la demande —
            // il y en a des centaines — mais la rangée qui les ouvre est la
            // première chose de la liste, et la section dépliée aussi.
            item(key = "sans-echeance") {
                TextButton(
                    onClick = { vm.toggleUndated() },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    val n = vm.undatedCount
                    Text(
                        if (vm.showUndated) pluralStringResource(R.plurals.tasks_hide_undated, n, n)
                        else pluralStringResource(R.plurals.tasks_show_undated, n, n),
                    )
                }
            }
            if (vm.showUndated) {
                items(vm.undated, key = { "u" + it.id }) { task ->
                    LigneTache(task, vm.zone, vm.busy,
                        onComplete = { vm.complete(task, it) },
                        onOpen = { vm.open(task) })
                }
            }

            // En retard, Aujourd'hui, un intertitre par jour de la semaine qui
            // vient, Plus tard : découpé par date locale, voir `decouperTaches`.
            if (vm.sections.isEmpty()) {
                item(key = "rien") {
                    Text(
                        stringResource(R.string.tasks_nothing_due),
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            vm.sections.forEach { section ->
                item(key = "s-" + section.cle) {
                    Entete(
                        titreSection(section.intertitre),
                        section.taches.size,
                        alerte = section.alerte,
                        majuscules = section.majuscules,
                    )
                }
                items(section.taches, key = { "t" + it.id }) { task ->
                    LigneTache(task, vm.zone, vm.busy,
                        onComplete = { vm.complete(task, it) },
                        onOpen = { vm.open(task) })
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
        }
    }

    }

    vm.selected?.let { task ->
        FicheTache(
            task = task,
            zone = vm.zone,
            options = vm.options,
            busy = vm.busy,
            onWrite = { vm.write(task, it) },
            onComplete = { vm.complete(task, it) },
            onCancel = { vm.cancel(task) },
            onOuvrirOdoo = { OdooLinks.openRecord(context, "project.task", task.id) },
            onClose = { vm.close() },
        )
    }

    if (vm.composing) {
        ComposerTache(
            zone = vm.zone,
            options = vm.options,
            busy = vm.busy,
            onCreer = { nom, projet, echeance, priorite, etiquettes ->
                vm.create(nom, projet, echeance, priorite, etiquettes)
            },
            onFermer = { vm.closeComposer() },
        )
    }
}

/**
 * Le champ de recherche, à la place des horizons le temps qu'il est ouvert.
 * Le clavier s'ouvre avec lui : on l'a ouvert pour taper.
 */
@Composable
private fun BarreRecherche(
    terme: String,
    cherche: Boolean,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = terme,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.tasks_search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (cherche) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            },
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
        }
    }
}

/** Les résultats d'une recherche, à plat, dans l'ordre du serveur. */
private fun androidx.compose.foundation.lazy.LazyListScope.resultatsRecherche(
    vm: TachesViewModel,
    context: android.content.Context,
) {
    item(key = "r-entete") {
        Entete(
            if (vm.cherche && vm.resultats.isEmpty()) "…"
            else context.getString(R.string.tasks_search_results),
            vm.resultats.size,
        )
    }
    if (vm.rechercheLocale) {
        item(key = "r-locale") {
            Text(
                stringResource(R.string.tasks_search_local_only),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!vm.cherche && vm.resultats.isEmpty()) {
        item(key = "r-rien") {
            Text(
                stringResource(R.string.tasks_search_none, vm.recherche.trim()),
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(vm.resultats, key = { "r" + it.id }) { task ->
        LigneTache(task, vm.zone, vm.busy,
            onComplete = { vm.complete(task, it) },
            onOpen = { vm.open(task) })
    }
    if (vm.resultatsTronques) {
        item(key = "r-plus") {
            Text(
                stringResource(R.string.tasks_search_more),
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * L'en-tête d'un seau.
 *
 * ⚠️ Le compte est une pastille, pas une parenthèse dans le titre. « En retard
 * (12) » se lit comme un titre ; une pastille rouge portant 12 se lit comme
 * une quantité, ce qui est l'information qu'on cherche en ouvrant cet écran.
 * Et la barre pleine d'un bout à l'autre est remplacée par un fond discret :
 * peindre toute la largeur en rouge pour trois tâches en retard rendait le
 * rouge illisible partout ailleurs.
 */
@Composable
private fun Entete(titre: String, compte: Int, alerte: Boolean = false, majuscules: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (majuscules) titre.uppercase() else titre,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = if (alerte) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (compte > 0) {
            Surface(
                color = if (alerte) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "$compte",
                    Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (alerte) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Une tâche, avec de quoi la lire ET la fermer.
 *
 * ⚠️ La case est à GAUCHE et le reste de la ligne ouvre la fiche : compléter
 * est le geste le plus fréquent, et le faire passer par un écran de détail
 * était le principal reproche fait à la v1. Le liseré porte la couleur de la
 * tâche quand elle en a une, sinon celle de sa première étiquette.
 */
@Composable
private fun LigneTache(
    task: AgendaTask,
    zone: ZoneId,
    busy: Boolean,
    onComplete: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val liseré = hexOuNull(task.color)
        ?: task.tags.firstNotNullOfOrNull { hexOuNull(it.color) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(liseré ?: Color.Transparent),
        )
        Checkbox(
            checked = task.done,
            enabled = !busy,
            onCheckedChange = { onComplete(it) },
        )
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.priority != "0") {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.tasks_high_priority),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp).padding(end = 4.dp),
                    )
                }
                Text(
                    task.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                // Au JOUR, comme les sections (#25717) : une tâche de ce matin
                // rangée sous « Aujourd'hui » ne doit pas s'afficher en rouge
                // comme un retard parce que son heure est passée.
                val enRetard = !task.done && task.deadlineAt(zone)
                    ?.toLocalDate()?.isBefore(LocalDate.now(zone)) == true
                Text(
                    echeance(task, zone),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (enRetard) FontWeight.Bold else FontWeight.Normal,
                    color = if (enRetard) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val sous = listOf(task.project, task.stage, task.partner)
                .filter { it.isNotBlank() }
            if (sous.isNotEmpty()) {
                Text(
                    sous.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (task.tags.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    task.tags.take(4).forEach { tag ->
                        val teinte = hexOuNull(tag.color)
                            ?: MaterialTheme.colorScheme.surfaceVariant
                        Surface(
                            color = teinte.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                tag.name,
                                Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * L'intertitre d'une section, mis en mots dans la langue du téléphone. Le
 * découpage ne fait que dire de quelle section il s'agit : voir [Intertitre].
 */
@Composable
private fun titreSection(intertitre: Intertitre): String = when (intertitre) {
    Intertitre.EnRetard -> stringResource(R.string.tasks_section_overdue)
    Intertitre.Aujourdhui -> stringResource(R.string.agenda_today)
    is Intertitre.Jour -> titreDuJour(intertitre.date, formateurDate("EEEEdMMMM"))
    Intertitre.PlusTard -> stringResource(R.string.tasks_section_later)
}

/**
 * « aujourd'hui », « demain », sinon la date.
 *
 * Une date absolue pour aujourd'hui obligerait à la comparer de tête à la date
 * du jour, ce qui est exactement le travail que l'écran est censé éviter.
 */
@Composable
private fun echeance(task: AgendaTask, zone: ZoneId): String {
    val court = formateurDate("dMMM")
    val at = task.deadlineAt(zone) ?: return "—"
    val today = LocalDate.now(zone)
    val day = at.toLocalDate()
    val heure = HEURE.format(at)
    return when (day) {
        today -> stringResource(R.string.tasks_due_today_at, heure)
        today.plusDays(1) -> stringResource(R.string.tasks_due_tomorrow_at, heure)
        else -> court.format(day)
    }
}
