package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.ui.relativeTime
import com.bluefoxconsultant.sms.ui.threads.Avatar
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MailRow(
    message: MailMessage,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val unread = message.unreadCount > 0 || message.isUnread

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(message.correspondent)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.correspondent,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // More than one message in the conversation: show the count the
                // way a mail client does, so a long thread reads as one row.
                if (message.messageCount > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = message.messageCount.toString(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relativeTime(message.sortDate),
                    fontSize = 12.sp,
                    color = if (unread) BrandAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = message.displaySubject,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (message.hasAttachments) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Pièce jointe",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (message.snoozedUntilMs != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = "Reporté",
                        tint = BrandAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            // Where this email was filed in Odoo — the reason for using this
            // app over a plain IMAP client, so it belongs on the row.
            message.record?.let { record ->
                Spacer(Modifier.size(4.dp))
                RecordChip(record.name)
            }
        }
    }
}

@Composable
private fun RecordChip(label: String) {
    Box(
        modifier = Modifier
            .background(BrandAccent.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = BrandAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
