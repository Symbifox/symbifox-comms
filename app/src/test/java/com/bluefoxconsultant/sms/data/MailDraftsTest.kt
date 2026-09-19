package com.bluefoxconsultant.sms.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText

/**
 * Les brouillons, sans appareil.
 *
 * Ce qui compte ici n'est pas d'écrire un JSON, c'est la tenue de compte : un
 * même composeur ne doit laisser qu'UNE ligne quel que soit le nombre de fois
 * qu'on le quitte, un brouillon vidé doit disparaître de lui-même, et un
 * fichier illisible ne doit pas emporter l'écran avec lui.
 */
class MailDraftsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "drafts.json")
    private fun store() = MailDrafts(file())

    private fun draft(id: String, subject: String = "Objet", body: String = "Texte") =
        MailDraft(id = id, subject = subject, body = body, to = listOf("a@x.ca"))

    @Test
    fun `un brouillon survit au redemarrage`() {
        store().save(draft("d1", subject = "Devis"))

        val relu = store().drafts.value
        assertEquals(1, relu.size)
        assertEquals("Devis", relu.first().subject)
        assertEquals(listOf("a@x.ca"), relu.first().to)
    }

    @Test
    fun `sauver deux fois le meme composeur ne laisse qu'une ligne`() {
        val s = store()
        s.save(draft("d1", body = "premier jet"))
        s.save(draft("d1", body = "deuxieme jet"))

        assertEquals(1, s.drafts.value.size)
        assertEquals("deuxieme jet", s.drafts.value.first().body)
    }

    @Test
    fun `un brouillon vide s'efface au lieu de rester en coquille`() {
        val s = store()
        s.save(draft("d1"))
        assertEquals(1, s.drafts.value.size)

        // L'utilisateur a tout effacé puis quitté : « laisse tomber ».
        val kept = s.save(MailDraft(id = "d1"))

        assertNull("rien à garder", kept)
        assertTrue(s.drafts.value.isEmpty())
    }

    @Test
    fun `le plus recent passe devant`() {
        val s = store()
        s.save(draft("vieux", subject = "Vieux"))
        s.save(draft("neuf", subject = "Neuf"))

        assertEquals("Neuf", s.drafts.value.first().subject)
    }

    @Test
    fun `supprimer ne touche que la ligne visee`() {
        val s = store()
        s.save(draft("d1", subject = "Un"))
        s.save(draft("d2", subject = "Deux"))

        s.delete("d1")

        assertEquals(1, s.drafts.value.size)
        assertEquals("Deux", s.drafts.value.first().subject)
        assertNull(s.get("d1"))
        assertNotNull(s.get("d2"))
    }

    @Test
    fun `un fichier illisible degrade en liste vide, sans planter`() {
        file().parentFile?.mkdirs()
        file().writeText("{ pas du JSON")

        val s = store()
        assertTrue(s.drafts.value.isEmpty())

        // Et l'écriture suivante repart proprement plutôt que de rester coincée.
        s.save(draft("d1"))
        assertEquals(1, store().drafts.value.size)
    }

    @Test
    fun `les libelles d'un brouillon sont des ressources, le texte tape reste tel quel`() {
        val vide = MailDraft(id = "d1")
        assertEquals(UiText.Res(R.string.common_no_subject), vide.label)
        assertEquals(UiText.Res(R.string.common_no_recipient), vide.recipients)
        assertEquals(UiText.Res(R.string.mail_draft_kind_new), vide.kindLabel)
        assertEquals(UiText.Res(R.string.mail_draft_kind_reply_all), vide.copy(mode = "reply_all").kindLabel)
        // Sans objet, la première ligne non vide du corps tient lieu de titre.
        assertEquals(UiText.Raw("Bonjour"), vide.copy(body = "\n  Bonjour \nsuite").label)
    }

    @Test
    fun `le mode et le courriel d'origine survivent, sinon une reponse repart comme un message neuf`() {
        val s = store()
        s.save(MailDraft(id = "d1", mode = "reply", emailId = 4242, body = "ok"))

        val relu = store().get("d1")!!
        assertEquals("reply", relu.mode)
        assertEquals(4242, relu.emailId)
    }
}
