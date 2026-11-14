package com.nexa.app.presentation.chat.components

import android.app.DatePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Native Android Date Picker Dialog wrapped for Compose with past/today date boundary.
 * Prevents future-date selection by setting maxDate = System.currentTimeMillis().
 */
@Composable
fun ChatDatePickerDialog(
    onDateSelected: (epochDayMillis: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val zoneId = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zoneId) }

    DisposableEffect(Unit) {
        val dialog = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selectedLocalDate = LocalDate.of(year, month + 1, dayOfMonth)
                val epochMillis = selectedLocalDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                onDateSelected(epochMillis)
            },
            today.year,
            today.monthValue - 1,
            today.dayOfMonth
        )

        // Prevent selecting invalid future dates
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()

        onDispose {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }
}
