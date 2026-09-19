package com.bluefoxconsultant.sms.ui.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conversion markdown-lite → HTML. Le point sensible n'est pas le gras : c'est
 * que le texte tapé reste du texte, y compris quand il ressemble à du balisage.
 *
 * Depuis #25764, le même balisage s'affiche rendu : l'essai qui compte le plus
 * est [ce qui s affiche est ce qui part], qui confronte l'analyse de l'écran
 * au HTML envoyé sur un corpus, portée par portée.
 */
class RichTextTest {

    private val vide = RichText.VIDE

    @Test
    fun `markup typed by hand stays text`() {
        val html = RichText.toHtml("Dis <b>bonjour</b> & au revoir")
        assertTrue(html.contains("&lt;b&gt;"))
        assertTrue(html.contains("&amp;"))
        assertFalse(html.contains("<b>"))
    }

    @Test
    fun `bold and italic become tags`() {
        assertTrue(RichText.toHtml("un **point** important").contains("<strong>point</strong>"))
        assertTrue(RichText.toHtml("un *mot* nuance").contains("<em>mot</em>"))
    }

    @Test
    fun `italic does not swallow bold`() {
        val html = RichText.toHtml("**gras** et *penche*")
        assertTrue(html.contains("<strong>gras</strong>"))
        assertTrue(html.contains("<em>penche</em>"))
    }

    @Test
    fun `bullets become a single list`() {
        val html = RichText.toHtml("Points :\n- un\n- deux\nFin")
        assertEquals(1, Regex("<ul>").findAll(html).count())
        assertEquals(2, Regex("<li>").findAll(html).count())
        assertTrue(html.contains("Fin"))
    }

    @Test
    fun `plain text reports no formatting`() {
        assertFalse(RichText.hasFormatting("Bonjour, ci-joint le rapport."))
        assertTrue(RichText.hasFormatting("Bonjour **Marie**"))
        assertTrue(RichText.hasFormatting("- un\n- deux"))
    }

    @Test
    fun `a line prefix toggles`() {
        val (added, _) = RichText.applyLinePrefix("un\ndeux", 4, "- ")
        assertTrue(added.contains("- deux"))
        val (removed, _) = RichText.applyLinePrefix(added, 6, "- ")
        assertFalse(removed.contains("- deux"))
    }

    @Test
    fun `out of range offsets do not crash`() {
        RichText.applyMarker("abc", -5, 99, "**")
        RichText.applyLinePrefix("abc", 99, "- ")
        RichText.basculer("abc", -5, 99, RichText.Style.GRAS)
        RichText.stylesActifs("abc", 99, -3)
        RichText.estPuce("abc", 99)
    }

    // ------------------------------------------------------------ l'écran

    @Test
    fun `les marqueurs se cachent et le contenu reste`() {
        val a = RichText.analyser("un **point** et *nuance*")
        assertEquals("un point et nuance", a.affiche)
        assertEquals(listOf(RichText.Style.GRAS, RichText.Style.ITALIQUE), a.portees.map { it.style })
    }

    @Test
    fun `une puce se dessine sans changer de longueur`() {
        val a = RichText.analyser("Liste :\n  - un\n- deux")
        assertEquals("Liste :\n  • un\n• deux", a.affiche)
    }

    @Test
    fun `des asterisques sans partenaire restent visibles`() {
        assertEquals("5 * 3 et **", RichText.analyser("5 * 3 et **").affiche)
    }

    @Test
    fun `les correspondances d indices vont dans les deux sens`() {
        val a = RichText.analyser("a **bc** d")
        // Visible : « a bc d ». Le « b » est le 3e caractère visible, à l'indice source 4.
        assertEquals(4, a.versSource[2])
        assertEquals(2, a.versAffiche[4])
        // Au bout du texte.
        assertEquals("a **bc** d".length, a.versSource[a.affiche.length])
        assertEquals(a.affiche.length, a.versAffiche["a **bc** d".length])
        // Monotone, jamais hors bornes.
        for (i in 1 until a.versAffiche.size) assertTrue(a.versAffiche[i] >= a.versAffiche[i - 1])
    }

