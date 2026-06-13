package com.circuitstitch.deferno.core.common.log

import software.amazon.app.kmplogger.LogLevel as KmpLogLevel
import software.amazon.app.kmplogger.Logger as KmpLogger

/**
 * Android/JVM/iOS actual: delegate straight to amzn/kmp-logger (the targets it publishes a klib
 * for). The macOS actual lives in `macosMain` and writes to `os_log` directly (no published klib).
 */
actual class Logger actual constructor(tag: String) {
    private val delegate = KmpLogger(tag)

    actual fun v(message: () -> String) = delegate.v(message = message)
    actual fun d(message: () -> String) = delegate.d(message = message)
    actual fun i(message: () -> String) = delegate.i(message = message)
    actual fun w(throwable: Throwable?, message: () -> String) =
        delegate.w(throwable = throwable, message = message)
    actual fun e(throwable: Throwable?, message: () -> String) =
        delegate.e(throwable = throwable, message = message)

    actual companion object {
        actual fun configure(minLogLevel: LogLevel, prefix: String?) =
            KmpLogger.configure(minLogLevel = minLogLevel.toKmp(), prefix = prefix)

        // Nested (not top-level) so this file emits no `LoggerKt` JVM facade that would clash with
        // commonMain's `Logger.kt` (`Any.logger`) in the JVM/Android compilations.
        private fun LogLevel.toKmp(): KmpLogLevel = when (this) {
            LogLevel.VERBOSE -> KmpLogLevel.VERBOSE
            LogLevel.DEBUG -> KmpLogLevel.DEBUG
            LogLevel.INFO -> KmpLogLevel.INFO
            LogLevel.WARN -> KmpLogLevel.WARN
            LogLevel.ERROR -> KmpLogLevel.ERROR
        }
    }
}
