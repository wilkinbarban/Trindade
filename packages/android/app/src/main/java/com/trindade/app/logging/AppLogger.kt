package com.trindade.app.logging

interface AppLogger {
    fun w(tag: String, message: String, throwable: Throwable? = null)
}

object AndroidAppLogger : AppLogger {
    override fun w(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.w(tag, message, throwable)
    }
}

object NoOpAppLogger : AppLogger {
    override fun w(tag: String, message: String, throwable: Throwable?) {}
}
