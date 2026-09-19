package com.bluefoxconsultant.sms.sip

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.SipConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText

/**
 * Le poste SIP de l'appareil.
 *
 * L'app portait jusqu'ici un télécommande : elle demandait au PBX de faire
 * sonner un AUTRE appareil. C'était la phase 1, et ça marchait — sauf qu'aucun
 * autre appareil n'était joignable, alors la conversation atterrissait toujours
 * sur le navigateur du poste de travail.
 *
 * Ici, l'appareil devient lui-même le poste. Il s'enregistre sur le PBX avec la
 * MÊME extension que le navigateur : `Dial(PJSIP/1001)` fait sonner tous les
 * contacts enregistrés, donc s'ajouter n'enlève rien — on décroche où l'on veut.
 * Et pour un appel sortant, il n'y a plus de rappel du tout : le poste compose
 * directement, une seule jambe, immédiate.
 *
 * ⚠️ WebView et non pile native. Les deux piles SIP Android sérieuses
 * (liblinphone, PJSIP) sont sous GPL — contaminant pour une app propriétaire —
 * et pèsent des dizaines de mégaoctets. JsSIP est sous MIT, tient en 240 Ko, et
 * tourne déjà contre ce PBX dans le navigateur Odoo. WebRTC vient du système.
 *
 * ⚠️ La page est servie sur https://appassets.androidplatform.net/, pas file://.
 * getUserMedia exige une origine sûre : sous file://, le micro est refusé et
 * l'appel part muet, sans erreur lisible.
 */
object SipEngine {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private var web: WebView? = null
    private var app: Context? = null
    private var pageReady = false
    /** Config en attente que la page finisse de charger. */
    private var pending: SipConfig? = null
    /** Verrou d'écran de proximité, tenu le temps de l'appel. */
    private var proximite: PowerManager.WakeLock? = null
    /** Numéro à composer dès que l'enregistrement aboutit. */
    private var queuedNumber: String? = null
    /**
     * « Répondre » pressé AVANT que l'appel arrive.
     *
     * Le réveil par push retourne l'ordre habituel : la notification est
     * dessinée quand le PBX prévient, donc plusieurs secondes avant l'INVITE.
     * Sans ça, décrocher depuis l'écran de sonnerie ne ferait rien, et il
     * faudrait décrocher une deuxième fois quand le téléphone sonne pour de bon.
     */
    private var autoAnswer = false

    private val _state = MutableStateFlow(SipState())
    val state: StateFlow<SipState> = _state.asStateFlow()

    /**
     * Démarre le poste : va chercher les identifiants, puis s'enregistre.
     *
     * Sans effet si le compte n'a pas de poste SIP — le bouton « sur cet
     * appareil » reste alors absent, plutôt que d'échouer au moment de l'appel.
     */
    fun start(context: Context) {
        app = context.applicationContext
        if (_state.value.status != SipStatus.OFFLINE) return
        _state.value = _state.value.copy(status = SipStatus.CONNECTING)
        scope.launch {
            val cfg = runCatching { Graph.phone.sipConfig() }.getOrNull()
            if (cfg == null || !cfg.usable) {
                _state.value = SipState(status = SipStatus.UNAVAILABLE)
                return@launch
            }
            _state.value = _state.value.copy(extension = cfg.extension)
            ensureWebView()
            if (pageReady) push(cfg) else pending = cfg
        }
    }

    /** Coupe l'enregistrement. L'appel en cours, s'il y en a un, est raccroché. */
    fun stop() {
        main.post {
            web?.evaluateJavascript("BFPhone && BFPhone.stop()", null)
            releaseAudio()
            releaseProximity()
            app?.let { CallService.stop(it) }
            _state.value = SipState()
        }
    }

    fun call(number: String) {
        val clean = number.filter { it.isDigit() || it == '+' }.removePrefix("+")
        when (_state.value.status) {
            SipStatus.REGISTERED -> js("BFPhone.call(${quote(clean)})")
            // Pas encore enregistré : on retient le numéro plutôt que de perdre
            // le geste. Le premier « registered » le composera.
            SipStatus.CONNECTING -> queuedNumber = clean
            else -> _state.value = _state.value.copy(error = uiText(R.string.sip_not_connected))
        }
    }

