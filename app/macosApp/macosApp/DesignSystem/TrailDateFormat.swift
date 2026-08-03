import Foundation

/// All human-facing Trail date/time formatting (ADR-0046): the bridge is Kotlin/Native (no `java.time`), so
/// it exposes only the device-local ISO day key + epoch seconds and every rendered date is formatted here
/// with `DateFormatter` (native, locale-aware).
///
/// **Twin note.** iOS keeps this enum at the bottom of `Tasks/TaskDetailView.swift`; macOS hosts it in
/// `DesignSystem/` instead, because `L.diffValueText` (DesignSystem/Localization.swift) calls
/// ``instantValue(_:)`` — leaving it in `Tasks/` would point the design system back at a feature folder. The
/// bodies stay a twin of `app/iosApp/iosApp/Tasks/TaskDetailView.swift`; keep them in sync.
enum TrailDateFormat {
    /// The row time (e.g. "4:17 PM") — the short, locale-aware time of an activity instant. The formatter is
    /// a cached static (iOS allocates per call): a desktop-width Trail renders far more rows at once, and
    /// `DateFormatter` init is expensive.
    static func time(_ epoch: Double) -> String {
        timeFormatter.string(from: Date(timeIntervalSince1970: epoch))
    }

    /// The diff subtitle / calm meta line ("MMM d · HH:mm") for an activity instant.
    static func whenLabel(_ epoch: Double) -> String {
        whenFormatter.string(from: Date(timeIntervalSince1970: epoch))
    }

    /// An instant-valued diff value (DEADLINE or the soft TARGET_DATE, #375): parse the raw RFC3339 instant
    /// and render it "MMM d · HH:mm"; on a parse failure return the raw string (matches the Compose
    /// `getOrDefault(raw)`). Named for the *value shape*, not for one field — the two remain distinct fields
    /// in the Trail, they merely share this formatter.
    static func instantValue(_ rfc3339: String) -> String {
        guard let date = parseInstant(rfc3339) else { return rfc3339 }
        return whenFormatter.string(from: date)
    }

    /// The device-local ISO day (yyyy-MM-dd) for `Date()`, to compare against the bridge's `activityItemDayIso`.
    static func todayIso() -> String { isoDayFormatter.string(from: Date()) }

    /// Whether [day] (a device-local ISO day key) is the device-local today.
    static func dayIsoIsToday(_ day: String) -> Bool { day == todayIso() }

    private static func parseInstant(_ rfc3339: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: rfc3339) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: rfc3339)
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.timeStyle = .short
        return formatter
    }()

    private static let whenFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.dateFormat = "MMM d · HH:mm"
        return formatter
    }()

    /// The day-bucket key. Pinned to `Calendar.current` + POSIX + "yyyy-MM-dd" so it matches the bridge's
    /// `activityItemDayIso` (Kotlin `LocalDate.toString()`) exactly — a locale-shifted format here would
    /// silently mis-bucket every row.
    private static let isoDayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar.current
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()
}
