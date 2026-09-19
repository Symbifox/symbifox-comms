package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.ui.graphics.vector.ImageVector
import com.bluefoxconsultant.sms.data.MailFilter

/**
 * Le pictogramme de chaque boîte, à un seul endroit.
 *
 * Réception, non lus, reportés, traités, envoyés, à router, tous, brouillons :
 * chacun le sien, et le même partout où la boîte est nommée. Deux boîtes qui
 * partageraient un pictogramme ne se distingueraient plus, ce qui était
 * justement le reproche.
 */
fun iconeDeBoite(filter: MailFilter): ImageVector = when (filter) {
    MailFilter.INBOX -> Icons.Filled.Inbox
    MailFilter.UNREAD -> Icons.Filled.MarkEmailUnread
    MailFilter.SNOOZED -> Icons.Filled.Snooze
    MailFilter.HANDLED -> Icons.Filled.TaskAlt
    MailFilter.SENT -> Icons.AutoMirrored.Filled.Send
    MailFilter.UNROUTED -> Icons.Filled.AltRoute
    MailFilter.ALL -> Icons.Filled.AllInbox
    MailFilter.DRAFTS -> Icons.Filled.Drafts
}
