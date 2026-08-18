package com.bluefoxconsultant.sms.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.MainActivity

object Notifier {

    const val CHANNEL_ID = "sms"
    const val CHANNEL_MAIL = "mail"
    const val CHANNEL_GENFOX = "genfox"
    const val CHANNEL_HOSTING = "hosting"
    const val EXTRA_THREAD_ID = "thread_id"
    const val EXTRA_NOTIF_ID = "notif_id"
    const val EXTRA_EMAIL_ID = "email_id"
    const val EXTRA_THREAD_KEY = "thread_key"

    /**
     * Mail notifications carry a tag so the two halves can be cleared
     * independently — `clear_all` from one module must not wipe the other's
     * notifications off the shade.
     */
    const val TAG_MAIL = "mail"

    /** Same reasoning for the assistant: its own tag, cleared on its own. */
    const val TAG_GENFOX = "genfox"

    /**
     * Une seule notification d'hébergement, jamais une pile : l'état du parc
     * est UN état, et vingt lignes dans le tiroir n'apprennent rien de plus
     * qu'une ligne qui dit combien. Identifiant fixe, donc chaque cycle
     * remplace la précédente au lieu de s'empiler.
     *
     * ⚠️ Étiquetée, comme le courriel : `cancelAll` balaie les notifications
     * SANS étiquette. Sans celle-ci, ouvrir un texto effacerait l'avis de panne.
     */
    const val TAG_HOSTING = "hosting"
    const val HOSTING_ID = 0x405705
    const val EXTRA_GENFOX_SESSION = "genfox_session_id"

    private const val BF_BLUE = 0xFF29ABE2.toInt()
    private const val ALERT_RED = 0xFFD32F2F.toInt()
    private const val ALERT_AMBER = 0xFFF57C00.toInt()

    /** Stable per-thread notification id so `clear` can target it. */
    fun notifId(threadId: Int): Int = "thread-$threadId".hashCode()

