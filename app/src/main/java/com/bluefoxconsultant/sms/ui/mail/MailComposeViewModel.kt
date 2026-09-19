package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Adresses
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.LectureBornee
import com.bluefoxconsultant.sms.data.MailConfig
import com.bluefoxconsultant.sms.data.MailContact
import com.bluefoxconsultant.sms.data.MailDraft
import com.bluefoxconsultant.sms.data.MailIdentity
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.data.PendingAction
import com.bluefoxconsultant.sms.data.PieceProposee
import com.bluefoxconsultant.sms.data.RecordRef
import com.bluefoxconsultant.sms.data.ServerDraft
import com.bluefoxconsultant.sms.data.SharedContent
import com.bluefoxconsultant.sms.data.StagedUpload
import com.bluefoxconsultant.sms.data.isOffline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.StringRes
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiPlural
import com.bluefoxconsultant.sms.ui.uiText

/**
 * Le plafond d'une pièce jointe, celui du serveur : `UPLOAD_SINGLE_MAX` de
 * `bf_email_management/models/bf_email_mobile.py`, 25 Mio. Le reprendre ici
 * permet de refuser AVANT de lire, au lieu de charger le fichier pour se faire
 * répondre 413. ⚠️ Le serveur borne aussi le TOTAL d'un envoi au même chiffre.
 */
private const val PLAFOND_PIECE = 25L * 1024 * 1024

/** `bf.recipient.group._confirm_above` du poste : au-delà, on prévient. */
private const val SEUIL_GROUPE_VISIBLE = 10

/**
 * Composer for all four modes.
 *
 * **Une réponse montre ses destinataires (#25764).** Jusqu'à la 2.43, pour
 * `reply` et `reply_all` les champs restaient vides et le serveur calculait
 * les destinataires à l'envoi, sans que personne les voie. Avec un serveur qui
 * annonce `compose_api >= 2`, le composeur les demande à l'ouverture
 * (`/reply/prepare`), les montre en pastilles, et renvoie la liste retouchée.
 * Sans réseau à l'ouverture, ou avec un serveur plus ancien, il retombe sur le
 * comportement d'avant : rien d'affiché, le serveur décide.
 *
 * `forward` et un message neuf n'ont pas de destinataire par défaut : le champ
 * y est obligatoire.
 */
