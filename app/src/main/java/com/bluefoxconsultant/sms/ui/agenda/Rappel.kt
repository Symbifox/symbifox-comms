package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.data.AgendaAlarm
import com.bluefoxconsultant.sms.data.AgendaEvent
import com.bluefoxconsultant.sms.data.parseInstant
import java.time.Instant
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiPlural
import com.bluefoxconsultant.sms.ui.uiText

/**
 * Ce que la section « Rappel » de la fiche doit dire.
 *
 * 🔴 Le défaut réparé : la fiche offrait « reporter » et « vu » à TOUTE
 * rencontre, y compris à celle de la semaine prochaine dont le rappel n'a pas
 * encore sonné. Reporter un rappel qui n'est pas parti ne veut rien dire, et
 * « vu » non plus. Les deux gestes n'apparaissent qu'une fois le rappel sonné
 * et tant que la rencontre n'est pas finie ; avant, la fiche montre ce qui est
 * configuré et l'heure où ça sonne.
 *
 * Pure : c'est elle que le banc éprouve.
 */
sealed class EtatRappel {
    /** Instance sans `api` 3 : rien n'est su, on garde les gestes d'avant. */
    data object Ancien : EtatRappel()

    /** Aucun rappel de type notification sur cette rencontre. */
    data object Aucun : EtatRappel()

    /** Configuré, pas encore sonné. */
    data class AVenir(val alarms: List<AgendaAlarm>) : EtatRappel()

    /** Sonné, rencontre pas finie : les deux gestes ont un sens. */
    data class Sonne(val alarms: List<AgendaAlarm>) : EtatRappel()

    /** La rencontre est terminée : plus rien à reporter. */
    data class Passe(val alarms: List<AgendaAlarm>) : EtatRappel()
}

fun etatRappel(event: AgendaEvent, api: Int, maintenant: Instant): EtatRappel {
    if (api < 3) return EtatRappel.Ancien
    if (event.alarms.isEmpty()) return EtatRappel.Aucun
    val fin = event.stopInstant ?: event.startInstant
    if (fin != null && !fin.isAfter(maintenant)) return EtatRappel.Passe(event.alarms)
    // Le verdict du serveur d'abord : c'est SON horloge qui fait sonner. Le
    // calcul local ne sert qu'à rattraper un rappel dont l'heure est passée
    // depuis que la fiche a été lue.
    val sonne = event.reminderFired || event.alarms.any { alarm ->
        parseInstant(alarm.notifyAt ?: "")?.let { !it.isAfter(maintenant) } ?: false
    }
    return if (sonne) EtatRappel.Sonne(event.alarms) else EtatRappel.AVenir(event.alarms)
}

/**
 * « 15 min avant », « 1 h avant », « 2 j avant ».
 *
 * Un [UiText] et non une phrase : le découpage en jours, heures et minutes est
 * ce que le banc éprouve, la langue se choisit à l'affichage.
 */
fun libelleDelai(minutes: Int): UiText = when {
    minutes <= 0 -> uiText(R.string.meeting_reminder_at_start)
    minutes < 60 -> uiPlural(R.plurals.meeting_reminder_minutes_before, minutes)
    minutes % (24 * 60) == 0 -> uiPlural(R.plurals.meeting_reminder_days_before, minutes / (24 * 60))
    minutes % 60 == 0 -> uiPlural(R.plurals.meeting_reminder_hours_before, minutes / 60)
    else -> uiText(R.string.meeting_reminder_hours_minutes_before, minutes / 60, minutes % 60)
}
