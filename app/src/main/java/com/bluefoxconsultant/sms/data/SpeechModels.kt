package com.bluefoxconsultant.sms.data

import kotlinx.serialization.Serializable

/**
 * Whether this instance can turn speech into text.
 *
 * `enabled` is decided server-side: the module answers false when no
 * transcription service is configured, so the microphone never appears on a
 * server that could not honour it.
 */
@Serializable
data class SpeechConfig(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    val version: String = "",
)

@Serializable
data class TranscribeResponse(
    val ok: Boolean = false,
    val text: String = "",
)
