package com.github.damontecres.wholphin.jellytv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.time.Duration

/** Pick 15 / 30 / 45 / 60 / 90 minutes or "when this ends"; a running timer shows "Cancel". STUB. */
@Composable
fun SleepTimerDialog(
    remaining: Duration?,
    onStart: (Duration) -> Unit,
    onStartUntilEnd: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    onDismiss()
}

/** Small mono chip ("SLEEP 23:10") shown over any player while a timer runs. STUB. */
@Composable
fun SleepTimerChip(
    remaining: Duration,
    modifier: Modifier = Modifier,
) {
}
