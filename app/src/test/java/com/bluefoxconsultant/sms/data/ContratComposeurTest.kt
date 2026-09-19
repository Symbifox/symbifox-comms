package com.bluefoxconsultant.sms.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le contrat du composeur deuxième version, lu sur le SERVEUR (#25764).
 *
 * Les charges utiles ci-dessous ont été relevées le 2026-09-16 sur le banc
 * `t25764` (bf_email_management 18.0.11.37.0), avec un vrai jeton d'appareil :
 * ce ne sont pas des exemples tapés à la main. Un champ renommé d'un côté fait
 * tomber cet essai, pas l'app de quelqu'un.
 */
class ContratComposeurTest {

    // Même configuration que ApiClient : c'est elle qui lit et écrit sur le fil.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val config = """{"user_name": "Personne Essai", "accounts": [{"id": 236, "name": "Banc — essai@banc.test", "login": "essai@banc.test", "aliases": "", "state": "connected", "color": "#29ABE2"}, {"id": 237, "name": "Seconde boîte — essai@second.banc", "login": "essai@second.banc", "aliases": "", "state": "connected", "color": "#E11D48"}], "compose_api": 2, "identities": [{"id": 351, "name": "Personne Essai", "email": "essai@banc.test", "is_default": true, "account_id": 236, "signature_text": "Personne Essai\nOrganisation (banc)"}, {"id": 352, "name": "Essai (seconde boîte)", "email": "essai@second.banc", "is_default": false, "account_id": 237, "signature_text": "Essai\nSeconde boîte, signature du banc"}], "recipient_groups": true, "server_drafts": true}"""
    private val prepare = """{"mode": "reply_all", "to": [{"name": "Doe, Jane", "email": "jane@client.test"}], "cc": [{"name": "Associ\u00e9 Client", "email": "associe@client.test"}, {"name": "", "email": "comptable@client.test"}], "subject": "Re: Question sur la soumission", "identity_id": 351, "record": false}"""
    private val groupes = """{"contacts": [{"id": 2159, "name": "\u00c9quipe banc", "email": "", "company": "", "is_group": true, "field": "bcc", "members": [{"id": 2157, "name": "Julie Membre", "email": "julie@equipe.test"}, {"id": 2158, "name": "Karim Membre", "email": "karim@equipe.test"}]}]}"""

    @Test
    fun `la configuration annonce le composeur et les adresses`() {
        val c = json.decodeFromString<MailConfig>(config)
        assertTrue(c.composeurComplet)
        assertTrue(c.recipientGroups)
        assertEquals(2, c.identities.size)
        val defaut = c.identities.single { it.isDefault }
        assertEquals("essai@banc.test", defaut.email)
        assertTrue(defaut.accountId != null && defaut.accountId!! > 0)
        assertTrue(c.identities.any { it.signatureText.contains("Seconde boîte") })
    }

    @Test
    fun `une instance ancienne se lit sans composeur complet`() {
        val ancienne = json.decodeFromString<MailConfig>("""{"user_name":"X","accounts":[]}""")
        assertFalse(ancienne.composeurComplet)
        assertTrue(ancienne.identities.isEmpty())
    }

    @Test
    fun `une reponse preparee se lit, fiche absente comprise`() {
        val p = json.decodeFromString<ReplyPrepareResponse>(prepare)
        assertEquals("Re: Question sur la soumission", p.subject)
        assertEquals("\"Doe, Jane\" <jane@client.test>", p.to.single().enPastille)
        assertEquals(listOf("associe@client.test", "comptable@client.test"), p.cc.map { it.email })
        assertTrue(p.identityId != null)
        assertNull(p.record)
    }

    @Test
    fun `un groupe arrive deplie avec son champ`() {
        val g = json.decodeFromString<MailContactsResponse>(groupes).contacts.single()
        assertTrue(g.isGroup)
        assertEquals("bcc", g.field)
        assertEquals(2, g.members.size)
    }

    @Test
    fun `une reponse part avec les noms de champs du serveur`() {
        val corps = json.encodeToString(MailReplyRequest(
            emailId = 1, mode = "reply", body = "<p>x</p>", bodyIsHtml = true,
            to = listOf("a@b.c"), bcc = listOf("d@e.f"), subject = "Objet",
            identityId = 5, scheduledMs = 1789646400000L,
        ))
        val o = json.parseToJsonElement(corps).jsonObject
        for (cle in listOf("email_id", "mode", "body", "body_is_html", "to", "bcc",
                           "subject", "identity_id", "scheduled_ms")) {
            assertTrue("$cle absent de $corps", cle in o)
        }
        assertFalse("un champ nul ne voyage pas", "cc" in o)
    }

    @Test
    fun `une reponse a un serveur ancien n emporte aucun champ neuf`() {
        val o: JsonObject = json.parseToJsonElement(json.encodeToString(
            MailReplyRequest(emailId = 1, mode = "reply", body = "x"))).jsonObject
        for (cle in listOf("bcc", "subject", "identity_id", "scheduled_ms")) {
            assertFalse("$cle ne doit pas partir", cle in o)
        }
    }

    @Test
    fun `un courriel neuf classe sur une fiche part avec res_model et res_id`() {
        val o = json.parseToJsonElement(json.encodeToString(MailComposeRequest(
            to = listOf("a@b.c"), subject = "s", body = "b",
            resModel = "project.task", resId = 59, identityId = 351,
        ))).jsonObject
        assertEquals("\"project.task\"", o["res_model"].toString())
        assertEquals("59", o["res_id"].toString())
        assertEquals("351", o["identity_id"].toString())
    }
}
