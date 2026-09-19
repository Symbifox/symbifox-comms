package com.bluefoxconsultant.sms.ui.mail

import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailDraft
import com.bluefoxconsultant.sms.data.ServerDraftSaveResponse

/**
 * Faire remonter au poste une reprise gardée sur l'appareil (#25579).
 *
 * Vit ici, et pas dans le dépôt réseau, parce que la conversion du corps est
 * une affaire de composeur : c'est [RichText] qui sait ce que la syntaxe
 * légère de l'écran veut dire. Appelé de deux endroits — le composeur au
 * moment d'envoyer, et la liste chaque fois qu'elle rouvre la section — d'où
 * une fonction plutôt qu'une méthode.
 *
 * ⚠️ Le corps ne voyage QUE s'il a été retouché. Un brouillon ouvert au
 * téléphone pour corriger un objet doit garder au poste le HTML qu'il y
 * avait ; l'aplatir en texte serait une perte que personne n'a demandée.
 */
suspend fun pushServerDraft(draft: MailDraft): ServerDraftSaveResponse {
    val formatted = !draft.bodyUntouched && RichText.hasFormatting(draft.body)
    return Graph.mail.saveServerDraft(
        id = draft.serverId,
        // Vide veut dire « je n'ai rien lu » : le serveur écrit alors sans
        // comparer, ce qui est le bon comportement pour une reprise forcée.
        version = draft.serverVersion.ifBlank { null },
        subject = draft.subject,
        body = when {
            draft.bodyUntouched -> null
            formatted -> RichText.toHtml(draft.body)
            // Nettoyé aussi en texte brut : une paire de gras armée puis
            // laissée vide partait au poste en « **** » (#25764).
            else -> RichText.nettoyer(draft.body)
        },
        bodyIsHtml = formatted,
        to = draft.to,
        attachmentIds = draft.attachments.map { it.attachmentId },
    )
}
