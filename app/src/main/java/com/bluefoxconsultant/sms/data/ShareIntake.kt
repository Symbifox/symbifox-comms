package com.bluefoxconsultant.sms.data

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Ce qu'une autre app vient de partager vers Comms.
 *
 * [subject] n'existe que sur le chemin courriel (`EXTRA_SUBJECT`) : un texto
 * n'a pas d'objet, il finit donc fondu dans le corps. [uris] porte des URI de
 * fournisseur de contenu, lisibles seulement grâce à la permission accordée
 * avec l'intention — d'où la lecture immédiate dans [MediaPrep] plutôt qu'un
 * report à l'envoi.
 */
data class SharedContent(
    val text: String = "",
    val subject: String = "",
    val uris: List<Uri> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank() && subject.isBlank() && uris.isEmpty()
    val hasFiles: Boolean get() = uris.isNotEmpty()
}

/**
 * Le passe-plat entre l'intention de partage et le composeur qui l'accueille.
 *
 * ⚠️ En mémoire, et **pris une seule fois**. Deux raisons de ne pas faire
 * voyager ça dans la route de navigation : une liste d'URI encodée dans une
 * URL devient illisible dès la deuxième pièce jointe, et surtout la permission
 * de lecture est attachée à l'intention reçue par l'activité — la garder
 * ailleurs que dans le processus qui la détient ne servirait à rien.
 *
 * [take] vide la réserve : revenir sur le composeur après un envoi ne doit pas
 * re-remplir l'écran avec ce qui vient de partir.
 */
object ShareIntake {

    @Volatile
    private var pending: SharedContent? = null

    fun offer(content: SharedContent) {
        pending = content.takeUnless { it.isEmpty }
    }

    fun peek(): SharedContent? = pending

    fun take(): SharedContent? {
        val held = pending
        pending = null
        return held
    }

    fun clear() {
        pending = null
    }

    /**
     * Lit une intention de partage, ou rend `null` si ce n'en est pas une.
     *
     * `ACTION_SEND` porte au plus un fichier, `ACTION_SEND_MULTIPLE` en porte
     * plusieurs — et les deux peuvent aussi n'avoir que du texte, ce qui est
     * le cas d'un lien partagé depuis un navigateur.
     */
    fun from(intent: Intent): SharedContent? {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamExtras()
            else -> return null
        }
        val content = SharedContent(
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
            uris = uris,
        )
        return content.takeUnless { it.isEmpty }
    }

    // `getParcelableExtra(String)` est déprécié depuis Tiramisu et rend n'importe
    // quoi sur une intention forgée ; la variante typée refuse la mauvaise classe.
    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtras(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
}
