package com.bluefoxconsultant.sms.sip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.MainActivity

/**
 * Tient l'appel en vie quand l'app n'est plus à l'écran.
 *
 * ⚠️ Sans service au premier plan, Android suspend le processus dès que
 * l'utilisateur éteint l'écran ou passe à une autre app — et la WebView qui
 * porte l'audio se fait geler avec lui. La conversation se coupe au milieu
 * d'une phrase, sans erreur : du point de vue du système, tout va bien.
 *
 * Type « microphone » : c'est ce que l'app fait réellement pendant un appel.
 * « phoneCall » supposerait MANAGE_OWN_CALLS et une intégration au journal
 * d'appels du système, qu'on ne veut pas ici.
 *
 * Le service ne PORTE pas l'appel : [SipEngine] le fait, dans le processus. Le
 * service ne fait qu'empêcher le système de couper la lumière.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peer = intent?.getStringExtra(EXTRA_PEER).orEmpty()
        // 🔴 La surcharge à trois arguments n'existe qu'à partir d'Android 10 :
        // l'appeler sur Android 8 ou 9 lève NoSuchMethodError AVANT que
        // `typeOrZero()` ait pu rendre 0. Relevé par Lint (NewApi) à l'audit
        // du 2026-09-08 : tout appel plantait le service sur ces versions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification(peer), typeOrZero())
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification(peer))
        }
        // NOT_STICKY : si le système nous tue quand même, l'appel est de toute
        // façon perdu. Redémarrer un service sans appel n'afficherait qu'une
        // notification fantôme.
        return START_NOT_STICKY
    }

    private fun typeOrZero(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

    private fun notification(peer: String): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // Silencieux et discret : la notification dit qu'un appel est en
        // cours, elle n'a rien à annoncer. Le téléphone sonne déjà.
        // ⚠️ Recréé à chaque fois, sans garde « déjà là » : un canal existant ne
        // reprend que son nom et sa description, dans la langue du moment. Une
        // garde figeait le nom français chez qui a déjà l'app ; l'importance et
        // ce que la personne a réglé restent, eux, intouchés.
        nm?.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.call_service_in_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.call_service_channel_description) },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle(getString(R.string.call_service_in_progress))
            .setContentText(peer.ifBlank { getString(R.string.app_name) })
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    companion object {
        private const val CHANNEL = "call"
        private const val NOTIF_ID = 4201
        private const val EXTRA_PEER = "peer"

        /**
         * Démarre le service, et ACCEPTE de ne pas y arriver.
         *
         * ⚠️ Depuis Android 12, un service au premier plan lancé alors que
         * l'app est en arrière-plan lève `ForegroundServiceStartNotAllowedException`,
         * et depuis Android 14 le type « microphone » ajoute son propre refus
         * (`SecurityException`) tant qu'aucun écran de l'app n'est visible.
         *
         * Ça n'arrivait jamais tant qu'un appel ne pouvait naître que devant
         * l'utilisateur. Le réveil par push change ça : le PBX prévient pendant
         * que l'app est fermée, et laisser l'exception remonter ferait planter
         * le processus au moment précis où il doit sonner. L'écran de sonnerie
         * ([IncomingCallActivity]) rend l'app visible et le prochain essai
         * passe ; en attendant, un appel sans service vaut mieux qu'un plantage.
         */
        fun start(context: Context, peer: String) {
            val intent = Intent(context, CallService::class.java).putExtra(EXTRA_PEER, peer)
            // startForegroundService : obligatoire depuis O quand l'app n'est
            // pas déjà au premier plan, et sans effet néfaste quand elle l'est.
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
