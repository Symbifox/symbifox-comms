package com.bluefoxconsultant.sms.data

import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Whether this instance offers the assistant, and on what terms.
 *
 * `readonly` is not a setting the app can change: mobile conversations go
 * through the bridge's read-only endpoint by construction. It is surfaced so
 * the screen can say so rather than let someone ask for something that will
 * quietly not happen.
 */
@Serializable
data class GenfoxConfig(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    val readonly: Boolean = true,
    val version: String = "",
    /**
     * Le niveau de la surface mobile. 4 (bf_claude_chat 18.0.1.23.0, #25734) :
     * `/stop`, `busy` par conversation, `end_reason`, et `/ask` qui refuse une
     * seconde question dans une conversation qui travaille.
     */
    val api: Int = 0,
) {
    /** Le bouton Arrêter n'a de sens que si le serveur sait arrêter. */
    val canStop: Boolean get() = api >= 4
}

@Serializable
data class GenfoxSession(
    val id: Int = 0,
    val name: String = "",
    @SerialName("write_date") val writeDate: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    /** Gen travaille encore dans cette conversation (api 4). */
    val busy: Boolean = false,
    @SerialName("turn_id")
    @Serializable(with = FalseAsNullIntSerializer::class)
    val turnId: Int? = null,
)

@Serializable
data class GenfoxSessionsResponse(val sessions: List<GenfoxSession> = emptyList())

/** One tool the assistant reached for, in the order it did. */
@Serializable
data class GenfoxTool(
    val name: String = "",
    /** Character offset in the answer when the call started — used to order. */
    val at: Int = 0,
    /**
     * La ligne lisible de l'appel (« Lecture des tâches du projet »), que le
     * pont relaie une fois l'entrée de l'outil complète (BF #25718). Vide
     * avec un Odoo plus ancien que bf_claude_chat 18.0.1.21.0, ou quand Gen
     * n'a pas décrit sa commande.
     */
    val detail: String = "",
) {
    /** `mcp__tentaclaude-bf__odoo_get_task` reads as `odoo_get_task`. */
    val short: String get() = name.substringAfterLast("__").ifBlank { name }

    /**
     * Ce que l'écran affiche pour cette étape : la description, sinon le libellé.
     * La description est écrite par Gen, dans sa langue : elle passe telle quelle.
     */
    val texte: UiText get() = detail.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: libelle

    /**
     * Libellé de repli, le même que celui du panneau web (gen_wait.js) : si
     * l'un change, l'autre doit suivre, sinon les deux écrans se contredisent.
     * ⚠️ Dans les DEUX langues : l'anglais de `values` est le msgid de
     * gen_wait.js mot pour mot, le français celui de son `fr_CA.po`.
     */
    val libelle: UiText
        get() {
            val nom = short
            val connu = when (nom) {
                "Bash" -> R.string.gen_tool_running_check
                "WebSearch" -> R.string.gen_tool_searching_web
                "WebFetch" -> R.string.gen_tool_reading_web_page
                "Read", "Glob", "Grep" -> R.string.gen_tool_reading_files
                "Write", "Edit" -> R.string.gen_tool_writing_file
                "ToolSearch" -> R.string.gen_tool_picking_tools
                "Skill" -> R.string.gen_tool_following_procedure
                "odoo_get_task" -> R.string.gen_tool_reading_task
                "odoo_list_project_tasks" -> R.string.gen_tool_looking_through_tasks
                "odoo_add_task_comment" -> R.string.gen_tool_writing_task_note
                "email_read_message" -> R.string.gen_tool_reading_email
                "email_create_draft", "odoo_schedule_chatter_email" -> R.string.gen_tool_drafting_email
                else -> null
            }
            if (connu != null) return uiText(connu)
            val verbe = nom.replace(Regex("^(odoo|email|nc|pb|zoho|sync)_"), "")
            fun commence(vararg v: String) = v.any { verbe.startsWith(it + "_") }
            return when {
                nom.startsWith("email_") -> uiText(R.string.gen_tool_searching_mailbox)
                nom.startsWith("nc_") ->
                    if (commence("write", "create", "delete", "nc_create")) uiText(R.string.gen_tool_writing_nextcloud)
                    else uiText(R.string.gen_tool_looking_nextcloud)
                nom.startsWith("pb_") -> uiText(R.string.gen_tool_preparing_secure_link)
                nom.startsWith("odoo_") || nom.startsWith("zoho_") -> when {
                    commence("get", "read", "export") -> uiText(R.string.gen_tool_reading_odoo)
                    commence("list", "search") -> uiText(R.string.gen_tool_searching_odoo)
                    else -> uiText(R.string.gen_tool_writing_odoo)
                }
                // Le nom technique de l'outil : rien à traduire.
                else -> UiText.Raw(nom.removePrefix("odoo_").replace('_', ' '))
            }
        }
}

