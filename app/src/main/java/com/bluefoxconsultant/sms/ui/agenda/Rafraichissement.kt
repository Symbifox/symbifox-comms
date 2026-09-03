package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ce qui relit l'agenda pendant qu'on le regarde.
 *
 * 🔴 Le défaut réparé ici : **rien ne relisait jamais**. Le modèle de vue
 * chargeait dans son `init`, et la barre du bas empile les onglets avec
 * `saveState`/`restoreState` — revenir sur l'agenda RESTITUE le modèle au lieu
 * d'en créer un, donc l'`init` ne repassait pas. Seules trois commandes
 * rechargeaient : les flèches, un changement de mode, le pictogramme du jour.
 * Une grille ouverte le matin montrait encore le matin le soir, sans le dire.
 *
 * Le courriel avait déjà la parade (`MailListScreen` : relecture à `ON_RESUME`
 * plus tirer-pour-relire) ; l'agenda ne l'avait pas. C'est la même, en un seul
 * mécanisme : `repeatOnLifecycle(RESUMED)` relit en entrant dans l'état — donc
 * au retour d'onglet ET au retour d'arrière-plan — puis à la minute tant que
 * l'écran est devant. Il s'annule à la mise en pause : aucun trafic pour un
 * écran que personne ne regarde.
 */

/** Âge au-delà duquel une relecture silencieuse vaut la peine. */
const val FRAICHEUR_MS = 60_000L

/**
 * Âge au-delà duquel on l'ÉCRIT à l'écran.
 *
 * Une relecture silencieuse qui échoue ne doit pas effacer ce qui est affiché —
 * mais passé ce délai, se taire reviendrait à laisser croire que la grille est
 * à jour, c'est-à-dire à refaire le défaut d'origine en plus discret.
 */
const val PERIME_MS = 5 * 60_000L

/** Faut-il relire ? Pure : c'est elle que le banc éprouve. */
fun relectureUtile(maintenant: Long, derniereLecture: Long, age: Long = FRAICHEUR_MS): Boolean =
    derniereLecture <= 0L || maintenant - derniereLecture >= age

/**
 * Minuit est passé pendant que l'écran était ouvert.
 *
 * Rend la nouvelle ancre, ou `null` s'il n'y a rien à bouger. On ne déplace que
 * si l'ancre SUIVAIT aujourd'hui : quelqu'un parti regarder la semaine
 * prochaine ne doit pas être ramené de force au jour courant.
 */
fun ancreARattraper(ancre: LocalDate, aujourdhui: LocalDate, suitAujourdhui: Boolean): LocalDate? =
    if (suitAujourdhui && ancre != aujourdhui) aujourdhui else null

private val HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** « 16:42 », dans le fuseau de l'appareil. */
fun heureDeLecture(millis: Long, zone: ZoneId): String =
    HEURE.format(Instant.ofEpochMilli(millis).atZone(zone))

/**
 * Appelle [tick] en entrant dans l'état RESUMED, puis à chaque minute.
 *
 * ⚠️ [rememberUpdatedState] : l'effet est calé sur le propriétaire de cycle de
 * vie, pas sur la lambda. Sans ça, la boucle garderait la toute première
 * lambda, donc le tout premier modèle de vue.
 */
@Composable
fun RelirePendantQuOnRegarde(tick: () -> Unit) {
    val proprietaire = LocalLifecycleOwner.current
    val courant by rememberUpdatedState(tick)
    LaunchedEffect(proprietaire) {
        proprietaire.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                courant()
                delay(FRAICHEUR_MS)
            }
        }
    }
}

/**
 * Ce qui est affiché date, et la relecture ne passe plus.
 *
 * Une relecture silencieuse qui échoue garde l'écran plutôt que de le vider sur
 * une coupure de deux secondes. Mais se taire indéfiniment referait le défaut
 * d'origine sous une forme plus discrète — une grille qui a l'air à jour et qui
 * ne l'est pas. Passé [PERIME_MS], on écrit l'heure de la dernière lecture.
 */
@Composable
fun BanniereFraicheur(lu: Long, verifieA: Long, zone: ZoneId, onRelire: () -> Unit) {
    if (lu <= 0L || verifieA - lu < PERIME_MS) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Dernière lecture à " + heureDeLecture(lu, zone) + ".",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onRelire) { Text("Relire") }
        }
    }
}
