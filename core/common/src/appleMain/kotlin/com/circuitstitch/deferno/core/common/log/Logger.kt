package com.circuitstitch.deferno.core.common.log

import software.amazon.app.kmplogger.LogLevel as KmpLogLevel
import software.amazon.app.kmplogger.Logger as KmpLogger

/**
 * Apple actual (iOS + macOS): delegate straight to amzn/kmp-logger, which now ships a klib for every
 * Apple target this module builds (iosArm64, iosSimulatorArm64, macosArm64). kmp-logger's own
 * `os_log` writer lives in its shared `appleMain`, so one delegate here covers the whole Apple fleet
 * — no bespoke per-OS copy.
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

        // Nested (not top-level) so this file emits no `LoggerKt` facade clashing with commonMain's
        // `Logger.kt` (`Any.logger`) — harmless on native, kept identical to the former iosMain copy.
        private fun LogLevel.toKmp(): KmpLogLevel = when (this) {
            LogLevel.VERBOSE -> KmpLogLevel.VERBOSE
            LogLevel.DEBUG -> KmpLogLevel.DEBUG
            LogLevel.INFO -> KmpLogLevel.INFO
            LogLevel.WARN -> KmpLogLevel.WARN
            LogLevel.ERROR -> KmpLogLevel.ERROR
        }
    }
}
