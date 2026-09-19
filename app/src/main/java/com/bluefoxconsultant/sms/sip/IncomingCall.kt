package com.bluefoxconsultant.sms.sip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bluefoxconsultant.sms.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Le réveil par push : faire sonner un poste dont le processus est mort.
 *
 * L'app est un vrai poste SIP depuis la 2.22.0, mais son enregistrement ne
 * survit pas à sa fermeture : app balayée ou téléphone redémarré, l'AOR n'a
 * plus aucun contact pour elle et l'appel n'atteint jamais le combiné — sans
 * erreur, ce qui se lit comme un téléphone capricieux.
 *
 * Le PBX prévient donc Odoo AVANT de composer, Odoo pousse ici, et cette
 * classe fait trois choses dans cet ordre :
 *
 * 1. elle dessine tout de suite la notification d'appel entrant, avec une
 *    **intention plein écran** — c'est elle qui ouvre l'écran de sonnerie sur
 *    un téléphone verrouillé, sans que personne n'ait rien à toucher ;
 * 2. elle rallume le poste SIP, pour que l'INVITE ait où arriver ;
 * 3. elle abandonne toute seule au bout de [FENETRE_MS] si rien ne sonne.
 *
 * ⚠️ Rien ici ne démarre de service au premier plan. Depuis Android 12 un tel
 * démarrage depuis l'arrière-plan est refusé, et depuis Android 14 le type
 * « microphone » l'est doublement tant qu'aucun écran n'est visible. C'est
 * l'écran de sonnerie qui rend l'app visible ; le service suit, jamais l'inverse.
 */
object IncomingCall {

    const val CHANNEL = "incoming_call"
    const val NOTIF_ID = 4202
    const val EXTRA_PEER = "call_peer"
    const val EXTRA_NAME = "call_name"
    const val EXTRA_ANSWER = "call_answer"

    /**
     * Combien de temps on garde la sonnerie avant d'abandonner.
     *
     * Un peu plus long que le RINGTIME du PBX (25 s) : la notification doit
     * disparaître APRÈS que le correspondant a raccroché, pas avant, sinon
     * l'écran s'efface pendant que ça sonne encore.
     */
    private const val FENETRE_MS = 45_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var reveil: PowerManager.WakeLock? = null

    /** Dernier appel traité, pour ne pas dessiner deux fois la même sonnerie. */
    private var dernierId: String? = null

    /** L'appel qu'on est en train d'annoncer, pour l'écran de sonnerie. */
    @Volatile
    var peer: String = ""
        private set

    @Volatile
    var name: String = ""
        private set

    fun wake(context: Context, peer: String, name: String, callId: String) {
        val app = context.applicationContext
        // ⚠️ Le PBX peut réveiller deux fois le même appel (une jambe qui
        // rappelle, un réessai). Redessiner la notification relancerait la
        // sonnerie en plein milieu.
        if (callId.isNotBlank() && callId == dernierId && job?.isActive == true) return
        dernierId = callId
        this.peer = peer
        this.name = name

        prendreLeReveil(app)
        notifier(app, peer, name)

        job?.cancel()
        job = scope.launch {
            // Le poste se rallume tout de suite : l'INVITE arrive quelques
            // secondes après le push, et il lui faut un contact où atterrir.
            SipEngine.start(app)
            delay(FENETRE_MS)
            // Personne n'a décroché et rien n'a sonné : on efface plutôt que
            // de laisser une sonnerie fantôme dans le tiroir.
            if (SipEngine.state.value.call == null) stop(app)
        }
    }

    /** L'utilisateur refuse, ou l'appel s'est terminé ailleurs. */
    fun decline(context: Context) {
        SipEngine.cancelAutoAnswer()
        SipEngine.hangup()
        stop(context)
    }

    /** Efface la sonnerie et relâche tout ce qu'elle tenait. */
    fun stop(context: Context) {
        job?.cancel()
        job = null
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIF_ID)
        // ⚠️ Relâcher un verrou non tenu lève IllegalStateException — même
        // piège que le verrou de proximité de SipEngine.
        reveil?.let { if (it.isHeld) runCatching { it.release() } }
        reveil = null
    }

    // ── Interne ───────────────────────────────────────────────────────

    /**
     * Garde le processeur éveillé le temps de la sonnerie.
     *
     * Sans ça, un téléphone en veille profonde peut se rendormir entre le push
     * et l'INVITE : l'enregistrement SIP part, puis rien n'arrive. Le verrou
     * porte son propre délai d'expiration, pour qu'un chemin de sortie oublié
     * ne vide pas la pile.
     */
    private fun prendreLeReveil(context: Context) {
        if (reveil?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        reveil = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SymbifoxComms:sonnerie")
            .also { runCatching { it.acquire(FENETRE_MS + 15_000L) } }
    }

    private fun notifier(context: Context, peer: String, name: String) {
        creerLeCanal(context)
        val appelEntrant = context.getString(R.string.common_incoming_call)
        val titre = name.ifBlank { peer.ifBlank { appelEntrant } }
        val sousTitre = if (name.isBlank()) appelEntrant else peer.ifBlank { appelEntrant }

        val plein = ecran(context, answer = false)
        val repondre = ecran(context, answer = true)
        val refuser = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, CallDeclineReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle(titre)
            .setContentText(sousTitre)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // Une sonnerie ne se balaie pas et ne s'empile pas : elle vit tant
            // que l'appel vit, puis disparaît d'elle-même.
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(FENETRE_MS)
            .setContentIntent(plein)
            // ⚠️ Le cœur du lot. Sans intention plein écran, un appel entrant
            // n'est qu'une ligne muette dans le tiroir : c'est exactement ce
            // que faisait la notification du service d'appel, en importance
            // basse, sans bouton pour répondre.
            .setFullScreenIntent(plein, true)
            .addAction(R.drawable.ic_stat_sms, context.getString(R.string.common_answer_call), repondre)
            .addAction(R.drawable.ic_stat_sms, context.getString(R.string.common_decline_call), refuser)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        }
    }

    private fun ecran(context: Context, answer: Boolean): PendingIntent {
        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PEER, peer)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_ANSWER, answer)
        }
        return PendingIntent.getActivity(
            context,
            if (answer) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Canal dédié, en importance MAXIMALE.
     *
     * ⚠️ L'importance d'un canal se fige à sa création : la relever plus tard
     * dans le code ne change rien sur un appareil qui a déjà l'app. C'est pour
     * ça que la sonnerie ne réutilise pas le canal « call » du service d'appel,
     * volontairement silencieux et en importance basse — il ne pourrait plus
     * jamais sonner.
     *
     * ⚠️ Recréé à chaque sonnerie, sans garde « déjà là » : pour un canal
     * existant, Android ne met à jour QUE le nom et la description, donc le nom
     * suit la langue du téléphone. L'importance, la vibration, le Ne pas
     * déranger et la visibilité restent ceux déjà en place, ou réglés par la
     * personne. Une garde figeait le nom français chez qui a déjà l'app.
     */
    private fun creerLeCanal(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.incoming_call_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
                .apply {
                    description = context.getString(R.string.incoming_call_channel_description)
                    setBypassDnd(false)
                    enableVibration(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
        )
    }
}