class MailComposeViewModel(
    val mode: String,
    private val emailId: Int,
    resumed: String = "",
    /** Le brouillon du POSTE qu'on reprend, ou 0 (#25579). */
    private val serverDraftId: Int = 0,
) : ViewModel() {

    /**
     * L'identité du brouillon de ce composeur.
     *
     * Attribuée dès l'ouverture, même pour un message neuf : elle doit être
     * stable d'une sauvegarde à l'autre, sinon quitter l'écran deux fois
     * laisserait deux brouillons du même texte.
     *
     * Pour la reprise d'un brouillon du poste, elle est DÉRIVÉE de son
     * identifiant : rouvrir le même brouillon deux fois doit retrouver la
     * même reprise locale, pas en semer une deuxième.
     */
    private val draftId: String = when {
        resumed.isNotBlank() -> resumed
        serverDraftId > 0 -> "srv-$serverDraftId"
        else -> PendingAction.newToken()
    }

    val isServerDraft: Boolean get() = serverDraftId > 0
    val isNew: Boolean get() = mode == "new"
    val isReply: Boolean get() = mode == "reply" || mode == "reply_all"

    /**
     * Ce que l'instance sait faire, lu du cache que la liste a posé. Relu au
     * serveur si le cache manque : un composeur ouvert depuis une notification
     * peut précéder la liste.
     */
    var config by mutableStateOf(Graph.mailCache.loadConfig() ?: MailConfig())
        private set

    /** Les nouveautés de #25764 ne s'offrent qu'à un serveur qui les annonce. */
    val complet: Boolean get() = config.composeurComplet && !isServerDraft

    /** Vrai le temps d'aller chercher le brouillon du poste. */
    var loading by mutableStateOf(false)
        private set

    /**
     * La version lue au poste, renvoyée à chaque écriture.
     *
     * Vide tant qu'on n'a rien lu : le serveur écrit alors sans comparer, ce
     * qui est le bon comportement pour une reprise qu'on force après un
     * conflit.
     */
    private var serverVersion: String = ""

    /**
     * Le corps HTML tel que le poste l'a écrit.
     *
     * Gardé pour NE PAS le réenvoyer aplati : le composeur édite du texte, et
     * un aller-retour HTML → texte → HTML détruirait la mise en forme de
     * quelqu'un qui n'a touché qu'à l'objet. Tant que [body] n'a pas bougé,
     * l'écriture n'emporte pas le corps du tout.
     */
    private var serverBodyHtml: String = ""
    private var initialBody: String = ""
    private var initialSubject: String = ""
    private var initialTo: List<String> = emptyList()
    private var initialAttachments: List<Int> = emptyList()

    /** Ce que le serveur porte, quand il a refusé une écriture périmée. */
    var conflict by mutableStateOf<ServerDraft?>(null)
        private set

    // ------------------------------------------------------ destinataires
    var to by mutableStateOf("")
    var cc by mutableStateOf("")
    var bcc by mutableStateOf("")

    /** Confirmed recipients, kept apart from what is still being typed. */
    var toChips by mutableStateOf<List<String>>(emptyList())
        private set
    var ccChips by mutableStateOf<List<String>>(emptyList())
        private set
    var bccChips by mutableStateOf<List<String>>(emptyList())
        private set

    /** Les champs Cc et Cci sont-ils dépliés ? Ils le sont dès qu'ils portent quelqu'un. */
    var copiesVisibles by mutableStateOf(false)
        private set

    /**
     * Les destinataires d'une réponse ont-ils été préparés par le serveur ?
     * Tant que non, une réponse part vers ceux que le serveur calcule, et les
     * champs ne sont pas montrés (voir la doc de la classe).
     */
    var destinatairesPrepares by mutableStateOf(false)
        private set
    var preparation by mutableStateOf(false)
        private set
    var preparationEchouee by mutableStateOf(false)
        private set

    val champsDestinataires: Boolean
        get() = !isReply || destinatairesPrepares

    var suggestions by mutableStateOf<List<MailContact>>(emptyList())
        private set
    /** Which field the suggestions belong to: "to", "cc" or "bcc". */
    var suggestingFor by mutableStateOf("")
        private set

    private var lookupJob: Job? = null

    // -------------------------------------------------------- en-tête
    var subject by mutableStateOf("")

    /** L'adresse d'envoi choisie ; `null` = celle que propose le serveur. */
    var identityId by mutableStateOf<Int?>(null)
        private set

    val identites: List<MailIdentity> get() = if (complet) config.identities else emptyList()

    /**
     * L'adresse affichée dans « De ».
     *
     * ⚠️ `null` pour une réponse dont l'adresse n'est pas connue (préparation
     * pas encore revenue, ou impossible hors ligne) : le serveur répondra
     * alors depuis la boîte qui a reçu. Afficher ici l'adresse par défaut
     * mentirait, et l'ENVOYER écraserait ce bon choix du serveur.
     */
    val identite: MailIdentity?
        get() = identites.firstOrNull { it.id == identityId }
            ?: if (isNew) identites.firstOrNull { it.isDefault } ?: identites.firstOrNull() else null

    /** La fiche où classer un message neuf, choisie au composeur. */
    var fiche by mutableStateOf<RecordRef?>(null)
        private set

    // ---------------------------------------------------------- corps
    /**
     * Le corps, curseur compris : la barre de mise en forme et les
     * corrections de frappe ([RichText.corrigerEdition]) ont besoin de la
     * sélection, et la garder ici lui fait survivre à une rotation.
     */
    var corps by mutableStateOf(TextFieldValue(""))
        private set

    val body: String get() = corps.text

    // ------------------------------------------------ message d'origine
    var original by mutableStateOf<MailMessage?>(null)
        private set
    var originalIndisponible by mutableStateOf(false)
        private set
    private var originalDemande = false

    val aUnOriginal: Boolean get() = !isNew && emailId > 0 && !isServerDraft

    // ----------------------------------------------------------- envoi
    var sending by mutableStateOf(false)
        private set
    /** Rédigé à l'écran, dans la langue du téléphone : voir [UiText]. */
    var error by mutableStateOf<UiText?>(null)
        private set
    /** Une information à dire une fois (groupe déplié, envoi en file…). */
    var info by mutableStateOf<UiText?>(null)
        private set
    /** Set when the send was queued instead of delivered. */
    var queuedOffline by mutableStateOf(false)
        private set

    /**
     * Generated once per composer, before the first attempt, and reused if the
     * send is queued and replayed. The server refuses a repeat of the same
     * token, which is what stops a lost response from becoming a second copy
     * in the correspondent's inbox.
     */
    private var clientToken = PendingAction.newToken()

    /** Files already staged server-side, ready for the send to claim. */
    var attachments by mutableStateOf<List<StagedUpload>>(emptyList())
        private set
    var uploading by mutableStateOf(0)
        private set

    /** Fichiers partagés, pas encore lus : ils attendent un geste. Voir [adopt]. */
    var proposees by mutableStateOf<List<PieceProposee>>(emptyList())
        private set

    /** L'objet proposé par le serveur, qu'on ne compte pas comme une écriture. */
    private var sujetPrepare: String = ""

    /** Vrai une fois « Jeter » confirmé : quitter ne regarde plus rien. */
    private var jete = false

    init {
        // Reprise d'un brouillon : on remet l'écran exactement là où il était.
        // Les destinataires reviennent en pastilles et non dans le champ de
        // saisie — ils étaient confirmés quand on a quitté, les retaper serait
        // une occasion de plus de se tromper d'adresse.
        val local = Graph.drafts.get(draftId)
        // 🔴 Tout ce que ce bloc écrit doit être DÉCLARÉ AU-DESSUS de lui. Une
        // propriété déléguée (`by mutableStateOf`) n'a son délégué qu'une fois
        // sa ligne atteinte : l'écrire avant lève un NullPointerException. Un
        // commentaire disait qu'un `let` « échappait » au contrôle du
        // compilateur ; il y échappait, et l'app plantait à l'exécution dès
        // qu'on rouvrait un brouillon gardé sur l'appareil (relevé à
        // l'émulateur le 2026-09-16, #25764 ; le même code est dans la 2.43).
        local?.let { draft ->
            toChips = draft.to
            ccChips = draft.cc
            bccChips = draft.bcc
            subject = draft.subject
            corps = TextFieldValue(draft.body, TextRange(draft.body.length))
            attachments = draft.attachments
            identityId = draft.identityId
            destinatairesPrepares = draft.recipientsPrepared
            fiche = draft.resModel?.let { m ->
                draft.resId?.let { RecordRef(model = m, id = it, name = draft.recordName) }
            }
            copiesVisibles = draft.cc.isNotEmpty() || draft.bcc.isNotEmpty()
            // Une reprise gardée sur l'appareil porte la version qu'elle avait
            // lue : la remontée doit continuer de se comparer à celle-là, pas
            // repartir de zéro.
            serverVersion = draft.serverVersion
            snapshot()
        }
        if (isServerDraft) loadServerDraft(overwrite = local == null)
        viewModelScope.launch {
            if (Graph.mailCache.loadConfig() == null) {
                runCatching { Graph.mail.config() }.getOrNull()?.let {
                    Graph.mailCache.saveConfig(it)
                    config = it
                }
            }
            if (isReply || mode == "forward") preparer()
        }
    }

    // ============================================================ préparation

    /**
     * Demander au serveur ce qu'une réponse enverrait (#25764).
     *
     * Une reprise de brouillon dont les destinataires ont déjà été préparés ne
     * redemande rien : ce qui est à l'écran est ce que la personne a retouché.
     * Pour un transfert, seules l'adresse d'envoi et l'objet comptent.
     */
    fun preparer() {
        if (!complet || emailId <= 0) return
        if (isReply && destinatairesPrepares) return
        viewModelScope.launch {
            preparation = true
            preparationEchouee = false
            try {
                val prep = Graph.mail.prepareReply(emailId, mode)
                if (isReply && !destinatairesPrepares) {
                    toChips = Destinataires.ajouter(toChips, prep.to.map { it.enPastille }, ccChips, bccChips)
                    ccChips = Destinataires.ajouter(ccChips, prep.cc.map { it.enPastille }, toChips, bccChips)
                    if (ccChips.isNotEmpty()) copiesVisibles = true
                    destinatairesPrepares = true
                }
                if (subject.isBlank()) {
                    subject = prep.subject
                    sujetPrepare = prep.subject
                }
                if (identityId == null) identityId = prep.identityId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                preparationEchouee = true
            } finally {
                preparation = false
            }
        }
    }

    // ========================================================= destinataires

    /**
     * Look the address book up as the user types, debounced.
     *
     * Typing an address by hand still works — the field is free text and is
     * merged with the chips at send time. Completion is an accelerator, not a
     * gate: an address that isn't in Contacts yet must remain sendable.
     */
    fun onRecipientInput(field: String, value: String) {
        when (field) {
            "cc" -> cc = value
            "bcc" -> bcc = value
            else -> to = value
        }
        suggestingFor = field
        lookupJob?.cancel()
        val term = value.substringAfterLast(',').trim()
        if (term.length < 2) {
            suggestions = emptyList()
            return
        }
        val groupes = complet && config.recipientGroups
        lookupJob = viewModelScope.launch {
            delay(250)
            suggestions = runCatching { Graph.mail.contacts(term, groups = groupes) }
                .getOrDefault(emptyList())
        }
    }

    fun pickSuggestion(contact: MailContact) {
        if (contact.isGroup) {
            ajouterGroupe(contact)
        } else {
            val adresse = Adresses.formater(contact.name, contact.address)
            when (suggestingFor) {
                "cc" -> { ccChips = Destinataires.ajouter(ccChips, listOf(adresse), toChips, bccChips); cc = "" }
                "bcc" -> { bccChips = Destinataires.ajouter(bccChips, listOf(adresse), toChips, ccChips); bcc = "" }
                else -> { toChips = Destinataires.ajouter(toChips, listOf(adresse), ccChips, bccChips); to = "" }
            }
        }
        suggestions = emptyList()
    }

    /**
     * Un groupe se déplie à l'écran, dans le champ que le GROUPE désigne —
     * la même règle qu'au poste : un groupe « en Cci » y va, où qu'on l'ait
     * tapé. On ne signe pas un envoi dont on ne voit pas les destinataires.
     */
    private fun ajouterGroupe(groupe: MailContact) {
        val membres = groupe.members.filter { it.email.isNotBlank() }.map { it.enPastille }
        val champ = when (groupe.field) { "cc", "bcc" -> groupe.field; else -> "to" }
        val avant = toChips.size + ccChips.size + bccChips.size
        when (champ) {
            "cc" -> ccChips = Destinataires.ajouter(ccChips, membres, toChips, bccChips)
            "bcc" -> bccChips = Destinataires.ajouter(bccChips, membres, toChips, ccChips)
            else -> toChips = Destinataires.ajouter(toChips, membres, ccChips, bccChips)
        }
        if (champ != "to") copiesVisibles = true
        when (suggestingFor) {
            "cc" -> cc = ""
            "bcc" -> bcc = ""
            else -> to = ""
        }
        val ajoutes = toChips.size + ccChips.size + bccChips.size - avant
        // Le même seuil que le poste : au-delà, chacun lira l'adresse des autres.
        if (champ != "bcc" && toChips.size + ccChips.size > SEUIL_GROUPE_VISIBLE) {
            info = uiPlural(R.plurals.mail_compose_group_visible_warning,
                            toChips.size + ccChips.size, toChips.size + ccChips.size)
            return
        }
        info = uiPlural(
            when (champ) {
                "cc" -> R.plurals.mail_compose_group_added_cc
                "bcc" -> R.plurals.mail_compose_group_added_bcc
                else -> R.plurals.mail_compose_group_added_to
            },
            ajoutes, groupe.name, ajoutes,
        )
    }

    fun removeChip(field: String, address: String) {
        when (field) {
            "cc" -> ccChips = ccChips - address
            "bcc" -> bccChips = bccChips - address
            else -> toChips = toChips - address
        }
    }

    fun montrerCopies() {
        copiesVisibles = true
    }

    private fun tousA(): List<String> = Destinataires.ajouter(toChips, Destinataires.saisie(to))
    private fun tousCc(): List<String> = Destinataires.ajouter(ccChips, Destinataires.saisie(cc))
    private fun tousCci(): List<String> = Destinataires.ajouter(bccChips, Destinataires.saisie(bcc))

    // ================================================================ en-tête

    fun choisirIdentite(identite: MailIdentity) {
        identityId = identite.id
    }

    fun classerSur(record: RecordRef?) {
        fiche = record
    }

    // ================================================================= corps

    /**
     * Ce que le champ propose, corrigé pour que les marqueurs cachés restent
     * cohérents (voir [RichText.corrigerEdition]). Sans correction, la valeur
     * du champ passe telle quelle — composition du clavier comprise, sans
     * quoi la saisie prédictive perdrait le mot en cours à chaque lettre.
     */
    fun onCorps(valeur: TextFieldValue) {
        val avant = corps.text
        val correction = if (valeur.text != avant)
            RichText.corrigerEdition(avant, valeur.text, valeur.selection.end) else null
        corps = if (correction == null) valeur
        else TextFieldValue(correction.texte, TextRange(correction.selDebut, correction.selFin))
    }

    fun basculer(style: RichText.Style) {
        val sel = corps.selection
        val e = RichText.basculer(corps.text, sel.min, sel.max, style)
        corps = TextFieldValue(e.texte, TextRange(e.selDebut, e.selFin))
    }

    fun basculerPuce() {
        val (texte, curseur) = RichText.applyLinePrefix(corps.text, corps.selection.start, "- ")
        corps = TextFieldValue(texte, TextRange(curseur))
    }

    /** Dictation extends the message where the caret is, not only at the end. */
    fun inserer(parle: String) {
        val t = corps.text
        val c = corps.selection.end.coerceIn(0, t.length)
        val avantC = t.substring(0, c)
        val sep = if (avantC.isNotEmpty() && !avantC.last().isWhitespace()) " " else ""
        val texte = avantC + sep + parle + t.substring(c)
        corps = TextFieldValue(texte, TextRange(c + sep.length + parle.length))
    }

    val stylesActifs: Set<RichText.Style>
        get() = RichText.stylesActifs(corps.text, corps.selection.min, corps.selection.max)

    val puceActive: Boolean get() = RichText.estPuce(corps.text, corps.selection.start)

    // ============================================================ original

    /** Charger le message d'origine, une fois, quand on le montre. */
    fun chargerOriginal() {
        if (!aUnOriginal || originalDemande) return
        originalDemande = true
        viewModelScope.launch {
            try {
                original = Graph.mail.message(emailId)
                originalIndisponible = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                originalIndisponible = true
                originalDemande = false
            }
        }
    }

    // ============================================================= brouillons

    /**
     * Va chercher le brouillon du poste.
     *
     * [overwrite] est faux quand une reprise locale attend déjà de remonter :
     * on lit alors le serveur pour le corps HTML d'origine et rien d'autre.
     * Écraser l'écran avec la version distante effacerait ce que la personne
     * a écrit la dernière fois sans réseau, ce qui est précisément le texte
     * qu'on s'était promis de ne plus perdre.
     */
    private fun loadServerDraft(overwrite: Boolean) {
        viewModelScope.launch {
            loading = true
            try {
                val draft = Graph.mail.serverDraft(serverDraftId)
                serverBodyHtml = draft.bodyHtml
                if (overwrite) {
                    serverVersion = draft.version
                    toChips = draft.to
                    subject = draft.subject
                    corps = TextFieldValue(draft.bodyText, TextRange(draft.bodyText.length))
                    attachments = draft.attachments.map {
                        StagedUpload(ok = true, attachmentId = it.id,
                                     name = it.name, size = it.size,
                                     mimetype = it.mimetype)
                    }
                    snapshot()
                }
            } catch (e: Exception) {
                error = if (e.isOffline())
                    uiText(R.string.mail_compose_server_draft_offline)
                else uiText(R.string.mail_compose_server_draft_missing)
            } finally {
                loading = false
            }
        }
    }

    /** Fige l'état lu, pour savoir ensuite ce que la personne a vraiment changé. */
    private fun snapshot() {
        initialBody = body
        initialSubject = subject
        initialTo = toChips
        initialAttachments = attachments.map { it.attachmentId }
    }

    /** La personne a-t-elle touché à quoi que ce soit depuis la lecture ? */
    private fun dirty(): Boolean =
        subject != initialSubject ||
            body != initialBody ||
            tousA() != initialTo ||
            attachments.map { it.attachmentId } != initialAttachments

    /** L'état de l'écran, en brouillon de l'appareil. */
    private fun brouillon(): MailDraft = MailDraft(
        id = draftId,
        mode = mode,
        emailId = emailId,
        to = tousA(),
        cc = tousCc(),
        bcc = tousCci(),
        subject = subject,
        body = body,
        attachments = attachments,
        serverId = serverDraftId,
        serverVersion = serverVersion,
        bodyUntouched = isServerDraft && body == initialBody,
        identityId = identityId,
        recipientsPrepared = destinatairesPrepares,
        resModel = fiche?.model,
        resId = fiche?.id,
        recordName = fiche?.name.orEmpty(),
    )

    /**
     * Garde ce qui est écrit, au moment de quitter sans envoyer.
     *
     * Le magasin efface de lui-même un brouillon vidé : il n'y a donc rien à
     * décider ici entre « enregistrer » et « supprimer », et un écran ouvert
     * par erreur ne laisse pas de ligne à nettoyer. Rend vrai quand quelque
     * chose a bel et bien été gardé, pour que l'écran puisse le dire.
     *
     * ⚠️ Une réponse que personne n'a touchée n'est pas un brouillon, même si
     * ses destinataires ont été préparés : ouvrir « Répondre » puis revenir ne
     * doit pas laisser une ligne dans les brouillons.
     */
    fun saveDraft(): Boolean {
        if (jete) return false
        // Une reprise du poste que personne n'a touchée n'a rien à garder ici,
        // et surtout rien à remonter : la laisser en attente ferait réécrire
        // le brouillon du poste avec sa propre copie, et le seul effet
        // possible serait d'en aplatir le corps.
        if (isServerDraft && !dirty()) {
            Graph.drafts.delete(draftId)
            return false
        }
        val d = brouillon()
        // Des destinataires tapés dans un transfert sont une écriture ; ceux
        // qu'une réponse a reçus du serveur n'en sont pas.
        val rienEcrit = RichText.nettoyer(d.body).isBlank() && d.attachments.isEmpty() &&
            (d.subject.isBlank() || d.subject == sujetPrepare) &&
            (isReply || d.to.isEmpty() && d.cc.isEmpty() && d.bcc.isEmpty())
        if (!isNew && rienEcrit && Graph.drafts.get(draftId) == null) {
            return false
        }
        return Graph.drafts.save(d) != null
    }



    /**
     * Jeter le brouillon sur place.
     *
     * Celui de l'appareil disparaît. Celui du poste est supprimé au serveur :
     * c'est le geste « Jeter » de la liste, confirmé à l'écran.
     */
    fun jeter(onFait: () -> Unit) {
        jete = true
        Graph.drafts.delete(draftId)
        if (!isServerDraft) {
            onFait()
            return
        }
        viewModelScope.launch {
            try {
                Graph.mail.deleteServerDraft(serverDraftId)
                onFait()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                jete = false
                error = if (e.isOffline()) uiText(R.string.mail_compose_server_draft_needs_network)
                else uiText(R.string.mail_delete_failed)
            }
        }
    }

    /**
     * Reprend ce qu'une autre app vient de partager vers Comms.
     *
     * L'objet du partage devient l'objet du courriel — c'est exactement ce
     * qu'`EXTRA_SUBJECT` veut dire — et le texte, souvent un lien, le corps.
     * Pré-remplir du texte n'envoie rien : la personne le voit et le relit.
     *
     * 🔴 Les fichiers, eux, ne sont NI lus NI téléversés ici (audit du
     * 2026-09-08, C-M2). Ils deviennent des [proposees], et ne partent qu'au
     * geste : « Joindre », ou « Envoyer ». Avant, avec une seule moitié
     * connectée, le partage allait droit au composeur et la pièce montait au
     * serveur avant que quiconque ait regardé l'écran.
     *
     * ⚠️ N'écrase rien : reprendre un brouillon puis recevoir un partage ne
     * doit pas effacer ce qui était déjà écrit.
     */
    fun adopt(context: Context, shared: SharedContent) {
        if (subject.isBlank()) subject = shared.subject
        if (shared.text.isNotBlank()) {
            val texte = if (body.isBlank()) shared.text else body + "\n\n" + shared.text
            corps = TextFieldValue(texte, TextRange(texte.length))
        }
        if (shared.uris.isEmpty()) return
        proposees = proposees + shared.uris.map {
            PieceProposee(it, it.lastPathSegment ?: "piece-jointe")
        }
        // Le nom et la taille, sans un octet du contenu : de quoi reconnaître
        // la pièce et savoir tout de suite qu'elle ne passera pas.
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            val lues = withContext(Dispatchers.IO) {
                shared.uris.associateWith { LectureBornee.metadonnees(resolver, it) }
            }
            proposees = proposees.map { p ->
                lues[p.uri]?.let { p.copy(nom = it.nom, taille = it.taille) } ?: p
            }
        }
    }


    /** « Joindre » : le geste qui autorise la lecture et le téléversement. */
    fun joindreProposee(context: Context, piece: PieceProposee) {
        proposees = proposees - piece
        attach(context, piece.uri)
    }

    /** Écartée sans avoir été lue. */
    fun ecarterProposee(piece: PieceProposee) {
        proposees = proposees - piece
    }

    @get:StringRes
    val titleRes: Int get() = when (mode) {
        "reply" -> R.string.mail_reply
        "reply_all" -> R.string.mail_reply_all
        "forward" -> R.string.mail_forward
        else -> R.string.common_new_email
    }

    /**
     * Upload a picked file immediately rather than at send time.
     *
     * The user keeps typing while it goes up, and a failure surfaces now —
     * when there is still a composer to fix it in — instead of turning into a
     * failed send after they hit the button.
     */
    fun attach(context: Context, uri: Uri) {
        // Le contexte d'APPLICATION : la coroutine survit à une rotation, et
        // elle ne doit pas retenir l'activité qu'elle vient de quitter.
        val app = context.applicationContext
        uploading += 1
        viewModelScope.launch {
            try {
                televerser(app, uri)
            } finally {
                uploading -= 1
            }
        }
    }

    /**
     * Lit la pièce sous plafond, puis la téléverse. Rend vrai si elle est jointe.
     *
     * Le plafond est celui du serveur, vérifié AVANT de lire : sur la taille
     * annoncée d'abord, puis octet par octet (Q-M4, C-F4). Un échec pose
     * [error] et rend faux ; il ne lève pas, sauf l'annulation.
     */
    private suspend fun televerser(context: Context, uri: Uri): Boolean {
        val lue = try {
            withContext(Dispatchers.IO) {
                LectureBornee.lireUri(context.contentResolver, uri, PLAFOND_PIECE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LectureBornee.TropGros) {
            error = uiText(R.string.mail_compose_file_too_large)
            return false
        } catch (e: LectureBornee.MemoireInsuffisante) {
            error = uiText(R.string.mail_compose_out_of_memory)
            return false
        } catch (e: Exception) {
            error = uiText(R.string.mail_compose_file_unreadable)
            return false
        }
        if (lue.octets.isEmpty()) {
            error = uiText(R.string.mail_compose_file_unreadable)
            return false
        }
        return try {
            attachments = attachments + Graph.mail.uploadAttachment(lue.nom, lue.type, lue.octets)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (e.message == "too_large") uiText(R.string.mail_compose_file_too_large)
            else uiText(R.string.mail_compose_upload_failed)
            false
        }
    }

    fun removeAttachment(staged: StagedUpload) {
        attachments = attachments.filterNot { it.attachmentId == staged.attachmentId }
    }

    // ================================================================ envoi

    /**
     * L'envoi, tel qu'il partira : tout ce que l'écran porte, figé une fois.
     *
     * 🔴 C'est la même valeur qui part tout de suite, qui attend son délai
     * d'annulation, ou qui patiente hors ligne. Avant #25764, la file hors
     * ligne en recopiait une partie à la main, et perdait en route les pièces
     * jointes, les Cc en pastilles et la mise en forme.
     */
    private fun envoi(scheduledMs: Long?): PendingAction {
        val propre = RichText.nettoyer(body)
        val formate = RichText.hasFormatting(propre)
        val prepare = !isReply || destinatairesPrepares
        return PendingAction(
            token = clientToken,
            kind = if (isNew) PendingAction.KIND_COMPOSE else PendingAction.KIND_REPLY,
            createdMs = System.currentTimeMillis(),
            emailId = emailId,
            mode = mode,
            body = if (formate) RichText.toHtml(propre) else propre,
            bodyIsHtml = formate,
            bodySource = body,
            subject = subject,
            // Une réponse non préparée laisse le serveur calculer : rien ne
            // voyage, comme avant.
            to = if (prepare) tousA().ifEmpty { null } else null,
            cc = if (prepare) tousCc().ifEmpty { null } else null,
            bcc = if (complet && prepare) tousCci().ifEmpty { null } else null,
            attachments = attachments,
            // Seulement une adresse CHOISIE ou PRÉPARÉE : l'identité par
            // défaut d'un message neuf vient du cache, et l'envoyer ferait
            // refuser l'envoi si elle a été retirée depuis. Sans rien, le
            // serveur prend son défaut, qui est le même.
            identityId = if (complet) identityId else null,
            resModel = if (complet && isNew) fiche?.model else null,
            resId = if (complet && isNew) fiche?.id else null,
            recordName = fiche?.name.orEmpty(),
            scheduledMs = scheduledMs,
            recipientsPrepared = destinatairesPrepares,
        )
    }

    /**
     * Envoyer, maintenant ou à [scheduledMs].
     *
     * [onSent] reçoit ce que la liste doit dire en arrivant : le composeur
     * s'est déjà fermé.
     */
    fun send(context: Context, onSent: (UiText?) -> Unit, scheduledMs: Long? = null) {
        if (sending) return
        if (uploading > 0) {
            error = uiText(R.string.mail_compose_upload_in_progress)
            return
        }
        if (RichText.nettoyer(body).isBlank()) {
            error = uiText(R.string.mail_compose_empty_message)
            return
        }
        // Chips plus anything still sitting in the field, so a half-typed
        // address is not silently dropped when Send is tapped.
        val exigeDestinataire = !isReply || destinatairesPrepares
        if (exigeDestinataire && tousA().isEmpty()) {
            error = uiText(R.string.mail_compose_need_recipient)
            return
        }
        sending = true
        error = null
        val app = context.applicationContext
        viewModelScope.launch {
            // Toucher « Envoyer » est le geste qui vaut accord pour les pièces
            // encore proposées : elles montent d'abord, et une seule qui échoue
            // arrête l'envoi — un courriel parti sans la pièce qu'on voyait à
            // l'écran serait pire qu'un envoi à refaire.
            if (proposees.isNotEmpty()) {
                val aJoindre = proposees
                proposees = emptyList()
                var toutes = true
                uploading += aJoindre.size
                try {
                    for (piece in aJoindre) {
                        try {
                            if (!televerser(app, piece.uri)) toutes = false
                        } finally {
                            uploading -= 1
                        }
                    }
                } finally {
                    if (!toutes || !isActive) sending = false
                }
                if (!toutes) return@launch
            }
            if (isServerDraft) {
                sendServerDraft(onSent)
                return@launch
            }
            // Programmer exige un serveur qui sait le faire MAINTENANT : un
            // cache de configuration périmé (instance revenue en arrière)
            // ferait partir le message tout de suite, en silence.
            if (scheduledMs != null) {
                val frais = runCatching { Graph.mail.config() }.getOrNull()
                if (frais != null) {
                    Graph.mailCache.saveConfig(frais)
                    config = frais
                    if (!frais.composeurComplet) {
                        error = uiText(R.string.mail_compose_schedule_unsupported)
                        sending = false
                        return@launch
                    }
                }
            }
            val action = envoi(scheduledMs)
            val delai = Graph.uiPrefs.delaiAnnulation
            // Le délai d'annulation ne vaut que pour un envoi immédiat : un
            // envoi programmé est déjà un délai, et l'annuler se fait dans la
            // liste des programmés.
            if (scheduledMs == null && delai > 0) {
                Graph.drafts.delete(draftId)
                jete = true
                Graph.envois.programmer(action, delai * 1000L)
                sending = false
                onSent(null)
                return@launch
            }
            try {
                Graph.mail.replay(action)
                Graph.drafts.delete(draftId)
                jete = true
                onSent(if (scheduledMs != null) uiText(R.string.mail_compose_scheduled_notice) else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isOffline()) {
                    // La file porte le message maintenant : garder le brouillon
                    // en plus le ferait partir deux fois, une par chemin.
                    Graph.outbox.enqueue(action)
                    queuedOffline = true
                    Graph.drafts.delete(draftId)
                    jete = true
                    onSent(uiText(R.string.mail_compose_queued_offline))
                } else {
                    // Le refus du serveur tel quel ; à défaut, le nôtre.
                    error = e.message?.takeIf { it.isNotBlank() && it != "error" }
                        ?.let { UiText.Raw(it) }
                        ?: uiText(R.string.mail_send_failed)
                    // Rien n'est parti : l'envoi corrigé est un NOUVEL envoi.
                    // Avec le même jeton, un serveur qui l'aurait gardé
                    // répondrait « doublon », et l'écran dirait « envoyé ».
                    clientToken = PendingAction.newToken()
                }
            } finally {
                sending = false
            }
        }
    }

    /**
     * Envoyer un brouillon du POSTE : écrire d'abord, poster ensuite.
     *
     * Deux temps et pas un seul, parce que ce qui part doit être ce qui est à
     * l'écran. Le serveur poste depuis l'enregistrement, pas depuis la charge
     * utile d'un envoi : sans l'écriture préalable, une correction faite juste
     * avant de toucher « Envoyer » partirait sans être dedans.
     *
     * Rien n'est mis en file hors ligne. Un brouillon du poste n'est pas un
     * message que l'app détient : le rejouer plus tard le posterait dans un
     * état que la personne n'aura pas revu, et il peut entretemps avoir été
     * envoyé du bureau. Hors ligne, le texte reste gardé sur l'appareil et la
     * remontée se retentera d'elle-même.
     */
    private suspend fun sendServerDraft(onSent: (UiText?) -> Unit) {
        try {
            if (dirty()) {
                val saved = pushServerDraft(
                    MailDraft(
                        id = draftId,
                        to = tousA(),
                        subject = subject,
                        body = body,
                        attachments = attachments,
                        serverId = serverDraftId,
                        serverVersion = serverVersion,
                        bodyUntouched = body == initialBody,
                    ),
                )
                if (saved.conflict) {
                    conflict = saved.draft
                    error = uiText(R.string.mail_compose_changed_on_desktop)
                    return
                }
                saved.draft?.let { serverVersion = it.version }
            }
            val sent = Graph.mail.sendServerDraft(serverDraftId, serverVersion)
            if (sent.conflict) {
                conflict = sent.draft
                error = uiText(R.string.mail_compose_changed_on_desktop)
                return
            }
            // Le brouillon n'existe plus nulle part : le noyau le supprime en
            // postant, et la reprise locale n'a plus rien à remonter.
            Graph.drafts.delete(draftId)
            jete = true
            onSent(null)
        } catch (e: Exception) {
            val refus = e.message?.takeIf { it.isNotBlank() && it != "error" }
            error = when {
                e.isOffline() -> uiText(R.string.mail_compose_server_draft_needs_network)
                refus != null -> UiText.Raw(refus)
                else -> uiText(R.string.mail_send_failed)
            }
        } finally {
            sending = false
        }
    }

    /**
     * Reprendre ce que le poste porte, en jetant ce qui a été écrit ici.
     *
     * L'issue offerte après un conflit. L'autre — forcer — n'est pas offerte
     * exprès : écraser le travail de l'autre bord d'un seul geste sur un
     * téléphone est trop facile, et le poste, lui, a l'écran pour arbitrer.
     */
    fun adoptServerVersion() {
        val remote = conflict ?: return
        serverVersion = remote.version
        serverBodyHtml = remote.bodyHtml
        toChips = remote.to
        subject = remote.subject
        corps = TextFieldValue(remote.bodyText, TextRange(remote.bodyText.length))
        attachments = remote.attachments.map {
            StagedUpload(ok = true, attachmentId = it.id, name = it.name,
                         size = it.size, mimetype = it.mimetype)
        }
        snapshot()
        Graph.drafts.delete(draftId)
        conflict = null
    }

    fun dismissConflict() {
        conflict = null
    }

    fun dismissError() {
        error = null
    }

    fun dismissInfo() {
        info = null
    }
}