/**
 * Ce qu'un tour a consommé.
 *
 * ⚠️ [totalTokens] additionne le contexte RELU, qui n'est pas du travail neuf :
 * c'est le même contexte relu à chaque pas interne du tour, facturé au dixième
 * du prix. Il représentait ~93 % du volume mensuel, et c'est ce qui faisait
 * afficher « 50 k jetons » pour un bonjour — vrai, et incompréhensible.
 * L'écran montre donc [displayTokens], la même grandeur que le Cockpit Odoo et
 * que le panneau web. Trois surfaces, un seul chiffre.
 */
@Serializable
data class GenfoxUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Int = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int = 0,
    @SerialName("net_tokens") val netTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("cost_usd") val costUsd: Double = 0.0,
    @SerialName("duration_ms") val durationMs: Int = 0,
) {
    val hasAny: Boolean get() = totalTokens > 0 || outputTokens > 0

    /**
     * Les jetons à afficher : neufs, sans le contexte relu.
     *
     * Recalculé à défaut, pour qu'un serveur plus ancien — qui ne sert pas
     * `net_tokens` — donne quand même le bon chiffre au lieu de zéro.
     */
    val displayTokens: Int
        get() = if (netTokens > 0) netTokens
        else inputTokens + cacheWriteTokens + outputTokens
}

@Serializable
data class GenfoxMessage(
    val id: Int = 0,
    val role: String = "",
    val content: String = "",
    /** `pending` while the assistant is still working on that turn. */
    val state: String = "done",
    val tools: List<GenfoxTool> = emptyList(),
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Int = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int = 0,
    @SerialName("net_tokens") val netTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("cost_usd") val costUsd: Double = 0.0,
    @SerialName("duration_ms") val durationMs: Int = 0,
    /** Pourquoi le tour n'a pas fini normalement ; `stopped` après Arrêter. */
    @SerialName("end_reason")
    @Serializable(with = FalseAsEmptyStringSerializer::class)
    val endReason: String = "",
    /**
     * Le texte d'une bulle d'erreur posée par l'app elle-même (question non
     * posée, tour trop long), dans la langue du téléphone. Jamais sérialisé :
     * le serveur n'en sait rien, et [content] garde ce que LUI a écrit.
     */
    @Transient val avis: UiText? = null,
) {
    val usage: GenfoxUsage
        get() = GenfoxUsage(inputTokens, outputTokens, cacheReadTokens,
                            cacheWriteTokens, netTokens, totalTokens,
                            costUsd, durationMs)

    val isUser: Boolean get() = role == "user"
    val isPending: Boolean get() = state == "pending"

    /**
     * Arrêté à la demande : enregistré en `error` côté serveur, mais ce n'est
     * pas une panne, et l'écran ne le peint pas comme telle.
     */
    val isStopped: Boolean get() = endReason == "stopped"
    val isError: Boolean get() = state == "error" && !isStopped
}

@Serializable
data class GenfoxMessagesResponse(
    @SerialName("session_id") val sessionId: Int = 0,
    @SerialName("session_name") val sessionName: String = "",
    val messages: List<GenfoxMessage> = emptyList(),
)

@Serializable
data class AskRequest(
    val message: String,
    @SerialName("session_id") val sessionId: Int? = null,
)

@Serializable
data class AskResponse(
    val ok: Boolean = false,
    @SerialName("session_id") val sessionId: Int = 0,
    @SerialName("turn_id") val turnId: Int = 0,
    val state: String = "pending",
)

@Serializable
data class TurnResponse(
    @SerialName("turn_id") val turnId: Int = 0,
    @SerialName("session_id") val sessionId: Int = 0,
    @SerialName("session_name") val sessionName: String = "",
    val state: String = "pending",
    /** Grows between polls while the answer is being written. */
    val text: String = "",
    val tools: List<GenfoxTool> = emptyList(),
    val usage: GenfoxUsage = GenfoxUsage(),
    @SerialName("end_reason")
    @Serializable(with = FalseAsEmptyStringSerializer::class)
    val endReason: String = "",
)
