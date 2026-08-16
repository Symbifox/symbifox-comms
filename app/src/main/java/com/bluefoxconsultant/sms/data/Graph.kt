package com.bluefoxconsultant.sms.data

import android.content.Context
import com.bluefoxconsultant.sms.network.ApiClient
import com.bluefoxconsultant.sms.network.MailRepository
import com.bluefoxconsultant.sms.network.Repository

/** Minimal service locator, initialised from BfSmsApp (and lazily from receivers). */
object Graph {
    private var initialized = false

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

    /** Last-known mailbox on disk, and actions taken while offline. */
    lateinit var mailCache: MailCache
        private set
    lateinit var outbox: MailOutbox
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
        sms = Repository(smsApi)
        mail = MailRepository(mailApi)
        mailCache = MailCache(context.applicationContext)
        outbox = MailOutbox(context.applicationContext)
        brandStore = BrandStore(context.applicationContext)
        uiPrefs = UiPrefs(context.applicationContext)
        initialized = true
    }
}
