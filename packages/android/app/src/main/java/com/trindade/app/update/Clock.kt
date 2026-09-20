package com.trindade.app.update

/**
 * The only clock in this app, and it exists for exactly one reason: the update check's answer is cached
 * for a bounded time, and a test has to be able to stand on the other side of that bound without waiting
 * half an hour and without a device.
 *
 * A `fun interface` with a single method rather than a dependency on a time library, because the cache
 * needs one number -- milliseconds since the epoch, the same one [System.currentTimeMillis] gives -- and
 * nothing else in this app reads a clock at all. Keeping the seam to one method is what makes it obvious
 * that this is the only place time enters the product; a second time abstraction anywhere else would be
 * the thing this note is here to prevent.
 */
fun interface Clock {

    /**
     * The current time in milliseconds since the Unix epoch, matching [System.currentTimeMillis].
     *
     * Only differences between two readings are ever used, so a monotonic-enough wall clock is enough and
     * no time zone or calendar is involved.
     */
    fun nowMillis(): Long
}