    /**
     * Caractère par caractère : le style que l'ÉCRAN donne (analyse de la
     * source brute, comme `MiseEnFormeVisible`) contre celui du HTML envoyé
     * (après [RichText.nettoyer]). Relecture adverse du 2026-09-16 : l'essai
     * d'avant analysait le texte nettoyé des deux côtés, et `*a*[VIDE]*b*`
     * passait alors que le destinataire recevait « a**b ».
     */
    @Test
    fun `ce qui s affiche est ce qui part`() {
        val corpus = listOf(
            "un **point** important",
            "**gras** et *penche*",
            "*a **b** c*",
            "5 * 3 * 2",
            "**a*b**",
            "x ** y ** z",
            "- **un** point\n- *deux*\nfin **ouverte",
            "a${vide}b **$vide** c *$vide*",
            "*a*$vide*b*",
            "**a**$vide**b**",
            "*",
            "",
            "**a** **b**",
            "2 * 3 * 4 et *vrai*",
        )
        for (source in corpus) {
            assertEquals("« $source »", styleEcran(source), styleEnvoye(source))
        }
    }

    /** (caractère, gras, italique) de ce que l'écran montre, lignes aplaties. */
    private fun styleEcran(source: String): List<Triple<Char, Boolean, Boolean>> {
        val a = RichText.analyser(source)
        val out = mutableListOf<Triple<Char, Boolean, Boolean>>()
        var debutLigne = true
        for (i in source.indices) {
            if (a.cache[i]) continue
            val ch = source[i]
            if (ch == '\n') { debutLigne = true; continue }
            if (i in a.puces) { continue }
            val gras = a.portees.any { it.style == RichText.Style.GRAS && i >= it.contenuDebut && i < it.contenuFin }
            val ital = a.portees.any { it.style == RichText.Style.ITALIQUE && i >= it.contenuDebut && i < it.contenuFin }
            out += Triple(ch, gras, ital)
            debutLigne = false
        }
        return normaliser(out)
    }

