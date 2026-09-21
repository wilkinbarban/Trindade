package com.trindade.app.logging

/**
 * The app's logging seam.
 *
 * [throwable] is required rather than optional, and that is `android-app-v1.md`'s `C1` reasoning
 * applied to a log call: a default would let a new call site drop a stack trace without saying so,
 * which is a silent failure the compiler can prevent instead. A call with nothing to attach passes
 * null and states it.
 */
interface AppLogger {
    fun w(tag: String, message: String, throwable: Throwable?)
}

object AndroidAppLogger : AppLogger {
    override fun w(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.w(tag, message, throwable)
    }
}

object NoOpAppLogger : AppLogger {
    override fun w(tag: String, message: String, throwable: Throwable?) {}
}
