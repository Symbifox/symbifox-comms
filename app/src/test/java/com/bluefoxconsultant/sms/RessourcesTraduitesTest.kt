package com.bluefoxconsultant.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Les deux langues de l'app disent la même chose, avec les mêmes trous.
 *
 * `values/strings.xml` est l'anglais, servi à toute langue non couverte ;
 * `values-fr` le français (Q-M8, audit du 2026-09-08). Une clé présente d'un
 * seul côté ne casse pas le build : Android retombe sur l'anglais, et c'est
 * justement l'interface mixte que ce lot a retirée. Un argument de format qui
 * diffère, lui, lève une exception au moment d'afficher, sur le seul téléphone
 * réglé dans cette langue. Rien de tout ça ne se voit sans le chercher.
 *
 * Lu comme du texte et non par un analyseur XML : ce sont les échappements
 * bruts (`\'`, `\"`) que aapt exige, et un analyseur les rendrait invisibles.
 */
class RessourcesTraduitesTest {

    private val res: File = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { it.isDirectory }

    private data class Ressources(
        val chaines: Map<String, String>,
        val pluriels: Map<String, Map<String, String>>,
    )

    private fun lire(dossier: String): Ressources {
        val texte = File(res, "$dossier/strings.xml").readText()
        val chaines = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(texte).associate { it.groupValues[1] to it.groupValues[2] }
        val pluriels = Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(texte).associate { bloc ->
                bloc.groupValues[1] to Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(bloc.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            }
        return Ressources(chaines, pluriels)
    }

    private val anglais = lire("values")
    private val francais = lire("values-fr")

    /** Les arguments de format, triés : `%1$s`, `%2$d`… */
    private fun arguments(texte: String): List<String> =
        Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[a-zA-Z%]""").findAll(texte)
            .map { it.value }.filter { it != "%%" }.sorted().toList()

    private fun difference(a: Set<String>, b: Set<String>): Set<String> = (a - b) + (b - a)

    @Test
    fun `chaque cle existe dans les deux langues`() {
        assertEquals(
            "chaînes d'un seul côté",
            emptySet<String>(),
            difference(anglais.chaines.keys, francais.chaines.keys),
        )
        assertEquals(
            "pluriels d'un seul côté",
            emptySet<String>(),
            difference(anglais.pluriels.keys, francais.pluriels.keys),
        )
    }

    @Test
    fun `les arguments de format sont les memes des deux cotes`() {
        val ecarts = anglais.chaines.mapNotNull { (cle, en) ->
            val fr = francais.chaines[cle] ?: return@mapNotNull null
            if (arguments(en) != arguments(fr)) "$cle : ${arguments(en)} / ${arguments(fr)}" else null
        }
        assertEquals(emptyList<String>(), ecarts)
    }

    @Test
    fun `un pluriel a one et other, et chaque forme porte les memes arguments`() {
        val ecarts = mutableListOf<String>()
        anglais.pluriels.forEach { (cle, formesEn) ->
            val formesFr = francais.pluriels[cle] ?: return@forEach
            val reference = arguments(formesEn["other"].orEmpty())
            for ((langue, formes) in listOf("en" to formesEn, "fr" to formesFr)) {
                if ("one" !in formes || "other" !in formes) ecarts += "$cle ($langue) : one/other manquant"
                formes.forEach { (quantite, texte) ->
                    if (arguments(texte) != reference) ecarts += "$cle ($langue, $quantite) : ${arguments(texte)}"
                    // Sans l'argument, « one » se lit faux là où il couvre 0 et 1.
                    if (reference.isEmpty()) ecarts += "$cle : pluriel sans argument"
                }
            }
        }
        assertEquals(emptyList<String>(), ecarts)
    }

    @Test
    fun `plusieurs arguments sont toujours numerotes`() {
        // « %s %s » devient faux dès qu'une langue inverse l'ordre des mots.
        val fautifs = listOf("en" to anglais.chaines, "fr" to francais.chaines).flatMap { (langue, chaines) ->
            chaines.filter { (_, texte) ->
                val args = arguments(texte)
                args.size > 1 && args.any { !it.matches(Regex("""%\d+\$.*""")) }
            }.keys.map { "$it ($langue)" }
        }
        assertEquals(emptyList<String>(), fautifs)
    }

    @Test
    fun `apostrophes et guillemets sont echappes pour aapt`() {
        val bruts = (anglais.chaines.values + francais.chaines.values +
            (anglais.pluriels.values + francais.pluriels.values).flatMap { it.values })
        val fautifs = bruts.filter { Regex("""(?<!\\)['"]""").containsMatchIn(it) }
        assertTrue("non échappés : $fautifs", fautifs.isEmpty())
    }
}
