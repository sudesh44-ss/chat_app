package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private fun nextLocalMidnightMillis(zoneId: ZoneId): Long {
    val now = ZonedDateTime.now(zoneId)
    val nextDay = now.toLocalDate().plusDays(1).atStartOfDay(zoneId)
    return nextDay.toInstant().toEpochMilli()
}

fun getDateSeparatorLabel(
    timestamp: Long,
    currentDate: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val messageDate = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
    val today = currentDate
    val yesterday = today.minusDays(1)

    return when (messageDate) {
        today -> "TODAY"
        yesterday -> "YESTERDAY"
        else -> messageDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault()))
    }
}

@Composable
fun rememberCurrentLocalDate(
    zoneId: ZoneId = ZoneId.systemDefault()
): State<LocalDate> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentDate = remember { mutableStateOf(LocalDate.now(zoneId)) }

    DisposableEffect(lifecycleOwner, zoneId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
                currentDate.value = LocalDate.now(ZoneId.systemDefault())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(lifecycleOwner, zoneId) {
        while (isActive) {
            val nowMillis = System.currentTimeMillis()
            val currentZoneId = ZoneId.systemDefault()
            val nextMidnightMillis = nextLocalMidnightMillis(currentZoneId)
            val waitMillis = max(1L, nextMidnightMillis - nowMillis)
            delay(waitMillis)
            currentDate.value = LocalDate.now(ZoneId.systemDefault())
        }
    }

    return currentDate
}

/**
 * Centered Liquid Glass date separator pill displayed between messages of different calendar days.
 */
@Composable
fun ChatDateSeparator(
    timestamp: Long,
    modifier: Modifier = Modifier,
    currentDate: LocalDate? = null
) {
    val resolvedCurrentDate = currentDate ?: rememberCurrentLocalDate().value
    val isDark = isSystemInDarkTheme()
    val zoneId = ZoneId.systemDefault()
    val formattedDate = remember(timestamp, resolvedCurrentDate, zoneId) {
        getDateSeparatorLabel(timestamp, resolvedCurrentDate, zoneId)
    }

    val surfaceColor = if (isDark) Color(0x33FFFFFF) else Color(0x18000000)
    val textColor = if (isDark) Color(0xDDFFFFFF) else Color(0xDD000000)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        NexaGlassSurface(
            shape = RoundedCornerShape(12.dp),
            surfaceColor = surfaceColor,
            elevation = 1.dp
        ) {
            Text(
                text = formattedDate,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Helper to check if two UTC epoch timestamps fall on the same calendar day in local timezone.
 */
fun isSameCalendarDay(timestamp1: Long, timestamp2: Long): Boolean {
    val zoneId = ZoneId.systemDefault()
    val date1 = Instant.ofEpochMilli(timestamp1).atZone(zoneId).toLocalDate()
    val date2 = Instant.ofEpochMilli(timestamp2).atZone(zoneId).toLocalDate()
    return date1 == date2
}