    fun answer() {
        autoAnswer = false
        js("BFPhone.answer()")
    }

    /**
     * Décroche l'appel entrant — ou le décrochera dès qu'il arrive.
     *
     * Utilisé par l'écran de sonnerie ouvert par le réveil par push, où le
     * geste précède l'INVITE. Sans appel en vue, l'intention est retenue.
     */
    fun answerWhenReady() {
        if (_state.value.call?.incoming == true) answer() else autoAnswer = true
    }

    fun cancelAutoAnswer() {
        autoAnswer = false
    }

    fun hangup() = js("BFPhone.hangup()")

    fun dtmf(digits: String) = js("BFPhone.dtmf(${quote(digits)})")

    fun setMuted(on: Boolean) = js("BFPhone.mute($on)")

    /**
     * Haut-parleur.
     *
     * `isSpeakerphoneOn` est déprécié depuis l'API 31 au profit de
     * `setCommunicationDevice`, mais l'app descend jusqu'à l'API 26 et l'ancien
     * appel fonctionne encore partout. À revoir le jour où minSdk montera.
     */
    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        audio()?.isSpeakerphoneOn = on
        _state.value = _state.value.copy(speaker = on)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // ── Interne ───────────────────────────────────────────────────────

    private fun audio(): AudioManager? =
        app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Bascule la sortie audio en mode conversation.
     *
     * Sans MODE_IN_COMMUNICATION, le son sort par le haut-parleur média à plein
     * volume et l'annulation d'écho ne s'engage pas : l'autre bout s'entend
     * lui-même. C'est le réglage qui distingue un appel d'une vidéo.
     */
    @Suppress("DEPRECATION")
    private fun takeAudio() {
        val am = audio() ?: return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        // Écouteur par défaut, jamais le haut-parleur : un appel d'affaires
        // qui démarre à voix haute se remarque dans une pièce partagée. Le
        // bouton du haut-parleur reste à un tap pour qui le veut.
        am.isSpeakerphoneOn = false
    }

