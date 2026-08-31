@file:OptIn(ExperimentalMaterial3Api::class)

package com.bluefoxconsultant.sms.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluefoxconsultant.sms.data.SharedContent
import com.bluefoxconsultant.sms.ui.theme.BrandAccent

/**
 * « Envoyer vers » : où va ce qu'une autre app vient de partager.
 *
 * ⚠️ Cet écran n'existe que lorsque le choix se pose. Avec une seule moitié
 * connectée, l'appelant saute directement au composeur : faire choisir entre
 * une option et rien est le genre d'étape qui fait renoncer à un partage.
 */
@Composable
fun ShareScreen(
    shared: SharedContent,
    canSms: Boolean,
    canMail: Boolean,
    onSms: () -> Unit,
    onMail: () -> Unit,
    onBack: () -> Unit,
) {
    // ⚠️ Le geste de retour du système ne passe pas par la flèche : sans ce
    // relais, renoncer au partage laisserait la réserve pleine et le prochain
    // message neuf hériterait de la photo d'avant.
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Envoyer avec", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandAccent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SharePreview(shared)
            Spacer(Modifier.height(20.dp))

            if (canSms) {
                ListItem(
                    headlineContent = { Text("Texto") },
                    supportingContent = {
                        Text(
                            if (shared.hasFiles) "Part en MMS, vers un numéro."
                            else "Vers un numéro.",
                        )
                    },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, tint = BrandAccent)
                    },
                    modifier = Modifier.clickableRow(onSms),
                )
            }
            if (canMail) {
                ListItem(
                    headlineContent = { Text("Courriel") },
                    supportingContent = {
                        Text(
                            if (shared.hasFiles) "En pièce jointe, vers une adresse."
                            else "Vers une adresse.",
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Filled.MailOutline, null, tint = BrandAccent)
                    },
                    modifier = Modifier.clickableRow(onMail),
                )
            }
        }
    }
}

/** Ce qui a été partagé, montré avant qu'on demande où l'envoyer. */
@Composable
private fun SharePreview(shared: SharedContent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            if (shared.subject.isNotBlank()) {
                Text(shared.subject, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Spacer(Modifier.height(4.dp))
            }
            if (shared.text.isNotBlank()) {
                Text(
                    shared.text,
                    fontSize = 13.sp,
                    maxLines = 6,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (shared.hasFiles) {
                if (shared.text.isNotBlank() || shared.subject.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.AttachFile, null, Modifier.size(16.dp), tint = BrandAccent)
                    Text(
                        if (shared.uris.size == 1) "1 fichier"
                        else "${shared.uris.size} fichiers",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    fillMaxWidth().clickable(onClick = onClick)
