package com.bluefoxconsultant.sms.sip

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluefoxconsultant.sms.ui.theme.BfSmsTheme
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

private val CALL_GREEN = Color(0xFF2E7D32)
private val CALL_RED = Color(0xFFC62828)

/**
 * L'écran de sonnerie — ce que l'intention plein écran ouvre.
 *
 * Volontairement séparé de [com.bluefoxconsultant.sms.ui.MainActivity] et
 * volontairement pauvre : il s'affiche PAR-DESSUS l'écran de verrouillage, et
 * tout ce qu'il montre est donc visible sans déverrouiller. Le nom du
 * correspondant et deux boutons, rien d'autre — surtout pas les conversations.
 *
 * Il porte aussi l'appel jusqu'au bout plutôt que de passer la main à l'écran
 * Téléphone : renvoyer vers l'app complète une fois décroché exposerait la
 * messagerie sur un téléphone resté verrouillé.
 */
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        montrerParDessusLeVerrou()
        traiter(intent)
        setContent {
            BfSmsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EcranSonnerie(
                        onFini = { finishAndRemoveTask() },
                        onDemanderLeVerrouLeve = { leverLeVerrou() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        traiter(intent)
    }

    /** « Répondre » pressé depuis la notification : l'intention le dit. */
    private fun traiter(intent: Intent?) {
        if (intent?.getBooleanExtra(IncomingCall.EXTRA_ANSWER, false) == true) {
            SipEngine.answerWhenReady()
        }
    }

    /**
     * S'afficher sur un téléphone verrouillé, et rallumer la dalle.
     *
     * ⚠️ Les drapeaux de fenêtre sont dépréciés depuis l'API 27 au profit des
     * deux `setShowWhenLocked`/`setTurnScreenOn`, mais l'app descend jusqu'à
     * l'API 26 : sans le repli, un appel n'ouvrirait rien sur ces appareils.
     */
    @Suppress("DEPRECATION")
    private fun montrerParDessusLeVerrou() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    /**
     * Demande la levée du verrou une fois l'appel décroché.
     *
     * Sans ça, raccrocher laisserait l'appareil sur cet écran plutôt que de
     * rendre la main au système. Le geste reste celui de l'utilisateur : le
     * système peut demander le code, et refuser.
     */
    private fun leverLeVerrou() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching { km.requestDismissKeyguard(this, null) }
        }
    }
}

@Composable
private fun EcranSonnerie(onFini: () -> Unit, onDemanderLeVerrouLeve: () -> Unit) {
    val sip by SipEngine.state.collectAsStateWithLifecycle()
    val contexte = androidx.compose.ui.platform.LocalContext.current
    // Le micro n'est demandé qu'ici, quand un appel arrive vraiment. Refusé,
    // l'appel part muet — ce que l'écran dit plutôt que de le laisser deviner.
    val micro = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        val accorde = ContextCompat.checkSelfPermission(
            contexte, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!accorde) micro.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ⚠️ « Aucun appel » veut dire deux choses opposées : pas ENCORE (le poste
    // se réenregistre, l'INVITE arrive) ou PLUS (c'est fini). Sans mémoire de
    // l'appel qui a existé, l'écran se fermerait à la seconde où il s'ouvre.
    var aSonne by remember { mutableStateOf(false) }
    LaunchedEffect(sip.call) {
        if (sip.call != null) aSonne = true
        if (aSonne && sip.call == null) {
            IncomingCall.stop(contexte)
            onFini()
        }
    }
    // Le réveil peut échouer : push reçu, poste qui ne se réenregistre pas,
    // INVITE qui n'arrive jamais. Sans cette sortie, l'écran de sonnerie
    // resterait planté par-dessus l'écran de verrouillage, sans rien à
    // décrocher et sans bouton pour s'en aller.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(50_000)
        if (!aSonne) {
            IncomingCall.stop(contexte)
            onFini()
        }
    }

    val etabli = sip.call?.established == true
    LaunchedEffect(etabli) {
        if (etabli) {
            // La sonnerie a fait son travail : elle s'efface et laisse la
            // notification d'appel en cours du service prendre le relais.
            IncomingCall.stop(contexte)
            onDemanderLeVerrouLeve()
        }
    }

    val nom = IncomingCall.name.ifBlank { "" }
    val numero = sip.call?.peer?.ifBlank { null } ?: IncomingCall.peer

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = nom.ifBlank { numero.ifBlank { stringResource(R.string.common_incoming_call) } },
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (nom.isNotBlank() && numero.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(numero, color = Color(0xFFB0BEC5), fontSize = 16.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(
                    when {
                        etabli -> R.string.phone_in_call
                        sip.call != null -> R.string.common_incoming_call
                        sip.status == SipStatus.REGISTERED -> R.string.incoming_call_softphone_ready
                        else -> R.string.incoming_call_waking_softphone
                    },
                ),
                color = Color(0xFF90A4AE),
                fontSize = 15.sp,
            )
        }

        if (etabli) {
            EnCommunication(
                muted = sip.muted,
                speaker = sip.speaker,
                onMute = { SipEngine.setMuted(!sip.muted) },
                onSpeaker = { SipEngine.setSpeaker(!sip.speaker) },
                onHangup = { SipEngine.hangup() },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        IncomingCall.decline(contexte)
                        onFini()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CALL_RED),
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = null,
                         modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.common_decline_call))
                }
                Button(
                    // Répondre AVANT que ça sonne est le cas normal ici : la
                    // notification est dessinée dès le push, plusieurs secondes
                    // avant l'INVITE. L'intention est retenue par le poste.
                    onClick = { SipEngine.answerWhenReady() },
                    colors = ButtonDefaults.buttonColors(containerColor = CALL_GREEN),
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null,
                         modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.common_answer_call))
                }
            }
        }
    }
}

@Composable
private fun EnCommunication(
    muted: Boolean,
    speaker: Boolean,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHangup: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onMute) {
                Icon(
                    if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(if (muted) R.string.common_unmute else R.string.common_mute),
                    color = Color.White,
                )
            }
            TextButton(onClick = onSpeaker) {
                Icon(
                    if (speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(if (speaker) R.string.phone_loudspeaker else R.string.phone_earpiece),
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onHangup,
            colors = ButtonDefaults.buttonColors(containerColor = CALL_RED),
        ) {
            Icon(Icons.Filled.CallEnd, contentDescription = null,
                 modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.common_hang_up))
        }
    }
}
