package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluefoxconsultant.sms.data.AgendaEvent
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

/**
 * Ce qu'une rencontre porte comme marques, sur la grille comme en liste.
 *
 * Pure, et c'est elle que le banc éprouve : la grille et la liste la lisent
 * toutes les deux, donc une rencontre confirmée l'est aux deux endroits, et un
 * OdJ qui apparaît sur l'une apparaît sur l'autre.
 *
 * ⚠️ « Confirmée » est MA présence, pas celle des autres : c'est la question
 * qu'on se pose en regardant la grille (« ai-je répondu ? »), et l'état des
 * autres participants vit sur la fiche.
 */
data class Marques(
    val confirmee: Boolean = false,
    val odj: Boolean = false,
    val cr: Boolean = false,
    val reportee: Boolean = false,
    val sansOdj: Boolean = false,
) {
    val vide: Boolean get() = !confirmee && !odj && !cr && !reportee && !sansOdj
}

fun marques(event: AgendaEvent): Marques = Marques(
    confirmee = event.myState == "accepted",
    odj = event.hasAgenda,
    cr = event.hasMinutes,
    reportee = event.snoozedUntil != null,
    sansOdj = event.skipAgenda && !event.hasAgenda,
)

/**
 * Le « C » de confirmée : une lettre dans un rond, lisible à 10 dp, là où un
 * crochet se confondrait avec une case à cocher.
 */
@Composable
fun PastilleConfirmee(encre: Color, fond: Color, taille: Dp = 12.dp) {
    Box(
        Modifier.size(taille).background(encre, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.meeting_confirmed_badge),
            color = fond,
            fontSize = (taille.value * 0.72f).sp,
            lineHeight = (taille.value * 0.8f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Les pictogrammes d'ordre du jour, de compte rendu et de report, sans le
 * « C » : celui-ci se pose à côté du titre, pas dans la rangée.
 *
 * Des pictogrammes et non « OdJ CR » en texte : à 8 sp sur un bloc de la
 * grille, les lettres se lisaient comme du bruit. Un pictogramme de 10 dp
 * se reconnaît sans se lire.
 */
@Composable
fun PictosRencontre(m: Marques, encre: Color, taille: Dp = 12.dp) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.odj) {
            Icon(
                Icons.AutoMirrored.Filled.ListAlt,
                contentDescription = stringResource(R.string.meeting_agenda),
                tint = encre,
                modifier = Modifier.size(taille),
            )
        }
        if (m.cr) {
            Icon(
                Icons.Filled.Summarize,
                contentDescription = stringResource(R.string.meeting_minutes),
                tint = encre,
                modifier = Modifier.size(taille),
            )
        }
        if (m.reportee) {
            Icon(
                Icons.Filled.Snooze,
                contentDescription = stringResource(R.string.meeting_reminder_snoozed),
                tint = encre,
                modifier = Modifier.size(taille),
            )
        }
        if (m.sansOdj) {
            Text(
                "—",
                color = encre,
                fontSize = (taille.value * 0.8f).sp,
                lineHeight = (taille.value).sp,
            )
        }
    }
}
