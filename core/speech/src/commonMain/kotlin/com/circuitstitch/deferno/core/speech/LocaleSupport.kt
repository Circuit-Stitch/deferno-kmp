package com.circuitstitch.deferno.core.speech

/**
 * The v1 English-only gate (ADR-0018): every registered engine recognizes English only and reports
 * [UnavailableReason.UnsupportedLocale] for anything else — never a mis-transcription. One predicate,
 * shared by all engines, so post-v1 multi-locale support has a single seam to widen.
 */
internal fun String.isEnglishLocale(): Boolean = lowercase().startsWith("en")
