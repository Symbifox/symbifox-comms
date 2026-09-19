package com.bluefoxconsultant.sms.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.bluefoxconsultant.sms.data.SwipeAction
import com.bluefoxconsultant.sms.ui.theme.BrandAccent
import kotlin.math.abs
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

/**
 * Fraction de la largeur de la ligne à parcourir pour que l'action parte.
 *
 * Un seul nombre, pour les deux listes : le geste doit se ressentir pareil
 * dans les textos et dans les courriels.
 */
private const val COMMIT_FRACTION = 0.25f

/**
 * Ce que la ligne relâchée a réellement parcouru.
 *
 * ⚠️ `confirmValueChange` est passé à `rememberSwipeToDismissBoxState`, donc
 * la lambda est construite AVANT que l'état existe : elle ne peut pas le
 * capturer directement. Ce porteur, rempli juste après, lui donne accès à
 * l'état et à la largeur mesurée sans rien faire recomposer.
 */
private class SwipeProbe {
    var state: SwipeToDismissBoxState? = null
    var widthPx: Int = 0

    /** Distance parcourue en pixels, 0 tant qu'aucun ancrage n'est posé. */
    fun travelled(): Float {
        val s = state ?: return 0f
        return runCatching { abs(s.requireOffset()) }.getOrDefault(0f)
    }

    fun committed(): Boolean =
        widthPx > 0 && travelled() >= widthPx * COMMIT_FRACTION
}

/**
 * Une ligne qu'on fait glisser pour agir, avec la même règle partout.
 *
 * 🔴 Le seuil positionnel de Material ne suffit pas : `SwipeToDismissBox`
 * pose aussi un seuil de VÉLOCITÉ (125 dp/s), et une chiquenaude par-dessus
 * emporte la ligne quelle que soit la distance. C'est ce qui rendait
 * l'archivage si facile à déclencher par accident, seuil positionnel ou pas.
 * On relit donc la distance réelle au relâchement et on refuse en deçà de
 * [COMMIT_FRACTION] : la vitesse ne rachète plus un geste trop court.
 *
 * Le fond ne s'allume qu'une fois le seuil franchi, et une vibration le dit
 * au doigt. Relâcher sur un fond gris ne fait jamais rien.
 *
 * Une direction réglée sur [SwipeAction.NONE] refuse le geste au lieu de
 * l'avaler : une ligne qui part et revient sans que rien n'arrive se lit
 * comme un bogue.
 */
@Composable
fun SwipeActionRow(
    startAction: SwipeAction,
    endAction: SwipeAction,
    onAction: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    restore: Boolean = false,
    content: @Composable () -> Unit,
) {
    // ⚠️ `rememberSwipeToDismissBoxState` GARDE la première lambda qu'on lui
    // donne : les paramètres capturés à la composition initiale y restent figés
    // pour la vie de la ligne. Sans `rememberUpdatedState`, neutraliser le
    // glissement pendant une sélection n'aurait aucun effet — la ligne partirait
    // quand même, avec l'action d'avant.
    val start by rememberUpdatedState(startAction)
    val end by rememberUpdatedState(endAction)
    val act by rememberUpdatedState(onAction)
    val probe = remember { SwipeProbe() }

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> start
                SwipeToDismissBoxValue.EndToStart -> end
                else -> SwipeAction.NONE
            }
            if (action == SwipeAction.NONE || !probe.committed()) {
                false
            } else {
                act(action)
                true
            }
        },
        positionalThreshold = { distance -> distance * COMMIT_FRACTION },
    )
    probe.state = state

    // ⚠️ `progress` vaut 1.0 au REPOS — il mesure l'écart entre l'ancrage
    // courant et la cible, confondus tant que rien ne bouge. Le lire sans
    // vérifier la direction peignait le fond « armé » derrière chaque ligne en
    // permanence. On lit donc la direction d'abord, la distance ensuite.
    val dismissing = state.dismissDirection != SwipeToDismissBoxValue.Settled
    val armed = dismissing && probe.committed()
    val action = when (state.dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> startAction
        SwipeToDismissBoxValue.EndToStart -> endAction
        else -> SwipeAction.NONE
    }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(armed) {
        if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val tint by animateColorAsState(
        if (armed) BrandAccent else MaterialTheme.colorScheme.surfaceVariant,
        label = "swipe-bg",
    )
    val iconScale by animateFloatAsState(if (armed) 1.15f else 0.85f, label = "swipe-icon")

    SwipeToDismissBox(
        state = state,
        modifier = modifier.onSizeChanged { probe.widthPx = it.width },
        backgroundContent = {
            // Dessiné pendant le geste seulement. Au repos il n'y a rien
            // derrière la ligne.
            if (dismissing && action != SwipeAction.NONE) {
                val (icon, label) = swipeGlyph(action, restore)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tint)
                        .padding(horizontal = 24.dp),
                    contentAlignment =
                    if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    },
                ) {
                    Icon(
                        icon,
                        contentDescription = stringResource(label),
                        tint = if (armed) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.scale(iconScale),
                    )
                }
            }
        },
        // Opaque, pour que rien derrière la ligne ne transparaisse au travers.
        content = {
            Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
        },
    )
}

/**
 * L'icône de l'action réellement configurée pour cette direction.
 *
 * Le fond affichait « Archiver » quoi qu'on ait réglé : reporter un courriel
 * montrait quand même la boîte d'archives, ce qui décrit la mauvaise action au
 * moment précis où la personne décide de relâcher ou non.
 */
private fun swipeGlyph(action: SwipeAction, restore: Boolean): Pair<ImageVector, Int> =
    when (action) {
        SwipeAction.ARCHIVE ->
            if (restore) Icons.Filled.Inbox to R.string.swipe_restore
            else Icons.Filled.Archive to R.string.common_archive
        SwipeAction.SNOOZE -> Icons.Filled.Schedule to R.string.common_snooze
        SwipeAction.MARK_READ -> Icons.Filled.Drafts to R.string.common_mark_read
        SwipeAction.NONE -> Icons.Filled.Archive to R.string.common_archive
    }
