package com.bluefoxconsultant.sms.ui.mail

import com.bluefoxconsultant.sms.data.Adresses
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Les créneaux proposés pour programmer un envoi (#25764).
 *
 * Quatre au plus, comme les « reporter à » de la boîte : on programme presque
 * toujours pour « tout à l'heure », « demain matin » ou « lundi ». Le reste
 * passe par le choix libre d'une date et d'une heure.
 */
object CreneauxEnvoi {

    enum class Cle { PLUS_TARD, DEMAIN_MATIN, DEMAIN_APRES_MIDI, LUNDI_MATIN }

    data class Creneau(val cle: Cle, val quand: ZonedDateTime)

    private val MATIN = LocalTime.of(8, 0)
    private val APRES_MIDI = LocalTime.of(13, 0)

    fun proposer(maintenant: ZonedDateTime): List<Creneau> {
        val out = mutableListOf<Creneau>()
        // « Plus tard aujourd'hui » : trois heures plus tard, à l'heure pile,
        // et seulement si ça tombe encore dans la journée de travail.
        val plusTard = maintenant.plusHours(3).truncatedTo(ChronoUnit.HOURS).plusHours(1)
        if (plusTard.toLocalDate() == maintenant.toLocalDate() && plusTard.hour <= 19) {
            out += Creneau(Cle.PLUS_TARD, plusTard)
        }
        val demain = maintenant.toLocalDate().plusDays(1)
        out += Creneau(Cle.DEMAIN_MATIN, demain.atTime(MATIN).atZone(maintenant.zone))
        out += Creneau(Cle.DEMAIN_APRES_MIDI, demain.atTime(APRES_MIDI).atZone(maintenant.zone))
        // Lundi matin, en fin de semaine seulement, et s'il n'est pas déjà « demain ».
        val jour = maintenant.dayOfWeek
        if (jour == DayOfWeek.FRIDAY || jour == DayOfWeek.SATURDAY) {
            val lundi = maintenant.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            out += Creneau(Cle.LUNDI_MATIN, lundi.atTime(MATIN).atZone(maintenant.zone))
        }
        return out
    }

    /**
     * Une heure choisie à la main est-elle acceptable ? Le serveur refuse ce
     * qui tombe à moins d'une minute ; on demande deux minutes, pour que le
     * temps de toucher Envoyer ne suffise pas à la rendre caduque.
     */
    fun acceptable(quand: ZonedDateTime, maintenant: ZonedDateTime): Boolean =
        quand.isAfter(maintenant.plusMinutes(2)) && quand.isBefore(maintenant.plusDays(365))
}

/** Les pastilles des trois champs À, Cc et Cci. */
object Destinataires {

    /** Ce qui a été tapé à la main, découpé en adresses. */
    fun saisie(brut: String): List<String> =
        brut.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }

    /**
     * Ajouter [nouvelles] à [champ] sans doublon — ni dans le champ, ni avec
     * une adresse déjà présente dans un des [autres] champs : une personne en
     * À et en Cci recevrait deux fois le même courriel.
     */
    fun ajouter(champ: List<String>, nouvelles: List<String>, vararg autres: List<String>): List<String> {
        val prises = (champ + autres.flatMap { it }).map { Adresses.cle(it) }.toMutableSet()
        val out = champ.toMutableList()
        for (adresse in nouvelles) {
            val cle = Adresses.cle(adresse)
            if (cle.isBlank() || cle in prises) continue
            prises += cle
            out += adresse
        }
        return out
    }
}
