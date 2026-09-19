package com.bluefoxconsultant.sms.ui.agenda

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * `DatePickerState` compte en millisecondes UTC à minuit. Le cas qui casse :
 * Montréal, à l'ouest de Greenwich, où « minuit local » et « minuit UTC » ne
 * tombent pas le même jour.
 */
class SelecteursTest {

    @Test
    fun `aller-retour par UTC rend le meme jour`() {
        val jour = LocalDate.of(2026, 9, 8)
        assertEquals(jour, dateDepuisMillisUtc(millisUtc(jour)))
    }

    @Test
    fun `minuit UTC n'est pas minuit a Montreal, et la date ne glisse pas`() {
        val jour = LocalDate.of(2026, 9, 8)
        val millis = millisUtc(jour)
        // Une conversion par le fuseau de l'appareil rendrait le 7.
        val parMontreal = java.time.Instant.ofEpochMilli(millis)
            .atZone(ZoneId.of("America/Toronto")).toLocalDate()
        assertEquals(LocalDate.of(2026, 9, 7), parMontreal)
        assertEquals(jour, dateDepuisMillisUtc(millis))
    }

    @Test
    fun `l'heure s'arrondit au quart le plus proche`() {
        assertEquals(LocalTime.of(9, 0), auQuartDHeure(LocalTime.of(9, 7)))
        assertEquals(LocalTime.of(9, 15), auQuartDHeure(LocalTime.of(9, 8)))
        assertEquals(LocalTime.of(10, 0), auQuartDHeure(LocalTime.of(9, 53)))
        assertEquals(LocalTime.of(0, 0), auQuartDHeure(LocalTime.of(23, 53)))
    }
}
