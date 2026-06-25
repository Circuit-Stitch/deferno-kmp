package com.circuitstitch.deferno.core.common.log

/**
 * The project's **one uniform logging facade**, identical on every target.
 *
 * Backed by amzn/kmp-logger on every target (Logcat on Android, `os_log` on iOS + macOS, `println`
 * on JVM). iOS and macOS share one delegating actual in `appleMain`. Call sites are byte-identical
 * across platforms, so the macOS app logs exactly like Android/iOS/desktop.
 *
 * Configure once at process start, before anything logs:
 * ```
 * Logger.configure(minLogLevel = LogLevel.DEBUG, prefix = "Deferno")
 * ```
 * Then log via an explicit tag — `Logger("DesktopMain").i { "…" }` — or, inside a class, via the
 * [Any.logger] extension whose tag is the class's simple name: `logger.i { "…" }`. The message
 * lambda is only evaluated when the level passes the configured minimum (zero cost when disabled).
 */
expect class Logger(tag: String) {
    /** Verbose — lowest priority, detailed tracing. */
    fun v(message: () -> String)

    /** Debug — development/troubleshooting; off in release builds via the configured minimum. */
    fun d(message: () -> String)

    /** Info — normal application flow worth noting. */
    fun i(message: () -> String)

    /** Warning — a recoverable/unexpected situation, with an optional associated [throwable]. */
    fun w(throwable: Throwable? = null, message: () -> String)

    /** Error — a serious problem, with an optional associated [throwable]. */
    fun e(throwable: Throwable? = null, message: () -> String)

    companion object {
        /**
         * Sets the minimum level and an optional tag [prefix] (tags render as `"$prefix: $tag"`).
         * Call **once** at startup before any logging. [prefix] must not be blank if provided.
         */
        fun configure(minLogLevel: LogLevel, prefix: String? = null)
    }
}

/**
 * Logging severity, mirroring kmp-logger's levels (and their native mappings: Logcat priorities on
 * Android, `os_log` types on Apple, formatted `println` on the JVM). Ordered by increasing severity.
 */
enum class LogLevel(val severity: Int) {
    VERBOSE(1),
    DEBUG(2),
    INFO(3),
    WARN(4),
    ERROR(5),
}

/**
 * A [Logger] tagged with the caller's class simple-name, for `logger.i { "…" }` inside a class —
 * the twin of kmp-logger's `Any.logger`. For top-level functions (no class receiver) build a
 * [Logger] with an explicit tag instead.
 */
val Any.logger: Logger get() = Logger(this::class.simpleName ?: "Deferno")
