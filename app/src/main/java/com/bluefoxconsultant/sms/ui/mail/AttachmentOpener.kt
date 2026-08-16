package com.bluefoxconsultant.sms.ui.mail

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads an attachment and hands it to whatever app can open it.
 *
 * The bytes never touch shared storage: they go to a private cache
 * subdirectory and are exposed through a [FileProvider] grant scoped to the
 * single receiving activity, for the life of that one intent. Business mail
 * carries contracts and invoices — dropping those in Downloads, where every
 * app with storage permission can read them, would be the wrong default.
 */
object AttachmentOpener {

    private const val DIR = "mail_attachments"

    /** Result of an open attempt, so the caller can say something useful. */
    sealed interface Result {
        data object Ok : Result
        data class Failed(val message: String) : Result
    }

    suspend fun open(context: Context, emailId: Int, attachment: MailAttachment): Result {
        val file = try {
            withContext(Dispatchers.IO) {
                val bytes = Graph.mail.attachment(emailId, attachment.idx)
                val dir = File(context.cacheDir, DIR).apply { mkdirs() }
                // Prefix with the email id: two messages can each carry a
                // "facture.pdf", and the second must not serve the first's bytes.
                File(dir, "$emailId-${sanitize(attachment.name)}").apply {
                    writeBytes(bytes)
                }
            }
        } catch (e: Exception) {
            return Result.Failed("Téléchargement impossible.")
        }

        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
        } catch (e: Exception) {
            return Result.Failed("Fichier inaccessible.")
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mimetype.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Result.Ok
        } catch (e: Exception) {
            // No installed app claims this type — common for .eml or odd MIME.
            Result.Failed("Aucune application pour ouvrir « ${attachment.name} ».")
        }
    }

    /** Keep the filename recognisable without letting it escape the directory. */
    private fun sanitize(name: String): String =
        name.replace(Regex("""[/\\:*?"<>|]"""), "_")
            .removePrefix(".")
            .take(96)
            .ifBlank { "piece-jointe" }

    /** Called on sign-out: cached attachments outlive the session otherwise. */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, DIR).deleteRecursively() }
    }
}
