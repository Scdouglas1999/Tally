package io.github.scdouglas1999.tally.media.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long after a failed load a card picture is asked for again: once after the first delay, and once more after the
 * second. A picture that fails three times stays empty until the card is drawn again.
 */
internal val ImageRetryDelaysMs = listOf(1_000L, 3_000L)

/** The delay before retry number [attempt] (0 = the first retry), or null when there are no retries left. */
internal fun imageRetryDelay(attempt: Int): Long? = ImageRetryDelaysMs.getOrNull(attempt)

/**
 * Coil's [AsyncImage] for Tally's card pictures, retried with a short backoff ([ImageRetryDelaysMs]) when a load fails
 * (a dropped connection, "Connection reset"): Coil never asks again for a picture that failed once. Each retry starts
 * a new request; [onError] is told of every failure.
 */
@Composable
fun RetryingAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    var attempt by remember(model) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    key(model, attempt) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            alignment = alignment,
            onError = { state ->
                onError?.invoke(state)
                val failed = attempt
                val wait = imageRetryDelay(failed) ?: return@AsyncImage
                scope.launch {
                    delay(wait)
                    if (attempt == failed) attempt = failed + 1
                }
            },
            modifier = modifier,
        )
    }
}
