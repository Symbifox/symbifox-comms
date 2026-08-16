package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.data.TokenStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, val err: String) : Exception(err)

/**
 * Thin OkHttp wrapper bound to one [Service]. Attaches that service's bearer
 * token to every call except the token exchange.
 *
 * One client per service, on purpose: a 401 must clear only the token of the
 * service that returned it. Sharing a client would sign the user out of both
 * tabs the moment either module revoked a device.
 */
class ApiClient(
    private val tokenStore: TokenStore,
    val service: Service,
) {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            // The token exchange is unauthenticated (no token yet), and /ping
            // runs before any login at all.
            val path = original.url.encodedPath
            val unauthenticated = path.endsWith("/auth/exchange") || path.endsWith("/ping")
            val builder = original.newBuilder()
            val token = tokenStore.tokenFor(service)
            if (!unauthenticated && token != null) {
                builder.header("Authorization", "Bearer $token")
            }
            val response = chain.proceed(builder.build())
            if (response.code == 401 && !unauthenticated) {
                // Session expired / revoked — drop ONLY this service's token so
                // the UI routes that tab back to login.
                tokenStore.clearToken(service)
            }
            response
        }
        .build()

    /** `<instance>/<module>/mobile/v1` rebuilt from the stored instance per call. */
    private fun base(): String {
        val instance = tokenStore.instanceUrl ?: throw ApiException(0, "no_instance")
        return service.baseUrl(instance)
    }

    fun postJson(path: String, body: String): String = exec(
        Request.Builder().url(base() + path)
            .post(body.toRequestBody(JSON_MEDIA)).build()
    )

    fun get(path: String): String = exec(
        Request.Builder().url(base() + path).get().build()
    )

    /** Multipart upload of one file. Streams as bytes, not base64. */
    fun postFile(path: String, field: String, filename: String,
                 mimetype: String, bytes: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                field, filename,
                bytes.toRequestBody(mimetype.toMediaTypeOrNull(), 0, bytes.size),
            )
            .build()
        return exec(Request.Builder().url(base() + path).post(body).build())
    }

    /** Raw bytes, for attachment downloads. */
    fun getBytes(path: String): ByteArray {
        val request = Request.Builder().url(base() + path).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException(response.code, parseError(response.body?.string().orEmpty()))
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * Capability probe against an instance the app is not signed in to yet.
     * `true` only for a 200 that names this module — anything else (404 from a
     * missing module, an HTML error page, a connection failure) means "not
     * available here", which hides the tab rather than failing the login.
     */
    /**
     * Full ping payload, including the instance's branding. Null when the
     * module is absent or unreachable — the caller keeps its defaults.
     */
    fun pingInfo(instance: String): com.bluefoxconsultant.sms.data.PingResponse? = try {
        val request = Request.Builder()
            .url(service.baseUrl(instance) + "/ping").get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) null
            else runCatching {
                json.decodeFromString<com.bluefoxconsultant.sms.data.PingResponse>(text)
            }.getOrNull()?.takeIf { it.ok }
        }
    } catch (e: Exception) {
        null
    }

    fun ping(instance: String): Boolean = try {
        val request = Request.Builder()
            .url(service.baseUrl(instance) + "/ping").get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            response.isSuccessful && runCatching {
                (json.parseToJsonElement(text) as? JsonObject)
                    ?.get("ok")?.jsonPrimitive?.content == "true"
            }.getOrDefault(false)
        }
    } catch (e: Exception) {
        false
    }

    private fun exec(request: Request): String {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, parseError(text))
            }
            return text
        }
    }

    private fun parseError(text: String): String = try {
        (json.parseToJsonElement(text) as? JsonObject)?.get("error")?.jsonPrimitive?.content ?: "error"
    } catch (e: Exception) {
        "error"
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Kept for callers that only ever meant the SMS API. */
        fun apiBase(instance: String): String = Service.SMS.baseUrl(instance)
    }
}
