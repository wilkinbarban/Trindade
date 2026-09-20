package com.trindade.app.update

/**
 * The only clock in this app, and it exists for exactly one reason: the update check's answer is cached
 * for a bounded time, and a test has to be able to stand on the other side of that bound without waiting
 * half an hour and without a device.
 *
 * A `fun interface` with a single method rather than a dependency on a time library, because the cache
 * needs one number -- milliseconds from an origin that only moves forwards -- and nothing else in this app
 * reads a clock at all. Keeping the seam to one method is what makes it obvious that this is the only place
 * time enters the product; a second time abstraction anywhere else would be the thing this note is here to
 * prevent.
 *
 * **An implementation must be monotonic**, and that requirement is stated here rather than beside the cache
 * that depends on it, because whoever writes the next implementation is the one who can put the bug back.
 * The cache asks one question -- is `now - writtenAt` below the window -- and everything it does follows
 * from the answer, so a reading that can step backwards is enough to break it: the difference goes
 * negative, the comparison stays true, a stale answer is served as fresh and GitHub is never asked, so the
 * ceiling the constant promises is bounded by the lifetime of the process instead of by the constant. Only
 * a difference is ever read, which is why this is easy to get wrong: a wall clock would do for a difference
 * in principle and does not do here, because a reading that moves backwards is not hypothetical -- an NTP
 * correction or a manual time change is enough, and neither is visible from inside this app. The binding
 * this app ships is `SystemClock.elapsedRealtime()`, in `com.trindade.app.di.ClockModule`.
 */
fun interface Clock {

    /**
     * The current reading in milliseconds, from an origin that never moves backwards.
     *
     * Only differences between two readings are used, so the origin itself is meaningless and does not have
     * to be the Unix epoch: the cache compares how long ago it wrote its answer, not when that was on a
     * calendar, and no time zone or calendar is involved.
     *
     * "Never moves backwards" is the requirement, not a description of wall clocks: see this type's note
     * for the one place the reading is used and what a backwards step does to it.
     */
    fun nowMillis(): Long
}
