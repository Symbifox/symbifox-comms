package com.bluefoxconsultant.sms.push

import com.bluefoxconsultant.sms.data.RegisterPushRequest
import com.bluefoxconsultant.sms.data.RegisterPushResponse
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import com.google.crypto.tink.subtle.EllipticCurves
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Le push chiffré, du serveur jusqu'à la décision d'afficher (C-M3).
 *
 * ⚠️ Le vecteur est produit par le SERVEUR (http_ece, aes128gcm) et déchiffré
 * ici par la classe même que le connecteur 3.3.5 appelle dans
 * `DefaultKeyManager.decrypt`. S'il cesse de passer, c'est le contrat entre
 * les deux bords qui est rompu : on corrige un bord, pas l'essai.
 */
class PousseeChiffreeTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name.json")) {
            "missing fixture $name.json"
        }.bufferedReader().readText()

    private fun b64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    private fun vecteur() = json.parseToJsonElement(fixture("webpush_vecteur")).jsonObject

    /** Monté comme `DefaultKeyManager.decrypt` : clés typées, secret d'auth, rs par défaut. */
    private fun dechiffreur(): WebPushHybridDecrypt {
        val v = vecteur()
        val prive = EllipticCurves.getEcPrivateKey(
            EllipticCurves.CurveType.NIST_P256,
            b64url(v.getValue("recipient_private_key_b64url").jsonPrimitive.content),
        )
        val publique = EllipticCurves.getEcPublicKey(
            EllipticCurves.CurveType.NIST_P256,
            EllipticCurves.PointFormatType.UNCOMPRESSED,
            b64url(v.getValue("recipient_public_key_b64url").jsonPrimitive.content),
        )
        return WebPushHybridDecrypt.Builder()
            .withAuthSecret(b64url(v.getValue("auth_b64url").jsonPrimitive.content))
            .withRecipientPublicKey(publique)
            .withRecipientPrivateKey(prive)
            .build()
    }

    @Test
    fun `le vecteur du serveur se dechiffre avec la bibliotheque du connecteur`() {
        val v = vecteur()
        val clair = dechiffreur().decrypt(
            b64url(v.getValue("ciphertext_b64url").jsonPrimitive.content),
            null,
        )
        val attendu = v.getValue("plaintext_utf8").jsonPrimitive.content
        // Octet pour octet, accents et coche compris : un écart d'encodage
        // passerait un test sur les chaînes et casserait l'affichage.
        assertArrayEquals(attendu.toByteArray(Charsets.UTF_8), clair)
        val obj = json.parseToJsonElement(String(clair, Charsets.UTF_8)).jsonObject
        assertEquals("sms", obj.getValue("type").jsonPrimitive.content)
        assertEquals("Bonjour é ✓", obj.getValue("body").jsonPrimitive.content)
    }

    @Test
    fun `un chiffre altere est refuse, le connecteur le passera en clair`() {
        val v = vecteur()
        val chiffre = b64url(v.getValue("ciphertext_b64url").jsonPrimitive.content)
        chiffre[chiffre.size - 3] = (chiffre[chiffre.size - 3].toInt() xor 0x01).toByte()
        try {
            dechiffreur().decrypt(chiffre, null)
            fail("un chiffré altéré ne doit pas se déchiffrer")
        } catch (e: GeneralSecurityException) {
            // attendu : `PushMessage(message, decrypted = false)` côté connecteur
        }
    }

    @Test
    fun `les cles du vecteur ont le format du contrat`() {
        val v = vecteur()
        assertEquals(65, b64url(v.getValue("recipient_public_key_b64url").jsonPrimitive.content).size)
        assertEquals(16, b64url(v.getValue("auth_b64url").jsonPrimitive.content).size)
    }

    // ── La décision ──────────────────────────────────────────────────

    private val smsNeuf = setOf("sms", "clear", "clear_all", "genfox", "call")
    private val courrielNeuf = setOf("mail", "mail_clear", "mail_clear_all")

    @Test
    fun `un message dechiffre passe toujours`() {
        assertTrue(accepterPoussee(true, "sms", listOf(smsNeuf, courrielNeuf)))
        assertTrue(accepterPoussee(true, null, listOf(smsNeuf)))
    }

    @Test
    fun `serveurs anciens, le clair passe comme avant`() {
        assertTrue(accepterPoussee(false, "sms", listOf(emptySet(), emptySet())))
        assertTrue(accepterPoussee(false, "mail", emptyList()))
    }

    @Test
    fun `un type chiffre qui arrive en clair est forge`() {
        assertFalse(accepterPoussee(false, "sms", listOf(smsNeuf, emptySet())))
        assertFalse(accepterPoussee(false, "call", listOf(emptySet(), smsNeuf)))
        assertFalse(accepterPoussee(false, "mail_clear_all", listOf(emptySet(), courrielNeuf)))
    }

    @Test
    fun `un seul serveur a jour ne bloque pas le clair de l'autre`() {
        // Messages à jour, courriel ancien : le courriel en clair passe encore.
        assertTrue(accepterPoussee(false, "mail", listOf(smsNeuf, emptySet())))
        // Module softphone pas à jour : « call » n'est pas annoncé, il passe.
        assertTrue(accepterPoussee(false, "call", listOf(smsNeuf - "call", courrielNeuf)))
    }

    @Test
    fun `un clair sans type ne passe pas`() {
        assertFalse(accepterPoussee(false, null, emptyList()))
        assertFalse(accepterPoussee(false, "", listOf(emptySet())))
    }

    @Test
    fun `seule une reponse webpush vraie retient des types`() {
        val neuf = json.decodeFromString<RegisterPushResponse>(
            """{"ok": true, "webpush": true, "webpush_types": ["sms", "clear", ""]}""",
        )
        assertEquals(setOf("sms", "clear"), typesChiffres(neuf))
        val ancien = json.decodeFromString<RegisterPushResponse>("""{"ok": true}""")
        assertEquals(emptySet<String>(), typesChiffres(ancien))
        val sansCles = json.decodeFromString<RegisterPushResponse>(
            """{"ok": true, "webpush": false, "webpush_types": ["sms"]}""",
        )
        assertEquals(emptySet<String>(), typesChiffres(sansCles))
    }

    @Test
    fun `l'inscription porte les cles, et rien quand il n'y en a pas`() {
        val avec = json.encodeToString(
            RegisterPushRequest.serializer(),
            RegisterPushRequest("https://ntfy.example/up1", "2.42.0", "BJR4", "Kilc"),
        )
        assertEquals(
            """{"endpoint":"https://ntfy.example/up1","app_version":"2.42.0","p256dh":"BJR4","auth":"Kilc"}""",
            avec,
        )
        val sans = json.encodeToString(
            RegisterPushRequest.serializer(),
            RegisterPushRequest("https://ntfy.example/up1", "2.42.0"),
        )
        assertEquals("""{"endpoint":"https://ntfy.example/up1","app_version":"2.42.0"}""", sans)
    }

    // ── Le distributeur ──────────────────────────────────────────────

    @Test
    fun `le distributeur retenu reste s'il est installe`() {
        assertEquals(
            DecisionDistributeur.Utiliser("org.b"),
            deciderDistributeur("org.b", "org.a", listOf("org.a", "org.b")),
        )
    }

    @Test
    fun `un distributeur retenu puis desinstalle cede au defaut`() {
        assertEquals(
            DecisionDistributeur.Utiliser("org.a"),
            deciderDistributeur("org.gone", "org.a", listOf("org.a", "org.b")),
        )
    }

    @Test
    fun `un seul installe est pris sans question`() {
        assertEquals(
            DecisionDistributeur.Utiliser("io.heckel.ntfy"),
            deciderDistributeur(null, null, listOf("io.heckel.ntfy")),
        )
    }

    @Test
    fun `plusieurs sans defaut, on demande et on ne prend jamais le premier`() {
        assertEquals(
            DecisionDistributeur.Demander(listOf("org.a", "org.b")),
            deciderDistributeur(null, null, listOf("org.a", "org.b", "org.a")),
        )
    }

    @Test
    fun `aucun installe`() {
        assertEquals(DecisionDistributeur.Aucun, deciderDistributeur(null, null, emptyList()))
    }
}
