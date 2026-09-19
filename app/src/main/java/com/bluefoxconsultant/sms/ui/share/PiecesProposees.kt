package com.bluefoxconsultant.sms.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluefoxconsultant.sms.data.PieceProposee
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

/**
 * Les fichiers partagés qui attendent un geste.
 *
 * Deux gestes par ligne et pas un : « Joindre » lit et prépare la pièce tout
 * de suite, la croix l'écarte sans l'avoir lue. Ne rien toucher et envoyer
 * vaut aussi accord — c'est le geste d'envoi — et le composeur le dit.
 */
@Composable
fun PiecesProposees(
    proposees: List<PieceProposee>,
    onJoindre: (PieceProposee) -> Unit,
    onEcarter: (PieceProposee) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (proposees.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(
                if (proposees.size == 1) R.string.share_pending_one else R.string.share_pending_many,
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        proposees.forEach { piece ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    piece.taille?.let { "${piece.nom} · ${tailleLisible(it)}" } ?: piece.nom,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                TextButton(onClick = { onJoindre(piece) }) { Text(stringResource(R.string.share_attach)) }
                IconButton(onClick = { onEcarter(piece) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.share_discard_file, piece.nom),
                    )
                }
            }
        }
    }
}

/** L'unité suit la langue (Mo, MB) et la décimale aussi (1,5 ou 1.5). */
@Composable
private fun tailleLisible(octets: Long): String = when {
    octets >= 1_048_576 -> stringResource(R.string.size_megabytes, octets / 1_048_576.0)
    octets >= 1024 -> stringResource(R.string.size_kilobytes, octets / 1024)
    else -> stringResource(R.string.size_bytes, octets)
}
