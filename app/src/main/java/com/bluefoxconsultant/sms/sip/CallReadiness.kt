package com.bluefoxconsultant.sms.sip

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
 * l'écran d'appel ne monte jamais par-dessus le verrouillage. Le réveil par
 * push aurait donc l'air de fonctionner — le push arrive, la notification
 * existe — tout en ratant la seule chose qu'on lui demande.
 *
 * L'autre porte est l'hibernation. Une application qu'Android met en pause
 * faute d'usage passe à l'état « arrêtée de force », et une application arrêtée
 * de force ne reçoit PLUS AUCUNE diffusion, donc plus aucun push, jusqu'à ce
 * qu'on la rouvre à la main. Aucune erreur nulle part, comme d'habitude.
 */
enum class CallGap {
    /** L'écran de sonnerie ne peut pas monter. Rien d'autre ne compte. */
    FULL_SCREEN,

    /** Le réveil marche, mais Android peut l'éteindre après des mois sans usage. */
    HIBERNATION,
}

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

/** Le réglage système à ouvrir pour combler [gap]. */
fun settingsIntentFor(context: Context, gap: CallGap): Intent {
    val self = Uri.fromParts("package", context.packageName, null)
    return when (gap) {
        CallGap.FULL_SCREEN ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, self)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self)
            }
        CallGap.HIBERNATION -> Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, self)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                gaps = buildList {
                    if (!fullScreenGranted(context)) add(CallGap.FULL_SCREEN)
                    if (!hibernationExempt(context)) add(CallGap.HIBERNATION)
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return gaps
}
