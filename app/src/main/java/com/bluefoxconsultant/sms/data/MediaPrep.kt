package com.bluefoxconsultant.sms.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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

    /** Trois, comme `media1..media3` chez VOIP.ms. */
    const val MAX_PARTS = 3

    /** Assez pour rester lisible à l'écran d'en face, assez petit pour passer. */
    private const val MAX_EDGE = 1280
    private val JPEG_QUALITIES = intArrayOf(85, 75, 65, 50, 40)

    class TooLarge(val name: String) : Exception("too_large")

    /**
     * Rend le média prêt à envoyer, ou lève.
     *
     * @throws TooLarge quand un fichier non redimensionnable dépasse le plafond
     * @throws IllegalStateException quand l'URI ne rend rien de lisible
     */
    fun read(context: Context, uri: Uri): OutgoingMedia {
        val resolver = context.contentResolver
        val name = resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "piece-jointe"
        val type = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("unreadable")
        check(bytes.isNotEmpty()) { "empty" }

        val isImage = type.startsWith("image/")
        val payload = if (isImage && bytes.size > MAX_BYTES) shrink(bytes) else bytes
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
