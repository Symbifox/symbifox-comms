package com.bluefoxconsultant.sms.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records one dictation to a file in the cache directory.
 *
 * AAC in an MP4 container at 16 kHz mono: that is what speech recognition wants
 * and nothing more, so a minute of talking is on the order of a couple hundred
 * kilobytes rather than several megabytes. The file lives in the cache, is
 * uploaded, then deleted — a recording of someone's voice has no business
 * outliving the sentence it became.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    val isRecording: Boolean get() = recorder != null

    /** True if the microphone actually started. */
    fun start(): Boolean {
        if (recorder != null) return true
        val file = File.createTempFile("dictee-", ".m4a", context.cacheDir)
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(32_000)
            // A dictation that runs away (pocket, forgotten tap) stops itself
            // well before it could hit the server's size limit.
            rec.setMaxDuration(MAX_MILLIS)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            target = file
            true
        } catch (e: Exception) {
            runCatching { rec.release() }
            file.delete()
            false
        }
    }

    /** Stops and returns the file, or null if nothing usable was captured. */
    fun stop(): File? {
        val rec = recorder ?: return null
        val file = target
        recorder = null
        target = null
        return try {
            rec.stop()
            rec.release()
            // Under a second is a mis-tap, not a sentence. MediaRecorder also
            // writes an unplayable stub when stopped that early.
            file?.takeIf { it.length() > MIN_BYTES }?.also { return it }
            file?.delete()
            null
        } catch (e: Exception) {
            runCatching { rec.release() }
            file?.delete()
            null
        }
    }

    /** Abandons the recording and leaves nothing behind. */
    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        val file = target
        target = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        file?.delete()
    }

    private companion object {
        const val MAX_MILLIS = 5 * 60 * 1000
        const val MIN_BYTES = 2_000L
    }
}
