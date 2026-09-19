package com.bluefoxconsultant.sms.socle

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Le socle visuel, éprouvé (BF #25765).
 *
 * ⚠️ **Ce fichier est IDENTIQUE dans les quatre applications Compose**, comme
 * `Socle.kt` lui-même : seule la ligne `package` change. Il est là parce que la
 * règle qu'il garde est exactement celle qu'une session pressée « corrigerait ».
 */
class SocleTest {

    /**
     * 🔴 **Blanc sur le bleu Blue Fox, et c'est une DÉCISION.**
     *
     * `#29ABE2` avec du blanc rend 2,62:1, et avec l'encre 6,94:1. Olivier a
     * tranché pour le blanc le 2026-09-13 après avoir vu les quatre options
     * rendues : l'encre a été écartée comme « weird with black text ». Un seuil
     * calculé sur le meilleur contraste bascule à 0,196 de luminance et
     * renverrait l'encre — c'est pour ça que le seuil du socle est à 0,45.
     *
     * Si cet essai tombe, ce n'est pas le seuil qu'il faut ajuster : c'est la
     * décision qu'il faut rouvrir avec Olivier.
     */
    @Test
    fun `le bleu Blue Fox porte du blanc`() {
        assertEquals(Color.White, Symbifox.surCouleur(Color(0xFF29ABE2)))
    }

    /** Le bleu du produit aussi, et lui passe AA à 4,7:1. */
    @Test
    fun `le bleu Symbifox porte du blanc`() {
        assertEquals(Color.White, Symbifox.surCouleur(Color(0xFF176CF2)))
    }

    /**
     * 🔴 Le cas qui justifie le calcul : une marque pâle reçoit de l'ENCRE.
     *
     * Comms figeait `onPrimary` à blanc et rendait donc ses boutons illisibles
     * chez un locataire à marque claire. Blanc sur `#F2C744` rend 1,61:1.
     */
    @Test
    fun `une marque pale porte de l encre`() {
        assertEquals(Symbifox.ENCRE, Symbifox.surCouleur(Color(0xFFF2C744)))
        assertEquals(Symbifox.ENCRE, Symbifox.surCouleur(Color.White))
    }

    /**
     * 🔴 La luminance est celle de la NORME, pas la moyenne des canaux bruts.
     *
     * Les deux ne répondent pas pareil : `#29ABE2` pèse 0,351 normalisé et
     * 0,578 brut. Tokens et Chronomètre employaient la seconde au seuil 0,55 et
     * écrivaient donc en encre là où Pastilles écrivait en blanc — deux
     * applications sœurs, le même téléphone, la même instance.
     */
    @Test
    fun `la luminance est linearisee et pas brute`() {
        val bf = Color(0xFF29ABE2)
        val brute = 0.2126f * bf.red + 0.7152f * bf.green + 0.0722f * bf.blue
        assertEquals(0.578f, brute, 0.002f)          // ce que l'ancienne formule voyait
        assertEquals(Color.White, Symbifox.surCouleur(bf))  // ce que le socle répond
    }

    /**
     * 🔴 Aucun rôle de Material 3 ne reste au défaut : ils sont MAUVES, et le
     * mauve ressort là où personne n'a pensé à regarder.
     */
    @Test
    fun `aucun conteneur ne reste mauve`() {
        val defaut = androidx.compose.material3.lightColorScheme()
        for (sombre in listOf(false, true)) {
            val s = Symbifox.schema(Symbifox.BLEU, Symbifox.BLEU_APPOINT, sombre)
            assertNotEquals(defaut.secondaryContainer, s.secondaryContainer)
            assertNotEquals(defaut.tertiaryContainer, s.tertiaryContainer)
            assertNotEquals(defaut.surfaceVariant, s.surfaceVariant)
            assertEquals(s.surfaceVariant, s.secondaryContainer)
            assertEquals(Symbifox.surCouleur(Symbifox.BLEU), s.onPrimary)
        }
    }

    /**
     * ⚠️ En sombre, le fond est un neutre TEINTÉ de l'accent, jamais l'accent.
     * Un fond pris dans la couleur de marque donne un écran saturé.
     */
    @Test
    fun `le fond sombre reste neutre`() {
        val s = Symbifox.schema(Symbifox.BLEU, Symbifox.BLEU_APPOINT, sombre = true)
        assertNotEquals(Symbifox.BLEU, s.background)
        // Proche du neutre de départ, pas de l'accent.
        val ecartNeutre = ecart(s.background, Color(0xFF121518))
        val ecartAccent = ecart(s.background, Symbifox.BLEU)
        assert(ecartNeutre < ecartAccent / 4) { "fond=$ecartNeutre accent=$ecartAccent" }
    }

    private fun ecart(a: Color, b: Color) =
        Math.abs(a.red - b.red) + Math.abs(a.green - b.green) + Math.abs(a.blue - b.blue)
}
