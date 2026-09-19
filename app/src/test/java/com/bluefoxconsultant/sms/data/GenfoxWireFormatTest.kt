package com.bluefoxconsultant.sms.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que l'api 4 de Gen ajoute (#25734), décodé avec la configuration de l'app.
 *
 * ⚠️ Écrit à la main d'après `controllers/mobile_api.py`, pas capturé : ce qui
 * est éprouvé ici, ce sont les `false` qu'Odoo pose où un texte ou un
 * identifiant est attendu (`search_read` sur un champ vide, `turn_id` d'une
 * conversation qui ne travaille pas). Décoder `false` dans un `String` lève,
 * et c'est toute la liste qui disparaissait alors.
 */
class GenfoxWireFormatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `une conversation au repos porte turn_id false`() {
        val sessions = json.decodeFromString<GenfoxSessionsResponse>("""
            {"sessions": [
              {"id": 7, "name": "Occupée", "write_date": "2026-09-15 03:00:00",
               "message_count": 4, "origin": "mobile", "busy": true, "turn_id": 812},
              {"id": 8, "name": "Libre", "write_date": "2026-09-15 02:00:00",
               "message_count": 2, "origin": "web", "busy": false, "turn_id": false}
            ]}
        """)
        assertEquals(listOf(true, false), sessions.sessions.map { it.busy })
        assertEquals(812, sessions.sessions[0].turnId)
        assertNull(sessions.sessions[1].turnId)
    }

    @Test
    fun `end_reason false se lit vide, et un serveur ancien ne l'envoie pas`() {
        val messages = json.decodeFromString<GenfoxMessagesResponse>("""
            {"session_id": 7, "session_name": "Occupée", "messages": [
              {"id": 1, "role": "assistant", "content": "ok", "state": "done",
               "end_reason": false, "tools": []},
              {"id": 2, "role": "assistant", "content": "(No response)",
               "state": "error", "end_reason": "stopped", "tools": []},
              {"id": 3, "role": "assistant", "content": "Délai", "state": "error",
               "tools": []}
            ]}
        """)
        val (fini, arrete, panne) = messages.messages
        assertEquals("", fini.endReason)
        assertFalse(fini.isError)
        assertTrue(arrete.isStopped)
        assertFalse("un arrêt voulu n'est pas une panne", arrete.isError)
        assertTrue(panne.isError)
        assertFalse(panne.isStopped)
    }

    @Test
    fun `le tour arrete le dit`() {
        val tour = json.decodeFromString<TurnResponse>("""
            {"turn_id": 812, "session_id": 7, "session_name": "Occupée",
             "state": "error", "end_reason": "stopped", "text": "Partiel",
             "tools": [], "usage": {}}
        """)
        assertEquals("stopped", tour.endReason)
    }

    @Test
    fun `le bouton arreter attend l'api 4`() {
        assertFalse(json.decodeFromString<GenfoxConfig>("""{"ok": true, "api": 3}""").canStop)
        assertTrue(json.decodeFromString<GenfoxConfig>("""{"ok": true, "api": 4}""").canStop)
        assertFalse("un serveur sans api", GenfoxConfig(ok = true).canStop)
    }
}
