package com.circuitstitch.deferno.core.common.log

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.darwin.OS_LOG_DEFAULT
import platform.darwin.OS_LOG_TYPE_DEBUG
import platform.darwin.OS_LOG_TYPE_DEFAULT
import platform.darwin.OS_LOG_TYPE_ERROR
import platform.darwin.OS_LOG_TYPE_INFO
import platform.darwin.__dso_handle
import platform.darwin._os_log_internal

/**
 * macOS actual: write to the unified logging system (`os_log`) via `_os_log_internal`, the same path
 * kmp-logger's iOS actual takes — kmp-logger 0.0.1 ships no `macosArm64` klib, so we reproduce its
 * (tiny) behaviour here. Output format and level→`os_log` mapping match the iOS side, so macOS logs
 * are indistinguishable from the rest of the fleet in Console.app.
 */
actual class Logger actual constructor(private val tag: String) {

    actual fun v(message: () -> String) = log(LogLevel.VERBOSE, null, message)
    actual fun d(message: () -> String) = log(LogLevel.DEBUG, null, message)
    actual fun i(message: () -> String) = log(LogLevel.INFO, null, message)
    actual fun w(throwable: Throwable?, message: () -> String) = log(LogLevel.WARN, throwable, message)
    actual fun e(throwable: Throwable?, message: () -> String) = log(LogLevel.ERROR, throwable, message)

    private fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
        if (level.severity < minSeverity) return
        write(level, "(${prefixedTag(tag)}) ${message()}${throwable.suffix()}")
    }

    actual companion object {
        private var minSeverity: Int = LogLevel.VERBOSE.severity
        private var prefix: String? = null

        actual fun configure(minLogLevel: LogLevel, prefix: String?) {
            require(prefix == null || prefix.isNotBlank()) { "Prefix must not be blank if provided." }
            minSeverity = minLogLevel.severity
            this.prefix = prefix
        }

        private fun prefixedTag(tag: String): String = prefix?.let { "$it: $tag" } ?: tag
    }
}

private fun Throwable?.suffix(): String =
    this?.let { "\nException: ${it.stackTraceToString()}" } ?: ""

@OptIn(ExperimentalForeignApi::class)
private fun write(level: LogLevel, message: String) {
    _os_log_internal(
        dso = __dso_handle.ptr,
        log = OS_LOG_DEFAULT,
        type = when (level) {
            LogLevel.VERBOSE, LogLevel.DEBUG -> OS_LOG_TYPE_DEBUG
            LogLevel.INFO -> OS_LOG_TYPE_INFO
            LogLevel.WARN -> OS_LOG_TYPE_DEFAULT
            LogLevel.ERROR -> OS_LOG_TYPE_ERROR
        },
        message = message,
    )
}
