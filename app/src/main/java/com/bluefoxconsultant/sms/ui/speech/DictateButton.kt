package com.bluefoxconsultant.sms.ui.speech

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluefoxconsultant.sms.audio.VoiceRecorder
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.network.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adds dictated text to whatever is already typed.
 *
 * Appending rather than replacing is the whole contract of the button: someone
 * who dictates twice is adding a sentence, not correcting one. The separator
 * exists so "bonjour" plus "ça va" does not come back as "bonjourça va".
 */
fun appendSpoken(existing: String, spoken: String): String {
    val clean = spoken.trim()
    if (clean.isEmpty()) return existing
    if (existing.isBlank()) return clean
    val separator = if (existing.last().isWhitespace()) "" else " "
    return existing + separator + clean
}

/**
 * Microphone that turns speech into text in the field beside it.
 *
 * Tap to start, tap to stop; the transcription is appended through [onText]
 * rather than replacing what is there, so dictating twice adds a sentence
 * instead of erasing one. Nothing is sent until the recording stops.
 *
 * Renders nothing when the instance has no transcription service — the server
 * decides that, and a mic that cannot transcribe is worse than no mic.
 */
@Composable
fun DictateButton(
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onText: (String) -> Unit,
) {
    val config by Graph.speechStore.config.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Graph.speechStore.ensureLoaded() }

    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Leaving the screen mid-dictation must not leave the microphone open, nor
    // a stray recording in the cache.
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    fun begin() {
        if (recorder.start()) {
            recording = true
            seconds = 0
        } else {
            scope.launch { snackbar.showSnackbar("Le micro n'est pas disponible.") }
        }
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) begin()
        else scope.launch { snackbar.showSnackbar("Sans accès au micro, pas de dictée.") }
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(1000)
            seconds += 1
        }
    }

    if (!config.enabled) return

    IconButton(
        modifier = modifier,
        enabled = enabled && !sending,
        onClick = {
            if (recording) {
                recording = false
                val file = recorder.stop()
                if (file == null) {
                    scope.launch { snackbar.showSnackbar("Rien n'a été enregistré.") }
                    return@IconButton
                }
                sending = true
                scope.launch {
                    try {
                        val text = Graph.speech.transcribe(file)
                        if (text.isBlank()) {
                            snackbar.showSnackbar("Rien n'a été compris.")
                        } else {
                            onText(text)
                        }
                    } catch (e: ApiException) {
                        // The server sends a sentence: too long, service down,
                        // rate limited. Show it rather than a generic failure.
                        snackbar.showSnackbar(e.err)
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Transcription impossible.")
                    } finally {
                        file.delete()
                        sending = false
                    }
                }
            } else {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) begin() else askPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    ) {
        when {
            sending -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
            recording -> Icon(
                Icons.Filled.Stop,
                contentDescription = "Arrêter la dictée ($seconds s)",
                tint = MaterialTheme.colorScheme.error,
            )
            else -> Icon(Icons.Filled.Mic, contentDescription = "Dicter")
        }
    }
}
