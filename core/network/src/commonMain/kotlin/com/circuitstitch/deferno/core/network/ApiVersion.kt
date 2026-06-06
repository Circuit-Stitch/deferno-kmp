package com.circuitstitch.deferno.core.network

/**
 * A parsed `major.minor` envelope version (ADR-0005). The envelope `version` is a
 * **breaking-contract counter** — additive, backward-compatible changes do not bump it — so
 * an ordered `major.minor` comparison is all the gate needs. Ordering is major-then-minor.
 */
data class ApiVersion(val major: Int, val minor: Int) : Comparable<ApiVersion> {
    override fun compareTo(other: ApiVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor })

    override fun toString(): String = "$major.$minor"

    companion object {
        /**
         * Parses `"<major>.<minor>"` (e.g. `"0.1"`), or `null` if it isn't exactly two
         * non-negative integer components. A malformed/unknown version is treated by the
         * reader as out-of-window (it can't be safely placed against the window).
         */
        fun parseOrNull(raw: String): ApiVersion? {
            val parts = raw.split('.')
            if (parts.size != 2) return null
            val major = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val minor = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return ApiVersion(major, minor)
        }
    }
}

/**
 * The envelope versions this client can safely read (ADR-0005, amended 2026-06-06). The window
 * is `[MIN..MAX]`; the live backend serves `0.1`, so today `MIN == MAX == 0.1`. This is the
 * **single bumpable constant** — widen [MAX] to `0.2` once backend
 * [#300](https://github.com/Kyle-Falconer/Deferno/issues/300) lands and declares `0.2` honestly.
 *
 * Out-of-window policy: a version **above** [MAX] is an unknown breaking major the client
 * can't parse safely (a future force-upgrade gate); **below** [MIN] is degrade/refuse. Both
 * surface as [ApiError.UnsupportedVersion] rather than handing up possibly-misparsed data. The
 * versioned-adapter routing the ADR describes ships present-but-empty: a single pass-through
 * for in-window versions, earning its first per-version mapper only at the next breaking bump.
 */
object SupportedApiVersions {
    val MIN: ApiVersion = ApiVersion(0, 1)
    val MAX: ApiVersion = ApiVersion(0, 1)

    /** The closed `[MIN..MAX]` window. */
    val window: ClosedRange<ApiVersion> = MIN..MAX

    /** Whether [version] is inside the supported window. */
    fun supports(version: ApiVersion): Boolean = version in window
}
