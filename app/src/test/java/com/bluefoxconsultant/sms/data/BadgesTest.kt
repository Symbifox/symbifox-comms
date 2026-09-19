package com.bluefoxconsultant.sms.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgesTest {

    @Test
    fun `le total des non-lus additionne tous les fils, masques compris`() {
        val fils = listOf(
            Thread(id = 1, unreadCount = 2),
            Thread(id = 2, unreadCount = 0),
            Thread(id = 3, unreadCount = 5, isHidden = true),
        )
        assertEquals(7, nonLusDesFils(fils))
        assertEquals(0, nonLusDesFils(emptyList()))
    }

    @Test
    fun `un compte negatif du serveur ne fait pas une pastille`() {
        assertEquals(0, nonLusDesFils(listOf(Thread(id = 1, unreadCount = -3))))
        val store = BadgeStore()
        store.poserCourriel(-1)
        assertEquals(0, store.badges.value.courriel)
    }

    @Test
    fun `chaque compteur bouge sans toucher aux autres`() {
        val store = BadgeStore()
        store.poserSms(3)
        store.poserCourriel(12)
        store.poserTaches(1)
        assertEquals(Badges(sms = 3, courriel = 12, taches = 1), store.badges.value)
        store.poserCourriel(0)
        assertEquals(Badges(sms = 3, courriel = 0, taches = 1), store.badges.value)
        store.vider()
        assertEquals(Badges(), store.badges.value)
    }
}
