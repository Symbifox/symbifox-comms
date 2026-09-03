package com.bluefoxconsultant.sms.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The PKCE verifier and its challenge.
 *
 * Why this exists: a custom app scheme is NOT exclusive on Android. Another app
 * may declare `com.bluefoxconsultant.sms://auth` and receive the pairing code
 * instead of this one. Without a verifier it would trade that code for a bearer
 * token, hence for the person's SMS history and mailbox. The redirect-scheme
 * allowlist on the server does not cover this: it closes the open redirect on
 * the server, not the local interception of the code on the device.
 *
 * The verifier never leaves this app. It goes out once, over HTTPS, in the
 * exchange body — never through the deep link that the other app could catch.
 */
object Pkce {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * 43 characters of randomness, the standard's minimum.
     *
     * `SecureRandom`, not `Random`: this is the only secret separating an
     * intercepted code from a bearer token.
     */
    fun verifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return encoder.encodeToString(bytes)
    }

    /** The S256 challenge — the only method the in-house server accepts. */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(digest)
    }
}