    /** (caractère, gras, italique) du HTML envoyé. */
    private fun styleEnvoye(source: String): List<Triple<Char, Boolean, Boolean>> {
        val html = RichText.toHtml(source)
        val out = mutableListOf<Triple<Char, Boolean, Boolean>>()
        var gras = 0
        var ital = 0
        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                val fin = html.indexOf('>', i)
                when (html.substring(i + 1, fin).substringBefore(' ')) {
                    "strong" -> gras++
                    "/strong" -> gras--
                    "em" -> ital++
                    "/em" -> ital--
                }
                i = fin + 1
                continue
            }
            val (ch, pas) = when {
                html.startsWith("&amp;", i) -> '&' to 5
                html.startsWith("&lt;", i) -> '<' to 4
                html.startsWith("&gt;", i) -> '>' to 4
                else -> html[i] to 1
            }
            out += Triple(ch, gras > 0, ital > 0)
            i += pas
        }
        return normaliser(out)
    }

    /** Les espaces ne portent pas de style lisible, et le HTML rogne les lignes. */
    private fun normaliser(l: List<Triple<Char, Boolean, Boolean>>) =
        l.filter { !it.first.isWhitespace() && it.first != '•' }

    @Test
    fun `deux asterisques de calcul restent un calcul`() {
        assertEquals("2 * 3 * 4", RichText.analyser("2 * 3 * 4").affiche)
        assertFalse(RichText.toHtml("2 * 3 * 4").contains("<em>"))
    }

    // --------------------------------------------------------- nettoyage

    @Test
    fun `une paire restee vide ne part pas`() {
        assertEquals("Bonjour ", RichText.nettoyer("Bonjour **$vide**"))
        assertEquals("ab", RichText.nettoyer("a${vide}b"))
        assertEquals("<p style=\"margin:0 0 12px 0;\">Bonjour</p>", RichText.toHtml("Bonjour *$vide*"))
        assertFalse(RichText.hasFormatting("Bonjour **$vide**"))
    }

    // ------------------------------------------------------ barre d'outils

    @Test
    fun `gras sans selection pose une paire ou l on ecrit`() {
        val e = RichText.basculer("Bonjour ", 8, 8, RichText.Style.GRAS)
        assertEquals("Bonjour **$vide**", e.texte)
        assertEquals(11, e.selDebut)
        // Taper dedans écrit en gras.
        val tape = e.texte.substring(0, e.selDebut) + "Marie" + e.texte.substring(e.selDebut)
        assertEquals("Bonjour Marie", RichText.analyser(tape).affiche)
        assertTrue(RichText.toHtml(tape).contains("<strong>Marie</strong>"))
        assertEquals(setOf(RichText.Style.GRAS), RichText.stylesActifs(tape, e.selDebut + 5, e.selDebut + 5))
    }

    @Test
    fun `rappuyer sur une paire vide la retire`() {
        val pose = RichText.basculer("Bonjour ", 8, 8, RichText.Style.GRAS)
        val retire = RichText.basculer(pose.texte, pose.selDebut, pose.selDebut, RichText.Style.GRAS)
        assertEquals("Bonjour ", retire.texte)
        assertEquals(8, retire.selDebut)
    }

    @Test
    fun `italique sans selection ne se confond pas avec le gras`() {
        val e = RichText.basculer("", 0, 0, RichText.Style.ITALIQUE)
        val tape = e.texte.substring(0, e.selDebut) + "mot" + e.texte.substring(e.selDebut)
        assertTrue(RichText.toHtml(tape).contains("<em>mot</em>"))
        assertFalse(RichText.toHtml(tape).contains("strong"))
    }

    @Test
    fun `gras au bout d un mot gras en sort`() {
        val texte = "un **mot**"
        val e = RichText.basculer(texte, 8, 8, RichText.Style.GRAS)
        assertEquals(texte, e.texte)
        assertEquals(10, e.selDebut)
        assertEquals(emptySet<RichText.Style>(), RichText.stylesActifs(e.texte, e.selDebut, e.selDebut))
    }

    @Test
    fun `gras au milieu d un mot gras le coupe`() {
        val e = RichText.basculer("**abcd**", 4, 4, RichText.Style.GRAS)
        val tape = e.texte.substring(0, e.selDebut) + "X" + e.texte.substring(e.selDebut)
        val html = RichText.toHtml(tape)
        assertTrue(html, html.contains("<strong>ab</strong>"))
        assertTrue(html, html.contains("<strong>cd</strong>"))
        assertFalse(html, html.contains("<strong>X"))
    }

    @Test
    fun `une selection devient grasse puis redevient romaine`() {
        val g = RichText.basculer("bonjour tout le monde", 8, 12, RichText.Style.GRAS)
        assertEquals("bonjour **tout** le monde", g.texte)
        assertEquals("tout", g.texte.substring(g.selDebut, g.selFin))
        val r = RichText.basculer(g.texte, g.selDebut, g.selFin, RichText.Style.GRAS)
        assertEquals("bonjour tout le monde", r.texte)
        assertEquals("tout", r.texte.substring(r.selDebut, r.selFin))
    }

    @Test
    fun `sortir une partie d une portee la coupe proprement`() {
        val texte = "**abcdef**"
        for ((a, b) in listOf(2 to 4, 4 to 6, 6 to 8)) {
            val e = RichText.basculer(texte, a, b, RichText.Style.GRAS)
            val an = RichText.analyser(e.texte)
            assertEquals("abcdef", an.affiche)
            val sortie = texte.substring(a, b)
            assertEquals(sortie, e.texte.substring(e.selDebut, e.selFin))
            assertFalse("« $sortie » doit sortir du gras",
                RichText.stylesActifs(e.texte, e.selDebut, e.selFin).contains(RichText.Style.GRAS))
            assertFalse(RichText.toHtml(e.texte).contains("*"))
        }
    }

    @Test
    fun `styler une selection qui chevauche un gras ne l imbrique pas`() {
        val e = RichText.basculer("un **deux** trois", 0, 17, RichText.Style.GRAS)
        assertEquals("**un deux trois**", e.texte)
    }

    // ------------------------------------------------------------ frappe

    @Test
    fun `une frappe ordinaire ne demande aucune correction`() {
        assertNull(RichText.corrigerEdition("un **mot**", "un **mots**", 8))
        assertNull(RichText.corrigerEdition("abc", "abcd", 4))
        assertNull(RichText.corrigerEdition("abc", "ab", 2))
    }

    @Test
    fun `effacer un marqueur cache efface la lettre d avant`() {
        // Curseur affiché après « mot » : en source, derrière la fermeture.
        // Le clavier efface le « * » juste avant le curseur.
        val avant = "un **mot** fin"
        val apres = "un **mot* fin"
        val e = RichText.corrigerEdition(avant, apres, 9)!!
        assertEquals("un **mo** fin", e.texte)
        assertEquals("un mo fin", RichText.analyser(e.texte).affiche)
    }

    @Test
    fun `effacer une portee jusqu au bout ne laisse pas d asterisques`() {
        val e = RichText.corrigerEdition("un **a** b", "un **** b", 5)!!
        assertEquals("un  b", e.texte)
        assertEquals(3, e.selDebut)
    }

    @Test
    fun `une selection effacee a cheval sur un marqueur ne le laisse pas orphelin`() {
        // « ab **cd** ef » : on sélectionne « d ef » à l'écran et on efface.
        val avant = "ab **cd** ef"
        val apres = "ab **c"
        val e = RichText.corrigerEdition(avant, apres, 6)!!
        assertEquals("ab c", e.texte)
        assertFalse(RichText.analyser(e.texte).affiche.contains("*"))
    }

    @Test
    fun `des asterisques tapees exprès ne sont pas retirees`() {
        assertNull(RichText.corrigerEdition("5 ", "5 *", 3))
        assertNull(RichText.corrigerEdition("un **mot**", "un **mot***", 11))
    }

    @Test
    fun `entree sur une puce en ouvre une autre`() {
        val e = RichText.corrigerEdition("- un", "- un\n", 5)!!
        assertEquals("- un\n- ", e.texte)
        assertEquals(7, e.selDebut)
    }

    @Test
    fun `entree sur une puce vide ferme la liste`() {
        val e = RichText.corrigerEdition("- un\n- ", "- un\n- \n", 8)!!
        assertEquals("- un\n", e.texte)
        assertEquals(5, e.selDebut)
    }

    @Test
    fun `entree hors liste ne touche a rien`() {
        assertNull(RichText.corrigerEdition("un", "un\n", 3))
    }

    // ------------------------------------------- relecture adverse 09-16

    @Test
    fun `italique juste apres un italique rentre dedans au lieu de coller une paire`() {
        val e = RichText.basculer("*a*", 3, 3, RichText.Style.ITALIQUE)
        assertEquals("*a*", e.texte)
        val tape = e.texte.substring(0, e.selDebut) + "b" + e.texte.substring(e.selDebut)
        assertEquals("ab", RichText.analyser(tape).affiche)
        assertTrue(RichText.toHtml(tape).contains("<em>ab</em>"))
    }

    @Test
    fun `un italique coupe sans rien taper se recolle a l envoi`() {
        val e = RichText.basculer("*ab*", 2, 2, RichText.Style.ITALIQUE)
        assertEquals("ab", RichText.analyser(e.texte).affiche)
        assertTrue(RichText.toHtml(e.texte), RichText.toHtml(e.texte).contains("<em>ab</em>"))
        val rebascule = RichText.basculer(e.texte, e.selDebut, e.selDebut, RichText.Style.ITALIQUE)
        assertFalse(RichText.analyser(rebascule.texte).affiche.contains("*"))
        assertFalse(RichText.toHtml(rebascule.texte).contains("*"))
    }

    @Test
    fun `entree au bout d un mot gras continue en gras sur la ligne suivante`() {
        val arme = RichText.basculer("", 0, 0, RichText.Style.GRAS)
        val avant = arme.texte.substring(0, arme.selDebut) + "Important" + arme.texte.substring(arme.selDebut)
        val curseur = arme.selDebut + "Important".length
        val apres = avant.substring(0, curseur) + "\n" + avant.substring(curseur)
        val e = RichText.corrigerEdition(avant, apres, curseur + 1)!!
        assertEquals("Important\n", RichText.analyser(e.texte).affiche)
        val suite = e.texte.substring(0, e.selDebut) + "suite" + e.texte.substring(e.selDebut)
        val html = RichText.toHtml(suite)
        assertTrue(html, html.contains("<strong>Important</strong>"))
        assertTrue(html, html.contains("<strong>suite</strong>"))
        assertFalse(html, html.contains("*"))
    }

    @Test
    fun `entree au milieu d un gras sur une puce coupe proprement`() {
        val avant = "- **ab**"
        val apres = "- **a\nb**"
        val e = RichText.corrigerEdition(avant, apres, 6)!!
        assertEquals("- **a**\n- **b**", e.texte)
        assertEquals("• a\n• b", RichText.analyser(e.texte).affiche)
    }

    @Test
    fun `entree au bout d une puce suivie d une autre ligne ouvre une puce`() {
        val e = RichText.corrigerEdition("- a\n- b", "- a\n\n- b", 4)!!
        assertEquals("- a\n- \n- b", e.texte)
        assertEquals(6, e.selDebut)
    }

    @Test
    fun `la puce active est celle de la ligne du curseur seulement`() {
        assertFalse(RichText.estPuce("\n- x", 0))
        assertTrue(RichText.estPuce("\n- x", 2))
    }

    @Test
    fun `un asterisque tape apres un italique ne le detruit pas`() {
        assertNull(RichText.corrigerEdition("*a*", "*a**", 4))
    }

    @Test
    fun `une puce en retrait se retire au lieu de doubler`() {
        val (sans, _) = RichText.applyLinePrefix("  - x", 5, "- ")
        assertEquals("  x", sans)
    }

    @Test
    fun `retirer l italique d un mot selectionne au doigt marche du premier coup`() {
        // Le mot « gras » sélectionné : la fin tombe derrière l'astérisque fermante.
        val e = RichText.basculer("*gras* fin", 1, 6, RichText.Style.ITALIQUE)
        assertEquals("gras fin", e.texte)
        assertEquals(setOf(RichText.Style.ITALIQUE), RichText.stylesActifs("*gras* fin", 1, 6))
    }
}
