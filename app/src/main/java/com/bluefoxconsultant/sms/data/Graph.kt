package com.bluefoxconsultant.sms.data

import android.content.Context
import com.bluefoxconsultant.sms.network.ApiClient
import com.bluefoxconsultant.sms.network.GenfoxRepository
import com.bluefoxconsultant.sms.network.MailRepository
import com.bluefoxconsultant.sms.network.PhoneRepository
import com.bluefoxconsultant.sms.network.Repository
import com.bluefoxconsultant.sms.network.SpeechRepository

/** Minimal service locator, initialised from BfSmsApp (and lazily from receivers). */
object Graph {
    private var initialized = false

    /** REST surface of `bf_softphone`, served by the same instance. */
    private const val PHONE_API_PATH = "/bf_softphone/mobile/v1"

    /** REST surface of `bf_speech` (dictation). */
    private const val SPEECH_API_PATH = "/bf_speech/mobile/v1"

    /** REST surface of `bf_claude_chat` (the assistant). */
    private const val GENFOX_API_PATH = "/bf_claude_chat/mobile/v1"

    lateinit var tokenStore: TokenStore
        private set

    /** SMS half — `bf_sms_archive`. */
    lateinit var smsApi: ApiClient
        private set
    lateinit var sms: Repository
        private set

    /** Mail half — `bf_email_management`. */
    lateinit var mailApi: ApiClient
        private set
    lateinit var mail: MailRepository
        private set

    /**
     * Phone half — `bf_softphone`. Its own module path, but the Messages token:
     * the server module depends on `bf_sms_archive`, so the phone is a
     * capability of that session rather than a third sign-in.
     */
    lateinit var phoneApi: ApiClient
        private set
    lateinit var phone: PhoneRepository
        private set
    lateinit var phoneStore: PhoneStore
        private set

    /**
     * Dictation — `bf_speech`. Depends on neither mailbox module server-side,
     * so it answers to whichever device token this install has.
     */
    lateinit var speech: SpeechRepository
        private set
    lateinit var speechStore: SpeechStore
        private set

    /**
     * GenFox — `bf_claude_chat`. Read-only from a phone by construction, and
     * asked asynchronously: a turn outlives the screen that started it.
     */
    lateinit var genfox: GenfoxRepository
        private set
    lateinit var genfoxStore: GenfoxStore
        private set

    /** Last-known mailbox on disk, and actions taken while offline. */
    lateinit var mailCache: MailCache
        private set
    lateinit var outbox: MailOutbox
        private set

    /** Les courriels commencés et pas envoyés — locaux à l'appareil. */
    lateinit var drafts: MailDrafts
        private set

    /** Colours taken from the connected instance; Symbifox until it answers. */
    lateinit var brandStore: BrandStore
        private set

    /** Per-direction swipe actions, chosen by the user. */
    lateinit var uiPrefs: UiPrefs
        private set

    val isReady: Boolean get() = initialized

    fun apiFor(service: Service): ApiClient =
        if (service == Service.MAIL) mailApi else smsApi

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        tokenStore = TokenStore(context.applicationContext)
        smsApi = ApiClient(tokenStore, Service.SMS)
        mailApi = ApiClient(tokenStore, Service.MAIL)
        phoneApi = ApiClient(tokenStore, Service.SMS, apiPath = PHONE_API_PATH)
        sms = Repository(smsApi)
        mail = MailRepository(mailApi)
        phone = PhoneRepository(phoneApi)
        phoneStore = PhoneStore(phone)
        speech = SpeechRepository(
            ApiClient(tokenStore, Service.SMS, apiPath = SPEECH_API_PATH),
            ApiClient(tokenStore, Service.MAIL, apiPath = SPEECH_API_PATH),
            tokenStore,
        )
        speechStore = SpeechStore(speech)
        genfox = GenfoxRepository(
            ApiClient(tokenStore, Service.SMS, apiPath = GENFOX_API_PATH),
            ApiClient(tokenStore, Service.MAIL, apiPath = GENFOX_API_PATH),
            tokenStore,
        )
        genfoxStore = GenfoxStore(genfox)
        mailCache = MailCache(context.applicationContext)
        outbox = MailOutbox(context.applicationContext)
        drafts = MailDrafts(context.applicationContext)
        brandStore = BrandStore(context.applicationContext)
        uiPrefs = UiPrefs(context.applicationContext)
        initialized = true
    }
}
