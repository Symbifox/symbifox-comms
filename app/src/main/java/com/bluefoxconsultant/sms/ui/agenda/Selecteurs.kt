package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

/**
 * Les sélecteurs de date et d'heure d'Android, là où l'app offrait des
 * chips « −1 j / +1 j » et dix heures rondes.
 *
 * ⚠️ `DatePickerState` compte en millisecondes UTC à minuit. Convertir par le
 * fuseau de l'appareil ferait glisser la date d'un jour à l'ouest de
 * Greenwich (Montréal : 8 septembre 00:00 local = 8 septembre 04:00 UTC, mais
 * 8 septembre 00:00 UTC = 7 septembre 20:00 local). Les deux conversions
 * passent par [millisUtc] et [dateDepuisMillisUtc], et c'est elles que le
 * banc éprouve.
 */
fun millisUtc(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun dateDepuisMillisUtc(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** Au quart d'heure le plus proche : une rencontre à 09:07 n'existe pas. */
fun auQuartDHeure(t: LocalTime): LocalTime {
    val minutes = ((t.hour * 60 + t.minute + 7) / 15) * 15
    return LocalTime.of((minutes / 60) % 24, minutes % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogueDate(
    initiale: LocalDate,
    onChoisir: (LocalDate) -> Unit,
    onFermer: () -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = millisUtc(initiale))
    DatePickerDialog(
        onDismissRequest = onFermer,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onChoisir(dateDepuisMillisUtc(it)) }
                onFermer()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onFermer) { Text(stringResource(R.string.common_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogueHeure(
    initiale: LocalTime,
    onChoisir: (LocalTime) -> Unit,
    onFermer: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initiale.hour,
        initialMinute = initiale.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onFermer,
        confirmButton = {
            TextButton(onClick = {
                onChoisir(LocalTime.of(state.hour, state.minute))
                onFermer()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onFermer) { Text(stringResource(R.string.common_cancel)) } },
        text = { TimePicker(state = state) },
    )
}