    /**
     * Éteint l'écran quand l'appareil est porté à l'oreille.
     *
     * `PROXIMITY_SCREEN_OFF_WAKE_LOCK` fait exactement ce que fait l'appli
     * Téléphone du système : le capteur de proximité éteint la dalle sans
     * endormir l'appareil, ce qui évite surtout les appuis de joue — un
     * raccrochage accidentel en pleine conversation.
     *
     * ⚠️ Toutes les dalles ne l'exposent pas ; `isWakeLockLevelSupported` est
     * donc une vraie question, pas une formalité. Sur un appareil sans capteur
     * on ne fait rien plutôt que de lever une exception.
     */
    private fun takeProximity() {
        if (proximite != null) return
        val pm = app?.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximite = pm.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "SymbifoxComms:appel",
        ).also { runCatching { it.acquire(2 * 60 * 60 * 1000L) } }
    }

    private fun releaseProximity() {
        // ⚠️ Relâcher un verrou non tenu lève IllegalStateException, et
        // l'écran resterait noir après l'appel — le pire des deux échecs.
        proximite?.let { if (it.isHeld) runCatching { it.release() } }
        proximite = null
    }

    @Suppress("DEPRECATION")
    private fun releaseAudio() {
        val am = audio() ?: return
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
        am.abandonAudioFocus(null)
    }

    private fun js(code: String) {
        main.post { web?.evaluateJavascript(code, null) }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun push(cfg: SipConfig) {
        js("BFPhone.start(${quote(json.encodeToString(cfg))})")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (web != null) return
        val context = app ?: return
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Le flux distant démarre tout seul : sans ça, il faudrait un geste
            // de l'utilisateur DANS la page, qui n'est jamais affichée.
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge, "BFAndroid")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
            }
            webChromeClient = object : WebChromeClient() {
                // Le micro est déjà accordé à l'app par le système ; ici on ne
                // fait que le transmettre à une page qu'on a écrite et qu'on
                // sert nous-mêmes. Aucune autre ressource n'est accordée.
                override fun onPermissionRequest(request: PermissionRequest) {
                    val wanted = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }.toTypedArray()
                    if (wanted.isEmpty()) request.deny() else request.grant(wanted)
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/webphone/webphone.html")
        }
    }

    /** Ce que la page renvoie. Appelé depuis un fil WebView, jamais l'UI. */
    private object Bridge {
        @android.webkit.JavascriptInterface
        fun onEvent(payload: String) {
            val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
            main.post { handle(o) }
        }
    }

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "ready" -> {
                pageReady = true
                pending?.let { push(it); pending = null }
            }
            "status" -> {
                val status = when (o.optString("status")) {
                    "registered" -> SipStatus.REGISTERED
                    "connecting" -> SipStatus.CONNECTING
                    "failed" -> SipStatus.FAILED
                    else -> SipStatus.OFFLINE
                }
                _state.value = _state.value.copy(
                    status = status,
                    // La cause est celle de JsSIP (« Request Timeout »…), une valeur
                    // de protocole : elle entre telle quelle dans la phrase traduite.
                    error = if (status == SipStatus.FAILED) {
                        uiText(
                            R.string.sip_registration_failed,
                            o.optString("cause").ifBlank { null }?.let { UiText.Raw(it) }
                                ?: uiText(R.string.sip_cause_authentication),
                        )
                    } else {
                        _state.value.error
                    },
                )
                if (status == SipStatus.REGISTERED) {
                    queuedNumber?.let { call(it); queuedNumber = null }
                }
            }
            "calling", "incoming" -> {
                takeAudio()
                // Le service démarre AVANT que l'appel soit établi : c'est
                // pendant la sonnerie qu'on est le plus susceptible de mettre
                // l'app de côté, et c'est justement là qu'il ne faut pas que
                // le système nous gèle.
                app?.let { CallService.start(it, o.optString("peer")) }
                _state.value = _state.value.copy(
                    call = CallLeg(
                        peer = o.optString("peer"),
                        incoming = o.optString("type") == "incoming",
                        established = false,
                    ),
                )
                if (autoAnswer && o.optString("type") == "incoming") answer()
            }
            "established" -> {
                // Seulement une fois décroché : éteindre l'écran pendant que ça
                // sonne empêcherait de raccrocher avant la réponse.
                takeProximity()
                _state.value = _state.value.copy(
                    call = _state.value.call?.copy(
                        established = true,
                        peer = o.optString("peer")
                            .ifBlank { _state.value.call?.peer.orEmpty() },
                    ),
                )
            }
            "ended" -> {
                autoAnswer = false
                releaseAudio()
                releaseProximity()
                app?.let { CallService.stop(it) }
                _state.value = _state.value.copy(call = null, muted = false, speaker = false)
            }
            "muted" -> _state.value = _state.value.copy(muted = o.optBoolean("muted"))
            // La page rend un CODE, pas une phrase : la langue est celle de l'app,
            // et la page n'a pas à la connaître.
            "error" -> _state.value = _state.value.copy(
                error = when (o.optString("code")) {
                    "not_connected" -> uiText(R.string.sip_not_connected)
                    "call_in_progress" -> uiText(R.string.sip_call_already_in_progress)
                    else -> UiText.Raw(o.optString("message"))
                },
            )
        }
    }
}

enum class SipStatus {
    /** Éteint, ou pas encore démarré. */
    OFFLINE,
    CONNECTING,
    REGISTERED,
    /** Le compte n'a pas de poste SIP : rien à proposer. */
    UNAVAILABLE,
    FAILED,
}

data class SipState(
    val status: SipStatus = SipStatus.OFFLINE,
    val extension: String = "",
    val call: CallLeg? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val error: UiText? = null,
) {
    /** Le poste peut-il porter un appel maintenant ? */
    val ready: Boolean get() = status == SipStatus.REGISTERED
}

data class CallLeg(
    val peer: String,
    val incoming: Boolean,
    val established: Boolean,
)
