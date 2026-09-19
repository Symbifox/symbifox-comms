package com.bluefoxconsultant.sms.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Ce qu'une autre app vient de partager vers Comms.
 *
 * [subject] n'existe que sur le chemin courriel (`EXTRA_SUBJECT`) : un texto
 * n'a pas d'objet, il finit donc fondu dans le corps. [uris] porte des URI de
 * fournisseur de contenu, lisibles grâce à la permission accordée avec
 * l'intention — elle vaut pour l'app tant que sa tâche vit, ce qui laisse le
 * temps d'attendre le geste de la personne avant de lire.
 *
 * [ecartes] compte les fichiers refusés par [ShareIntake.uriAcceptable] : ils
 * ne sont pas dans [uris], mais on doit pouvoir dire qu'ils existaient.
 */
data class SharedContent(
    val text: String = "",
    val subject: String = "",
    val uris: List<Uri> = emptyList(),
    val ecartes: Int = 0,
) {
    val isEmpty: Boolean get() = text.isBlank() && subject.isBlank() && uris.isEmpty()
    val hasFiles: Boolean get() = uris.isNotEmpty()
}

/**
 * Un fichier partagé que le composeur PROPOSE, sans l'avoir lu.
 *
 * ⚠️ Le partage ne vaut pas accord (C-M2) : une autre app peut lancer
 * « Envoyer vers » toute seule, et le fichier partait jadis vers le serveur
 * avant que la personne ait vu l'écran. Il reste donc ici — nom et taille
 * annoncés seulement — jusqu'à « Joindre » ou « Envoyer ».
 */
data class PieceProposee(
    val uri: Uri,
    val nom: String,
    /** Annoncée par le fournisseur, `null` s'il ne dit rien. */
    val taille: Long? = null,
)

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
     * Une URI partagée est-elle recevable ? Pure : c'est elle que le banc éprouve.
     *
     * 🔴 Le défaut réparé (audit du 2026-09-08, C-M2) : n'importe quelle app
     * pouvait « partager » un `file:///data/data/com.bluefoxconsultant.sms/…`
     * ou une URI du FileProvider de Comms lui-même, et Comms lisait alors SES
     * PROPRES fichiers — cache courriel, brouillons, boîte d'envoi, pièces
     * jointes téléchargées — pour les téléverser vers un destinataire que la
     * personne n'avait pas encore regardé. Le partage devenait une porte de
     * sortie pour les données de l'app.
     *
     * N'est donc accepté qu'un `content://` servi par une AUTRE app :
     * - `file://`, et tout autre schéma, sont refusés ;
     * - une autorité déclarée par l'app, ou préfixée par son paquet, est
     *   refusée ;
     * - ⚠️ l'autorité est lue SANS son préfixe d'usager : Android résout
     *   `content://0@com.bluefoxconsultant.sms.attachments/…` vers le
     *   fournisseur de l'app, et une comparaison sur la chaîne brute le
     *   laisserait passer.
     *
     * Le schéma est comparé tel quel, sans casse ignorée : le résolveur de
     * contenu d'Android fait de même, donc un « CONTENT:// » ne s'ouvrirait
     * de toute façon pas.
     */
    fun uriAcceptable(
        scheme: String?,
        authority: String?,
        autoritesDeLApp: Set<String>,
        paquet: String,
    ): Boolean {
        if (scheme != "content") return false
        val autorite = authority?.substringAfterLast('@')?.trim()?.lowercase()
        if (autorite.isNullOrEmpty()) return false
        val paquetBas = paquet.lowercase()
        if (autorite == paquetBas || autorite.startsWith("$paquetBas.")) return false
        return autoritesDeLApp.none { it.trim().lowercase() == autorite }
    }

    /**
     * Lit une intention de partage, ou rend `null` si ce n'en est pas une.
     *
     * `ACTION_SEND` porte au plus un fichier, `ACTION_SEND_MULTIPLE` en porte
     * plusieurs — et les deux peuvent aussi n'avoir que du texte, ce qui est
     * le cas d'un lien partagé depuis un navigateur.
     *
     * Rend aussi un partage dont TOUS les fichiers ont été écartés, texte vide
     * compris : c'est à l'appelant de le dire, pas à ce filtre de le taire.
     */
    fun from(intent: Intent, context: Context): SharedContent? {
        val brutes = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamExtras()
            else -> return null
        }
        val autorites = autoritesDeLApp(context)
        val retenues = brutes.filter {
            uriAcceptable(it.scheme, it.authority, autorites, context.packageName)
        }
        val content = SharedContent(
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
            uris = retenues,
            ecartes = brutes.size - retenues.size,
        )
        return content.takeUnless { it.isEmpty && it.ecartes == 0 }
    }

    /**
     * Les autorités que l'app déclare, bibliothèques comprises (FileProvider,
     * initialisation d'androidx). Le préfixe du paquet couvre déjà la
     * convention `${applicationId}.xxx` ; la liste couvre ce qui s'en écarterait.
     */
    private fun autoritesDeLApp(context: Context): Set<String> = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
        }
        info.providers.orEmpty()
            .flatMap { it.authority.orEmpty().split(';') }
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrDefault(emptySet())

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
