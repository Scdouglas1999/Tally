package com.github.damontecres.wholphin.jellytv.together

import java.time.Duration
import java.time.Instant

/**
 * One NTP-style exchange with `GET /GetUtcTime`: [sentAt] and [receivedAt] are local clock readings around the
 * request; [serverReceived] and [serverSent] are the server's `RequestReceptionTime` / `ResponseTransmissionTime`.
 */
data class ClockSample(
    val sentAt: Instant,
    val serverReceived: Instant,
    val serverSent: Instant,
    val receivedAt: Instant,
) {
    /** Network time only: the whole exchange minus the server's own processing. */
    val roundTrip: Duration
        get() = Duration.between(sentAt, receivedAt).minus(Duration.between(serverReceived, serverSent))

    /** server clock − local clock. */
    val offset: Duration
        get() = Duration.between(sentAt, serverReceived).plus(Duration.between(receivedAt, serverSent)).dividedBy(2)
}

/**
 * The best current estimate of the server's clock: the offset of the sample with the smallest round trip among
 * the last [WINDOW] samples (a fast exchange bounds the error best). Pure and thread-confined to its owner.
 */
class ServerClock {
    private val samples = ArrayDeque<ClockSample>()

    fun add(sample: ClockSample) {
        samples.addLast(sample)
        while (samples.size > WINDOW) samples.removeFirst()
    }

    val hasSample: Boolean get() = samples.isNotEmpty()

    /** server − local; zero until the first sample. */
    val offset: Duration get() = samples.minByOrNull { it.roundTrip }?.offset ?: Duration.ZERO

    /** Round trip of the best sample, for `POST /SyncPlay/Ping` (milliseconds). */
    val pingMs: Long get() = samples.minByOrNull { it.roundTrip }?.roundTrip?.toMillis() ?: 0L

    fun serverNow(localNow: Instant): Instant = localNow.plus(offset)

    fun toLocal(serverTime: Instant): Instant = serverTime.minus(offset)

    companion object {
        const val WINDOW = 8

        /** Sample this often while in a group (and 4 quick samples right after joining). */
        const val INTERVAL_MS = 10_000L
    }
}
