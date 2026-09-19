package com.bluefoxconsultant.sms.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Un fichier prêt à partir en MMS : déjà lu, déjà ramené sous les plafonds.
 *
 * Le base64 est produit ici et non à l'envoi, parce que la permission de lire
 * l'URI partagée ne survit pas forcément à l'écran.
 */
data class OutgoingMedia(
    val filename: String,
    val contentType: String,
    val dataB64: String,
    val sizeBytes: Int,
    val isImage: Boolean,
    /** Gardée pour l'aperçu à l'écran seulement. */
    val previewUri: Uri? = null,
)

/**
 * Lecture et mise au gabarit d'une pièce jointe MMS.
 *
 * ⚠️ **Le redimensionnement n'est pas un confort, c'est ce qui rend l'envoi
 * possible.** Une photo de téléphone pèse 3 à 6 Mo ; le MMS n'en accepte
 * qu'environ un, et le serveur refuse au-delà (`/send` → « Pièce jointe trop
 * volumineuse »). Sans cette étape, partager une photo depuis la galerie
 * échouerait à tous les coups, avec une erreur qui parle de taille alors que
 * l'usager n'a rien à redimensionner lui-même.
 *
 * Les fichiers qui ne sont pas des images passent tels quels : on ne sait pas
 * les rétrécir, et un PDF de 2 Mo doit se faire refuser clairement plutôt que
 * d'être tronqué en silence.
 */
object MediaPrep {

    /** Le plafond du serveur, repris ici pour refuser avant l'aller-retour. */
    const val MAX_BYTES = 1_000_000

    /**
     * Ce qu'on accepte de LIRE d'une image avant de la rétrécir.
     *
     * ⚠️ Pas [MAX_BYTES] : une photo de téléphone pèse 3 à 6 Mo et c'est
     * justement celle-là qu'il faut pouvoir ramener sous le plafond. Mais pas
     * sans borne non plus (C-F4) : au-delà de 25 Mo ce n'est plus une photo à
     * texter, et la décoder coûterait plus de mémoire que l'appareil n'en a.
     * Même valeur que le plafond d'une pièce jointe courriel.
     */
    const val MAX_LECTURE_IMAGE = 25L * 1024 * 1024

    /** Trois, comme `media1..media3` chez VOIP.ms. */
    const val MAX_PARTS = 3

    /** Assez pour rester lisible à l'écran d'en face, assez petit pour passer. */
    private const val MAX_EDGE = 1280
    private val JPEG_QUALITIES = intArrayOf(85, 75, 65, 50, 40)

    /** [plafond] : celui qui a été franchi, pour que le message dise le bon chiffre. */
    class TooLarge(val name: String, val plafond: Long = MAX_BYTES.toLong()) : Exception("too_large")

    /**
     * Rend le média prêt à envoyer, ou lève.
     *
     * @throws TooLarge quand un fichier non redimensionnable dépasse le plafond
     * @throws LectureBornee.MemoireInsuffisante quand l'appareil ne peut pas le préparer
     * @throws IllegalStateException quand l'URI ne rend rien de lisible
     */
    fun read(context: Context, uri: Uri): OutgoingMedia {
        val resolver = context.contentResolver
        val type = resolver.getType(uri) ?: "application/octet-stream"
        val isImage = type.startsWith("image/")
        // Lu par blocs sous un plafond vérifié avant d'allouer : un PDF de
        // 2 Go ne passe plus par la mémoire pour se faire dire qu'il dépasse
        // 1 Mo. Voir `LectureBornee`.
        val plafond = if (isImage) MAX_LECTURE_IMAGE else MAX_BYTES.toLong()
        val lue = try {
            LectureBornee.lireUri(resolver, uri, plafond)
        } catch (e: LectureBornee.TropGros) {
            throw TooLarge(LectureBornee.metadonnees(resolver, uri).nom, e.plafond)
        }
        val name = lue.nom
        val bytes = lue.octets
        check(bytes.isNotEmpty()) { "empty" }

        val payload = try {
            if (isImage && bytes.size > MAX_BYTES) shrink(bytes) else bytes
        } catch (e: OutOfMemoryError) {
            // Décoder une photo énorme peut manquer de mémoire même
            // sous-échantillonnée : on le dit, on ne ferme pas l'app.
            throw LectureBornee.MemoireInsuffisante()
        }
        if (payload.size > MAX_BYTES) throw TooLarge(name)

        // Rétrécir réencode en JPEG : le nom et le type doivent suivre, sinon
        // le destinataire reçoit un « photo.png » qui n'est pas un PNG.
        val shrunk = payload !== bytes
        return OutgoingMedia(
            filename = if (shrunk) name.substringBeforeLast('.', name) + ".jpg" else name,
            contentType = if (shrunk) "image/jpeg" else type,
            dataB64 = Base64.encodeToString(payload, Base64.NO_WRAP),
            sizeBytes = payload.size,
            isImage = isImage,
            previewUri = uri,
        )
    }

    /**
     * Sous-échantillonne puis compresse jusqu'à passer sous le plafond.
     *
     * ``inSampleSize`` d'abord — décoder une photo de 12 Mpx en pleine taille
     * pour la recompresser ensuite est le meilleur moyen de manquer de mémoire
     * sur un appareil modeste. La qualité descend ensuite par paliers, et le
     * dernier essai est rendu tel quel : l'appelant tranche.
     */
    private fun shrink(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return bytes

        var sample = 1
        while (longest / sample > MAX_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return bytes

        var out = bytes
        for (quality in JPEG_QUALITIES) {
            val buffer = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            out = buffer.toByteArray()
            if (out.size <= MAX_BYTES) break
        }
        bitmap.recycle()
        return out
    }
}
