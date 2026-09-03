package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Le pincement, et LUI SEUL.
 *
 * ⚠️ `detectTransformGestures` aurait été plus court, mais il consomme aussi le
 * glissement à un doigt : la grille aurait cessé de défiler, ce qui est le
 * geste le plus fréquent. On n'intervient donc qu'à partir de deux doigts, et
 * on ne consomme que ces événements-là.
 */
suspend fun PointerInputScope.detecterPincement(onZoom: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var evenement = awaitPointerEvent()
        while (evenement.changes.any { it.pressed }) {
            if (evenement.changes.size >= 2) {
                val facteur = evenement.calculateZoom()
                if (facteur != 1f) {
                    onZoom(facteur)
                    evenement.changes.forEach { it.consume() }
                }
            }
            evenement = awaitPointerEvent()
        }
    }
}
