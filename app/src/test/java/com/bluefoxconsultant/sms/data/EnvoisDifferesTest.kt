package com.bluefoxconsultant.sms.data

import com.bluefoxconsultant.sms.network.ApiException
import com.bluefoxconsultant.sms.ui.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * « Annuler l'envoi », et ce que la file hors ligne perdait (#25764).
 *
 * L'horloge est un compteur qu'on avance à la main : les délais se vérifient
 * sans attendre dix secondes, et sans qu'une machine lente fasse passer un
 * essai pour la mauvaise raison.
 */
class EnvoisDifferesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var maintenant = 1_000_000L
    private val envoyes = mutableListOf<PendingAction>()
    private var echec: Throwable? = null
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun fin() = portee.cancel()

    private val outbox by lazy { MailOutbox(File(tmp.root, "outbox.json")) }
    private val drafts by lazy { MailDrafts(File(tmp.root, "drafts.json")) }
    private val envois by lazy {
        EnvoisDifferes(outbox, drafts, { action ->
            echec?.let { throw it }
            envoyes += action
        }, portee, horloge = { maintenant })
    }

    private fun envoiComplet(token: String = "t1") = PendingAction(
        token = token,
        kind = PendingAction.KIND_REPLY,
        createdMs = 1L,
        emailId = 42,
        mode = "reply_all",
        body = "<p><strong>Bonjour</strong></p>",
        bodyIsHtml = true,
        bodySource = "**Bonjour**",
        subject = "Re: Question",
        to = listOf("\"Doe, Jane\" <jane@client.test>"),
        cc = listOf("copie@client.test"),
        bcc = listOf("cache@client.test"),
        attachments = listOf(StagedUpload(ok = true, attachmentId = 7, name = "devis.pdf", size = 1234)),
        identityId = 5,
        recipientsPrepared = true,
    )

    @Test
    fun `un envoi en attente ne part pas avant son delai`() = runBlocking {
        outbox.enqueue(envoiComplet().copy(notBeforeMs = maintenant + 10_000))
        assertEquals(0, envois.vider())
        assertTrue(envoyes.isEmpty())
        maintenant += 10_000
        assertEquals(1, envois.vider())
        assertEquals(listOf("t1"), envoyes.map { it.token })
    }

    @Test
    fun `une action due passe devant un envoi qui attend`() = runBlocking {
        outbox.enqueue(envoiComplet("attend").copy(notBeforeMs = maintenant + 10_000))
        outbox.enqueue(PendingAction(token = "lu", kind = PendingAction.KIND_MARK_READ, createdMs = 2L, emailIds = listOf(1)))
        envois.vider()
        assertEquals(listOf("lu"), envoyes.map { it.token })
        assertEquals(listOf("attend"), outbox.peek().map { it.token })
    }

    @Test
    fun `annuler rend le brouillon avec tout ce qu il portait`() = runBlocking {
        envois.programmer(envoiComplet(), 60_000)
        val brouillon = envois.annuler("t1")
        assertNotNull(brouillon)
        brouillon!!
        assertTrue(outbox.peek().isEmpty())
        assertEquals("reply_all", brouillon.mode)
        assertEquals(42, brouillon.emailId)
        assertEquals("**Bonjour**", brouillon.body)
        assertEquals(listOf("\"Doe, Jane\" <jane@client.test>"), brouillon.to)
        assertEquals(listOf("copie@client.test"), brouillon.cc)
        assertEquals(listOf("cache@client.test"), brouillon.bcc)
        assertEquals(listOf(7), brouillon.attachments.map { it.attachmentId })
        assertEquals(5, brouillon.identityId)
        assertTrue(brouillon.recipientsPrepared)
        assertEquals(brouillon, drafts.get(brouillon.id)?.copy(savedMs = brouillon.savedMs))
        maintenant += 60_000
        envois.vider()
        assertTrue("annulé ne part pas", envoyes.isEmpty())
    }

    @Test
    fun `annuler un envoi deja parti ne rend rien et ne rouvre rien`() = runBlocking {
        envois.programmer(envoiComplet(), 1_000)
        maintenant += 1_000
        envois.vider()
        assertNull(envois.annuler("t1"))
        assertEquals(1, envoyes.size)
        assertTrue(drafts.drafts.value.isEmpty())
    }

    @Test
    fun `annuler pendant que l envoi est en vol attend son issue`() = runBlocking {
        val entre = CompletableDeferred<Unit>()
        val porte = CompletableDeferred<Unit>()
        val lent = EnvoisDifferes(outbox, drafts, { entre.complete(Unit); porte.await() },
            portee, horloge = { maintenant })
        lent.programmer(envoiComplet(), 0)
        val vidange = launch(Dispatchers.Default) { lent.vider() }
        entre.await()
        val annulation = async(Dispatchers.Default) { lent.annuler("t1") }
        // Sans le verrou, l'annulation rendrait tout de suite un brouillon d'un
        // message en train de partir : elle doit ATTENDRE l'issue de l'envoi.
        delay(300)
        assertFalse("l'annulation n'attend pas l'envoi en vol", annulation.isCompleted)
        porte.complete(Unit)
        vidange.join()
        assertNull("le message est parti pendant l'annulation", annulation.await())
        assertTrue(drafts.drafts.value.isEmpty())
        assertEquals("le bandeau le dit", EnvoisDifferes.Issue.TropTard, lent.issue.value)
    }

    @Test
    fun `annuler pendant une vidange lente l emporte sur l echeance`() = runBlocking {
        val entre = CompletableDeferred<Unit>()
        val porte = CompletableDeferred<Unit>()
        val envoyesIci = mutableListOf<String>()
        val lent = EnvoisDifferes(outbox, drafts, { action ->
            if (action.kind == PendingAction.KIND_MARK_READ) { entre.complete(Unit); porte.await() }
            envoyesIci += action.token
        }, portee, horloge = { maintenant })
        outbox.enqueue(PendingAction(token = "lu", kind = PendingAction.KIND_MARK_READ, createdMs = 1L, emailIds = listOf(1)))
        lent.programmer(envoiComplet(), 10_000)
        val vidange = launch(Dispatchers.Default) { lent.vider() }
        entre.await()
        val annulation = async(Dispatchers.Default) { lent.annuler("t1") }
        delay(200)
        // L'échéance tombe PENDANT que la vidange attend le serveur.
        maintenant += 10_000
        porte.complete(Unit)
        vidange.join()
        val brouillon = annulation.await()
        assertEquals(listOf("lu"), envoyesIci)
        assertTrue("annulé à temps, il doit revenir en brouillon", brouillon != null)
    }

    @Test
    fun `un second appui sur annuler ne dit pas trop tard`() = runBlocking {
        envois.programmer(envoiComplet(), 60_000)
        assertNotNull(envois.annuler("t1"))
        assertNull(envois.annuler("t1"))
        assertNull(envois.issue.value)
    }

    @Test
    fun `un refus n est pas efface par un envoi parti juste apres`() = runBlocking {
        echec = ApiException(400, "Refusé.")
        envois.programmer(envoiComplet("a"), 1_000)
        maintenant += 1_000
        envois.vider()
        echec = null
        envois.programmer(envoiComplet("b"), 1_000)
        maintenant += 1_000
        envois.vider()
        assertTrue(envois.issue.value is EnvoisDifferes.Issue.Refuse)
    }

    @Test
    fun `au demarrage les envois laisses en file reprennent`() = runBlocking {
        // Un processus mort pendant le délai : la file porte l'envoi, rien
        // d'autre ne s'en souvient.
        outbox.enqueue(envoiComplet("attend").copy(notBeforeMs = maintenant + 5_000))
        outbox.enqueue(envoiComplet("du").copy(notBeforeMs = maintenant - 1))
        envois.reprendre()
        assertEquals(listOf("du"), envoyes.map { it.token })
        assertEquals(listOf("attend"), envois.enAttente.value.map { it.token })
    }

    @Test
    fun `un envoi refuse redevient un brouillon au lieu de disparaitre`() = runBlocking {
        echec = ApiException(400, "Aucun destinataire résolu.")
        envois.programmer(envoiComplet(), 1_000)
        maintenant += 1_000
        envois.vider()
        assertTrue(outbox.peek().isEmpty())
        val issue = envois.issue.value
        assertTrue(issue is EnvoisDifferes.Issue.Refuse)
        issue as EnvoisDifferes.Issue.Refuse
        assertEquals(UiText.Raw("Aucun destinataire résolu."), issue.raison)
        val garde = drafts.get(issue.brouillonId)
        assertNotNull(garde)
        assertEquals("**Bonjour**", garde!!.body)
        assertTrue("pas de « abandonné » en plus", outbox.drainFailures().isEmpty())
    }

    @Test
    fun `hors ligne l envoi reste en file et le bandeau le dit`() = runBlocking {
        echec = IOException("réseau")
        envois.programmer(envoiComplet(), 1_000)
        maintenant += 1_000
        envois.vider()
        assertEquals(listOf("t1"), outbox.peek().map { it.token })
        assertEquals(EnvoisDifferes.Issue.HorsLigne, envois.issue.value)
        assertTrue(envois.enAttente.value.isEmpty())
    }

    @Test
    fun `parti se dit une fois`() = runBlocking {
        envois.programmer(envoiComplet(), 1_000)
        maintenant += 1_000
        envois.vider()
        assertEquals(EnvoisDifferes.Issue.Parti, envois.issue.value)
        envois.consommerIssue()
        assertNull(envois.issue.value)
    }

    @Test
    fun `la file garde tout ce que l envoi portait`() {
        outbox.enqueue(envoiComplet())
        val relu = MailOutbox(File(tmp.root, "outbox.json")).peek().single()
        assertEquals(envoiComplet(), relu)
    }

    @Test
    fun `une action ancienne sans les champs neufs se relit`() {
        File(tmp.root, "outbox.json").writeText(
            """[{"token":"vieux","kind":"reply","created_ms":1,"email_id":3,"mode":"reply","body":"Salut","cc":["a@b.c"]}]""",
        )
        val relu = MailOutbox(File(tmp.root, "outbox.json")).peek().single()
        assertEquals("vieux", relu.token)
        assertEquals(0L, relu.notBeforeMs)
        assertFalse(relu.bodyIsHtml)
        assertTrue(relu.attachments.isEmpty())
        assertEquals("Salut", brouillonDepuisEnvoi(relu).body)
    }
}