    fun mailNotifId(emailId: Int): Int = "mail-$emailId".hashCode()

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Messages SMS",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications de nouveaux textos"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun ensureMailChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_MAIL) == null) {
            // Its own channel so mail and SMS can be tuned separately — mail
            // arrives in bursts and most people want it quieter.
            val channel = NotificationChannel(
                CHANNEL_MAIL,
                "Courriel",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifications de nouveaux courriels"
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun show(context: Context, title: String, body: String, threadId: Int, messageId: Int) {
        ensureChannel(context)
        val id = notifId(threadId)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_THREAD_ID, threadId)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            id,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val remoteInput = RemoteInput.Builder(ReplyReceiver.KEY_REPLY)
            .setLabel("Répondre")
            .build()
        val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
            putExtra(EXTRA_THREAD_ID, threadId)
            putExtra(EXTRA_NOTIF_ID, id)
        }
        val replyPi = PendingIntent.getBroadcast(
            context,
            id,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_stat_sms, "Répondre", replyPi)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // Swipe-away → mark the thread read in Odoo.
        val deleteIntent = Intent(context, MarkReadReceiver::class.java).apply {
            putExtra(EXTRA_THREAD_ID, threadId)
        }
        val deletePi = PendingIntent.getBroadcast(
            context,
            id,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setColor(BF_BLUE)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .setDeleteIntent(deletePi)
            .addAction(replyAction)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to do.
        }
    }

    private fun ensureGenfoxChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_GENFOX) == null) {
            // Default importance, not high: an answer you asked for a minute
            // ago is worth a glance, not an interruption.
            val channel = NotificationChannel(
                CHANNEL_GENFOX,
                "GenFox",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Réponses de l'assistant"
            }
            nm.createNotificationChannel(channel)
        }
    }

    /** The assistant finished a turn the phone did not stay to watch. */
    fun showGenfox(context: Context, title: String, body: String, sessionId: Int) {
        ensureGenfoxChannel(context)
        val id = "genfox-$sessionId".hashCode()
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_GENFOX_SESSION, sessionId)
        }
        val contentPi = PendingIntent.getActivity(
            context, id, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_GENFOX)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setColor(BF_BLUE)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG_GENFOX, id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to do.
        }
    }

    fun showSent(context: Context, notifId: Int) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setColor(BF_BLUE)
            .setContentText("Envoyé")
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    /**
     * A new email. `emailId` is 0 on the batch-summary push the server sends
     * past five messages in one sync — tapping that one opens the mail tab
     * rather than a message, and it carries no quick reply.
     */
    fun showMail(
        context: Context,
        title: String,
        subject: String,
        preview: String,
        emailId: Int,
        threadKey: String,
    ) {
        ensureMailChannel(context)
        val id = if (emailId > 0) mailNotifId(emailId) else SUMMARY_MAIL_ID

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_EMAIL_ID, emailId)
            putExtra(EXTRA_THREAD_KEY, threadKey)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            id,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setColor(BF_BLUE)
            .setContentTitle(title)
            .setContentText(subject)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(if (preview.isBlank()) subject else "$subject\n\n$preview"),
            )
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setAutoCancel(true)
            .setContentIntent(contentPi)

        if (emailId > 0) {
            val remoteInput = RemoteInput.Builder(MailReplyReceiver.KEY_REPLY)
                .setLabel("Répondre")
                .build()
            val replyIntent = Intent(context, MailReplyReceiver::class.java).apply {
                putExtra(EXTRA_EMAIL_ID, emailId)
                putExtra(EXTRA_NOTIF_ID, id)
            }
            val replyPi = PendingIntent.getBroadcast(
                context,
                id,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_sms, "Répondre", replyPi)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .build(),
            )

            // Swipe-away → mark read in Odoo, same two-way sync as SMS.
            val deleteIntent = Intent(context, MailMarkReadReceiver::class.java).apply {
                putExtra(EXTRA_EMAIL_ID, emailId)
            }
            builder.setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    id,
                    deleteIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        try {
            NotificationManagerCompat.from(context).notify(TAG_MAIL, id, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to do.
        }
    }

    fun showMailSent(context: Context, notifId: Int) {
        ensureMailChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setColor(BF_BLUE)
            .setContentText("Réponse envoyée")
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG_MAIL, notifId, notification)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    private fun ensureHostingChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_HOSTING) == null) {
            // Importance haute : un service à terre est la seule chose que
            // cette app ait à dire qui ne puisse pas attendre le matin.
            val channel = NotificationChannel(
                CHANNEL_HOSTING,
                "Hébergement",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Services hors ligne, disques pleins, sauvegardes en retard"
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * L'état du parc, ou rien.
     *
     * Persistante (`ongoing`) tant que quelque chose ne va pas : une alerte
     * qu'on peut balayer d'un doigt distrait ne remplit pas son office, et le
     * seul geste qui doit la faire disparaître est que la panne cesse. Elle est
     * donc RETIRÉE d'ici même dès que le parc est en ordre — c'est la
     * contrepartie, sans quoi elle resterait à mentir dans le tiroir.
     *
     * ⚠️ `setOnlyAlertOnce` : reposée à chaque cycle, elle sonnerait toutes les
     * quatre-vingt-dix secondes pendant toute la durée d'une panne.
     */
    fun showHosting(context: Context, alerts: com.bluefoxconsultant.sms.data.HostingAlerts) {
        if (!alerts.enabled || !alerts.hasAny) {
            clearHosting(context)
            return
        }
        ensureHostingChannel(context)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context, HOSTING_ID, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lines = alerts.alerts.take(6).map { "${it.service} — ${it.detail}" }
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        if (alerts.count > lines.size) {
            style.setSummaryText("et ${alerts.count - lines.size} de plus")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_HOSTING)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setColor(if (alerts.isDown) ALERT_RED else ALERT_AMBER)
            .setContentTitle(if (alerts.isDown) "Hébergement — panne" else "Hébergement")
            .setContentText(alerts.summary)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(contentPi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG_HOSTING, HOSTING_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS refusée — la bannière dans l'app reste.
        }
    }

    fun clearHosting(context: Context) {
        NotificationManagerCompat.from(context).cancel(TAG_HOSTING, HOSTING_ID)
    }

    fun cancelThread(context: Context, threadId: Int) {
        NotificationManagerCompat.from(context).cancel(notifId(threadId))
    }

    fun cancelMail(context: Context, emailId: Int) {
        NotificationManagerCompat.from(context).cancel(TAG_MAIL, mailNotifId(emailId))
    }

    /** Clears SMS notifications only — mail ones carry [TAG_MAIL] and survive. */
    fun cancelAll(context: Context) {
        cancelTagged(context, tag = null)
    }

    fun cancelAllMail(context: Context) {
        cancelTagged(context, tag = TAG_MAIL)
    }

    private fun cancelTagged(context: Context, tag: String?) {
        val manager = NotificationManagerCompat.from(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val active = try {
            nm?.activeNotifications
        } catch (e: Exception) {
            null
        }
        if (active == null) {
            // Can't enumerate — fall back to the blunt instrument rather than
            // leaving a stale notification the user already dealt with.
            manager.cancelAll()
            return
        }
        active.filter { it.tag == tag }.forEach { manager.cancel(it.tag, it.id) }
    }

    private const val SUMMARY_MAIL_ID = 0x11FA11
}
