package com.bluefoxconsultant.sms.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Lire une pièce jointe sans jamais dépasser un plafond.
 *
 * 🔴 Le défaut réparé (audit du 2026-09-08, Q-M4 et C-F4) : `readBytes()` sur
 * l'URI d'une autre app, sans borne. Une vidéo de 2 Go partagée vers Comms
 * était chargée entière en mémoire avant que quiconque parle de taille, et
 * l'`OutOfMemoryError` — une `Error`, pas une `Exception` — traversait les
 * `catch` et fermait l'app. Un fournisseur malveillant qui annonce 10 ko et en
 * sert dix milliards avait le même effet.
 *
 * Deux gardes, dans cet ordre :
 * 1. la taille ANNONCÉE (`OpenableColumns.SIZE`), quand le fournisseur en donne
 *    une, refuse avant d'ouvrir le flux ;
 * 2. un compteur pendant la lecture refuse dès que le plafond est franchi,
 *    parce qu'une taille annoncée n'engage personne.
 */
object LectureBornee {

    /** Assez pour que le coût d'un appel système ne domine pas, assez petit pour ne rien peser. */
    private const val BLOC = 64 * 1024

    class TropGros(val plafond: Long) : Exception("too_large")

    /** Le téléphone n'a pas la mémoire qu'il faut ; dit comme tel, sans fermer l'app. */
    class MemoireInsuffisante : Exception("out_of_memory")

    /**
     * Lit [entree] en entier, ou lève [TropGros] dès que [plafond] est franchi.
     *
     * @param tailleAnnoncee ce que le fournisseur prétend, ou `null`/négatif
     *   s'il ne dit rien. Sert à refuser tôt et à dimensionner le tampon —
     *   jamais au-delà du plafond, pour qu'une annonce mensongère ne fasse pas
     *   allouer ce qu'on s'interdit de lire.
     */
    fun lire(entree: InputStream, plafond: Long, tailleAnnoncee: Long?): ByteArray {
        require(plafond >= 0)
        if (tailleAnnoncee != null && tailleAnnoncee > plafond) throw TropGros(plafond)
        try {
            val capacite = when {
                tailleAnnoncee != null && tailleAnnoncee >= 0 -> tailleAnnoncee
                else -> BLOC.toLong()
            }.coerceAtMost(plafond).toInt()
            val tampon = TamponExpose(capacite)
            val bloc = ByteArray(BLOC)
            var total = 0L
            while (true) {
                val n = entree.read(bloc)
                if (n < 0) break
                total += n
                // Vérifié AVANT d'écrire : le tampon ne grossit jamais au-delà
                // du plafond, même d'un bloc.
                if (total > plafond) throw TropGros(plafond)
                tampon.write(bloc, 0, n)
            }
            return tampon.contenu()
        } catch (e: OutOfMemoryError) {
            throw MemoireInsuffisante()
        }
    }

    /**
     * Nom, type, taille annoncée et contenu borné d'une URI de contenu.
     *
     * @throws TropGros au-delà de [plafond]
     * @throws MemoireInsuffisante si l'allocation échoue malgré tout
     * @throws IllegalStateException quand l'URI ne rend rien de lisible
     */
    fun lireUri(resolver: ContentResolver, uri: Uri, plafond: Long): Lue {
        val meta = metadonnees(resolver, uri)
        val type = resolver.getType(uri) ?: "application/octet-stream"
        // Refus sur la taille annoncée AVANT d'ouvrir quoi que ce soit.
        if (meta.taille != null && meta.taille > plafond) throw TropGros(plafond)
        val octets = resolver.openInputStream(uri)?.use { lire(it, plafond, meta.taille) }
            ?: throw IllegalStateException("unreadable")
        return Lue(meta.nom, type, octets)
    }

    /**
     * Ce que le fournisseur dit du fichier, sans en lire un octet.
     *
     * C'est tout ce que le composeur montre d'une pièce PROPOSÉE : le nom pour
     * la reconnaître, la taille pour savoir qu'elle ne passera pas.
     */
    fun metadonnees(resolver: ContentResolver, uri: Uri): Meta {
        val (nom, taille) = runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val iNom = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val iTaille = c.getColumnIndex(OpenableColumns.SIZE)
                Pair(
                    if (iNom >= 0 && !c.isNull(iNom)) c.getString(iNom) else null,
                    if (iTaille >= 0 && !c.isNull(iTaille)) c.getLong(iTaille) else null,
                )
            }
        }.getOrNull() ?: Pair(null, null)
        return Meta(
            nom = nom?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "piece-jointe",
            taille = taille?.takeIf { it >= 0 },
        )
    }

    data class Meta(val nom: String, val taille: Long?)

    class Lue(val nom: String, val type: String, val octets: ByteArray)

    /**
     * Rend son tableau sans copie quand la taille annoncée était juste : à
     * 25 Mo, `toByteArray()` doublerait le pic de mémoire pour rien.
     */
    private class TamponExpose(capacite: Int) : ByteArrayOutputStream(capacite) {
        fun contenu(): ByteArray = if (count == buf.size) buf else buf.copyOf(count)
    }
}
