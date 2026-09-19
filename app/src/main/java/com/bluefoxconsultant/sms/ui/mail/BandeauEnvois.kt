package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.data.EnvoisDifferes
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.ui.asString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Le bandeau des envois, au-dessus de la barre d'onglets, sur tous les écrans
 * (#25764) : « Envoi dans 7 s · Annuler » tant qu'un délai court, puis ce qu'il
 * est advenu de l'envoi.
 *
 * Pas un `Snackbar` d'écran : le composeur qui a décidé l'envoi est déjà
 * fermé, et l'écran où l'on atterrit (un fil, la liste) n'en sait rien.
 */
@Composable
fun BandeauEnvois(onOuvrirBrouillon: (String) -> Unit) {
    if (!Graph.isReady) return
    val envois = Graph.envois
    val enAttente by envois.enAttente.collectAsStateWithLifecycle()
    val issue by envois.issue.collectAsStateWithLifecycle()
    val portee = rememberCoroutineScope()

    // Tous les envois dont le délai court, et non le dernier seul : deux
    // envois à dix secondes d'écart doivent pouvoir s'annuler tous les deux.
    if (enAttente.isNotEmpty()) {
        var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(enAttente.map { it.token }) {
            while (true) {
                maintenant = System.currentTimeMillis()
                if (enAttente.all { maintenant >= it.echeanceMs }) break
                delay(250)
            }
        }
        Column {
            enAttente.takeLast(3).forEach { envoi ->
                val secondes = ((envoi.echeanceMs - maintenant + 999) / 1000).coerceAtLeast(0)
                Ligne(
                    texte = stringResource(R.string.mail_undo_sending_in, secondes.toInt()),
                    action = stringResource(R.string.common_undo),
                    onAction = {
                        portee.launch {
                            envois.annuler(envoi.token)?.let { onOuvrirBrouillon(it.id) }
                        }
                    },
                    annoncer = false,
                )
            }
        }
        return
    }

    val dit = issue ?: return
    LaunchedEffect(dit) {
        delay(if (dit is EnvoisDifferes.Issue.Refuse) 8000 else 4000)
        envois.consommerIssue()
    }
    when (dit) {
        EnvoisDifferes.Issue.Parti -> Ligne(stringResource(R.string.mail_send_sent))
        EnvoisDifferes.Issue.HorsLigne -> Ligne(stringResource(R.string.mail_compose_queued_offline))
        EnvoisDifferes.Issue.TropTard -> Ligne(stringResource(R.string.mail_undo_too_late))
        is EnvoisDifferes.Issue.Info -> Ligne(dit.texte.asString())
        is EnvoisDifferes.Issue.Refuse -> Ligne(
            texte = stringResource(R.string.mail_send_refused_kept, dit.raison.asString()),
            action = stringResource(R.string.mail_send_open_draft),
            onAction = {
                envois.consommerIssue()
                onOuvrirBrouillon(dit.brouillonId)
            },
        )
    }
}

@Composable
private fun Ligne(
    texte: String,
    action: String? = null,
    onAction: () -> Unit = {},
    /** Faux pour le compte à rebours : TalkBack le relirait à chaque seconde. */
    annoncer: Boolean = true,
) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        // Annoncé par TalkBack : un envoi refusé ne se voit que huit secondes.
        modifier = if (annoncer) Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
        else Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                texte,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (action != null) {
                TextButton(onClick = onAction) {
                    Text(action, color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}
