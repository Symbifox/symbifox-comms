package com.bluefoxconsultant.sms.sip

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Ce qui manque pour que le combiné SONNE, par opposition à ce qui manque pour
 * qu'il reçoive.
 *
 * 🔴 Déclarer `USE_FULL_SCREEN_INTENT` au manifeste ne suffit plus. Depuis
 * Android 14, elle n'est accordée à l'installation qu'aux applications que le
 * système reconnaît comme applications d'appel ou de réveille-matin. Sans elle,
 * `setFullScreenIntent` ne lève rien : la sonnerie retombe en simple bandeau et
 * l'écran d'appel ne monte jamais par-dessus le verrouillage.
 *
 * L'autre porte est l'hibernation. Une application qu'Android met en pause
 * faute d'usage passe à l'état « arrêtée de force », et une application arrêtée
 * de force ne reçoit PLUS AUCUNE diffusion, donc plus aucun push.
 *
 * ⚠️ **Les deux sont des réglages DIFFÉRENTS, dans deux écrans différents.**
 * Accorder l'écran plein fait disparaître la bannière rouge et apparaître
 * l'ambre : de l'extérieur, ça se lit « la bannière n'est pas partie ». La
 * bannière nomme donc maintenant le réglage exact, et se laisse taire quand le
 * système répond faux à une question déjà réglée.
 */
enum class CallGap {
    /** L'écran de sonnerie ne peut pas monter. Rien d'autre ne compte. */
    FULL_SCREEN,

    /** Le réveil marche, mais Android peut l'éteindre après des mois sans usage. */
    HIBERNATION,
}

private const val FICHIER_SILENCE = "bf_call_gaps"

private fun fullScreenGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    val nm = context.getSystemService(NotificationManager::class.java) ?: return true
    return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
}

private fun hibernationExempt(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
    // ⚠️ Le défaut est `true` : une lecture qui échoue ne doit pas inventer un
    // problème. Une bannière qui accuse à tort coûte plus cher que pas de
    // bannière du tout.
    return runCatching { context.packageManager.isAutoRevokeWhitelisted }.getOrDefault(true)
}

/**
 * Le réglage système à ouvrir pour combler [gap], et rien d'autre.
 *
 * ⚠️ Rend null quand aucune activité ne répond. `ACTION_AUTO_REVOKE_PERMISSIONS`
 * n'est pas servi partout : sur certaines versions et certains ROM il ne
 * résout rien, et le `runCatching` de l'appelant avalait l'échec en silence. On
 * touchait la bannière, il ne se passait RIEN, et on en concluait qu'on avait
 * réglé quelque chose.
 */
fun settingsIntentFor(context: Context, gap: CallGap): Intent? {
    val self = Uri.fromParts("package", context.packageName, null)
    val candidats = when (gap) {
        CallGap.FULL_SCREEN -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, self)
            } else {
                null
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self),
        )
        // La fiche de l'application est le repli qui marche PARTOUT : le
        // réglage y vit sous « Application inutilisée ».
        CallGap.HIBERNATION -> listOf(
            Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, self),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self),
        )
    }
    return candidats.firstOrNull { intent ->
        intent.resolveActivity(context.packageManager) != null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Où se trouve le réglage, en toutes lettres, quand l'écran ne s'ouvre pas. */
fun cheminReglage(gap: CallGap): String = when (gap) {
    CallGap.FULL_SCREEN ->
        "Paramètres › Applications › Symbifox Comms › Notifications › " +
            "Notifications en plein écran"
    CallGap.HIBERNATION ->
        "Paramètres › Applications › Symbifox Comms › Application inutilisée › " +
            "désactiver la mise en pause"
}

/**
 * Taire une alerte que le système continue de signaler à tort.
 *
 * 🔴 Nécessaire, pas complaisant. `isAutoRevokeWhitelisted` répond faux sur des
 * ROM où l'hibernation n'existe pas, et sur des versions où la valeur ne se
 * rafraîchit qu'au prochain démarrage du processus. Sans cette porte, la
 * bannière accuse indéfiniment quelqu'un qui a fait ce qu'on lui demandait, et
 * une alerte qu'on ne peut pas éteindre finit par être ignorée — y compris le
 * jour où elle a raison.
 *
 * ⚠️ Le silence se lève tout seul dès que le système repasse au vert : une
 * régression future sera donc signalée de nouveau.
 */
fun tairePourToujours(context: Context, gap: CallGap) {
    context.getSharedPreferences(FICHIER_SILENCE, Context.MODE_PRIVATE)
        .edit().putBoolean(gap.name, true).apply()
}

private fun estTue(context: Context, gap: CallGap): Boolean =
    context.getSharedPreferences(FICHIER_SILENCE, Context.MODE_PRIVATE)
        .getBoolean(gap.name, false)

private fun leverLeSilence(context: Context, gap: CallGap) {
    context.getSharedPreferences(FICHIER_SILENCE, Context.MODE_PRIVATE)
        .edit().remove(gap.name).apply()
}

/**
 * Ce qui manque en ce moment, relu à chaque retour à l'écran.
 *
 * ⚠️ Relu sur `ON_RESUME` et pas une fois pour toutes : on envoie justement la
 * personne dans les réglages système, et elle revient. Une bannière qui reste
 * après qu'on a accordé la permission se lit comme un bogue.
 */
@Composable
fun rememberCallGaps(): List<CallGap> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var gaps by remember { mutableStateOf(emptyList<CallGap>()) }
    var relecture by remember { mutableIntStateOf(0) }

    fun mesurer() {
        gaps = buildList {
            val plein = fullScreenGranted(context)
            val hiberne = hibernationExempt(context)
            // Le silence se lève dès que le système dit oui : ce qui est réglé
            // pour de bon n'a plus besoin d'être tu.
            if (plein) leverLeSilence(context, CallGap.FULL_SCREEN)
            if (hiberne) leverLeSilence(context, CallGap.HIBERNATION)
            if (!plein && !estTue(context, CallGap.FULL_SCREEN)) add(CallGap.FULL_SCREEN)
            if (!hiberne && !estTue(context, CallGap.HIBERNATION)) add(CallGap.HIBERNATION)
        }
    }

    DisposableEffect(owner, relecture) {
        // Mesuré tout de suite en plus de l'observateur : ON_RESUME a pu passer
        // avant qu'on s'y abonne, et la bannière n'apparaissait alors qu'au
        // retour suivant.
        mesurer()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) mesurer()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return gaps
}
