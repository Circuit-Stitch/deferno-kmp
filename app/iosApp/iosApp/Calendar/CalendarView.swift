import Deferno
import SwiftUI

/// The Calendar Destination (#74): a single-pane month grid + day agenda over Occurrences. A thin
/// renderer of `CalendarComponent`.
///
/// Each agenda row is a `CalendarFiring` — the feed row paired with how *that day's* firing went — and
/// the chip renders whatever pre-localized token `ShellBridgeKt.occurrenceStatusToken` hands it, so
/// this View never re-derives a reading and never reads `item.status` for a firing (an offline mark no
/// longer touches that field, so a chip driven off it would look frozen). The vocabulary is factual
/// rather than suppressed (ADR-0053 decision 7): a past unfinished firing reads "Missed", and a day
/// this device has never synced reads "Not synced" rather than being guessed at.
///
/// Reschedule uses an in-View "pick a new day" mode (identical to Android by design — no native date
/// picker), arming the next day-cell tap.
struct CalendarView: View {
    let component: CalendarComponent
    @StateObject private var state: StateFlowObserver<CalendarState>
    @Environment(\.defernoColors) private var colors
    /// The agenda item awaiting a new day (the local reschedule mode), or nil.
    @State private var rescheduling: CalendarItem?

    // Locale-aware date rendering: the shared per-locale patterns (calendar_*_pattern) drive the
    // formats, and the system supplies month/weekday names — no hand-rolled name tables (#327).
    private static let monthYearFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = L.string("calendar_month_year_pattern")
        return f
    }()
    private static let agendaHeadingFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = L.string("calendar_agenda_heading_pattern")
        return f
    }()
    /// Monday-first, matching the ISO grid the shell provides (system symbols are Sunday-first).
    private static let weekdayShort: [String] = {
        let symbols = DateFormatter().shortStandaloneWeekdaySymbols ?? ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
        return Array(symbols[1...]) + [symbols[0]]
    }()

    private static func date(year: Int, month: Int, day: Int) -> Date {
        DateComponents(calendar: .current, year: year, month: month, day: day).date ?? Date()
    }

    init(component: CalendarComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        // No NavigationStack/title here: the single adaptive shell bar (MainShellView) titles "Calendar".
        VStack(spacing: 0) {
            monthHeader(value)
            weekdayHeader
            monthGrid(value)
            Divider().background(colors.outlineVariant)
            if let item = rescheduling {
                rescheduleBanner(item)
            }
            dayAgenda(value)
        }
        .background(colors.background)
    }

    // MARK: Month header

    private func monthHeader(_ value: CalendarState) -> some View {
        HStack {
            Button { component.onShowPreviousMonth() } label: { Image(systemName: "chevron.left") }
                .frame(minWidth: Layout.minTouchTarget, minHeight: Layout.minTouchTarget)
                .accessibilityLabel(L.string("calendar_previous_month"))
            Spacer()
            Text(monthLabel(value.visibleMonth))
                .font(.title2.weight(.semibold))
                .foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button { component.onShowNextMonth() } label: { Image(systemName: "chevron.right") }
                .frame(minWidth: Layout.minTouchTarget, minHeight: Layout.minTouchTarget)
                .accessibilityLabel(L.string("calendar_next_month"))
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 56)
        .foregroundStyle(colors.onSurface)
    }

    private var weekdayHeader: some View {
        HStack(spacing: 0) {
            ForEach(Self.weekdayShort, id: \.self) { day in
                Text(day)
                    .font(.caption2)
                    .foregroundStyle(colors.inkMuted)
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityHidden(true)
        .padding(.horizontal, 8)
    }

    // MARK: Month grid

    private func monthGrid(_ value: CalendarState) -> some View {
        let days = ShellBridgeKt.calendarGridDays(visibleMonth: value.visibleMonth)
        let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 7)
        return LazyVGrid(columns: columns, spacing: 2) {
            ForEach(0..<days.count, id: \.self) { index in
                dayCell(days[index], value: value)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }

    private func dayCell(_ day: LocalDate, value: CalendarState) -> some View {
        let inMonth = ShellBridgeKt.localDateInMonth(date: day, monthRef: value.visibleMonth)
        let selected = ShellBridgeKt.localDateEquals(a: day, b: value.selectedDay)
        let count = Int(ShellBridgeKt.markerCount(state: value, date: day))
        let number = Int(ShellBridgeKt.localDateDay(date: day))
        return Button { onDayTapped(day) } label: {
            VStack(spacing: 2) {
                Text("\(number)")
                    .font(.callout.weight(selected ? .bold : .regular))
                    .foregroundStyle(selected ? colors.onPrimary : (inMonth ? colors.onSurface : colors.inkMuted))
                Circle()
                    .fill(count > 0 ? (selected ? colors.onPrimary : colors.amberDeep) : Color.clear)
                    .frame(width: 5, height: 5)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(selected ? colors.primary : Color.clear, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(dayAccessibilityLabel(number: number, count: count, selected: selected))
    }

    private func onDayTapped(_ day: LocalDate) {
        if let item = rescheduling {
            component.onReschedule(itemId: item.id, newDate: day)
            rescheduling = nil
        } else {
            component.onDaySelected(date: day)
        }
    }

    // MARK: Reschedule banner

    private func rescheduleBanner(_ item: CalendarItem) -> some View {
        HStack {
            Text(L.format("calendar_reschedule_pick_day", item.title)).font(.subheadline)
            Spacer()
            Button(L.string("common_cancel")) { rescheduling = nil }
        }
        .padding(.horizontal, Layout.gutter)
        .padding(.vertical, 8)
        .background(colors.secondaryContainer)
    }

    // MARK: Day agenda

    private func dayAgenda(_ value: CalendarState) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(dayHeading(value.selectedDay))
                .font(.headline)
                .foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, Layout.gutter)
                .padding(.vertical, 8)
            if value.agenda.isEmpty {
                EmptyStateView(title: L.string("calendar_empty_title"),
                               message: L.string("calendar_empty_body"))
            } else {
                List {
                    // Keyed on the *row* id: `CalendarFiring` is the (row, reading) pair and has no id
                    // of its own — the identity that must stay stable across re-emissions is the feed
                    // row's, since a fact landing re-emits the whole agenda with fresh readings.
                    ForEach(value.agenda, id: \.item.id) { firing in
                        agendaRow(firing)
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(colors.background)
                    }
                }
                .listStyle(.plain)
            }
        }
    }

    private func agendaRow(_ firing: CalendarFiring) -> some View {
        let item = firing.item
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(item.title).font(.headline).foregroundStyle(colors.onSurface)
                Spacer()
                agendaStatusChip(firing)
            }
            if ShellBridgeKt.calendarItemActionable(item: item) {
                agendaActions(item)
            }
        }
        .padding(.horizontal, Layout.gutter)
        .padding(.vertical, 10)
    }

    @ViewBuilder
    private func agendaActions(_ item: CalendarItem) -> some View {
        let isHabit = ShellBridgeKt.calendarItemIsHabit(item: item)
        HStack(spacing: 8) {
            if !isHabit {
                actionChip(L.string("common_start")) { component.onMark(itemId: item.id, action: OccurrenceAction.start) }
            }
            actionChip(L.string("calendar_action_done")) { component.onMark(itemId: item.id, action: OccurrenceAction.complete) }
            if !isHabit {
                actionChip(L.string("calendar_action_skip")) { component.onMark(itemId: item.id, action: OccurrenceAction.skip) }
            }
            actionChip(L.string("common_clear")) { component.onClear(itemId: item.id) }
            // Reschedule is offered for every actionable firing (#380) — the backend ships habit, chore
            // and event reschedule over one shared handler; the old Events-only gate was a stale claim.
            actionChip(L.string("calendar_action_reschedule")) { rescheduling = item }
        }
    }

    private func actionChip(_ label: String, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .font(.footnote)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(colors.surfaceVariant, in: Capsule())
            .foregroundStyle(colors.onSurface)
            .frame(minHeight: 36)
    }

    /// The reading, rendered. Both the word and the tint come from the shared bridge — there is no
    /// `if` chain here to forget a state, and no second copy of the mapping to drift from the macOS
    /// twin or from Compose. See `ShellBridgeKt.occurrenceStatusToken` for why it lives there.
    private func agendaStatusChip(_ firing: CalendarFiring) -> some View {
        let isDone = ShellBridgeKt.occurrenceStatusIsDone(firing: firing)
        return Text(L.string(ShellBridgeKt.occurrenceStatusToken(firing: firing)))
            .font(.caption.weight(.medium))
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(isDone ? colors.successContainer : colors.surfaceVariant, in: Capsule())
            .foregroundStyle(colors.onSurface)
    }

    // MARK: Labels

    private func monthLabel(_ date: LocalDate) -> String {
        let month = Int(ShellBridgeKt.localDateMonthNumber(date: date))
        let year = Int(ShellBridgeKt.localDateYear(date: date))
        return Self.monthYearFormatter.string(from: Self.date(year: year, month: month, day: 1))
    }

    private func dayHeading(_ date: LocalDate) -> String {
        let year = Int(ShellBridgeKt.localDateYear(date: date))
        let month = Int(ShellBridgeKt.localDateMonthNumber(date: date))
        let day = Int(ShellBridgeKt.localDateDay(date: date))
        return Self.agendaHeadingFormatter.string(from: Self.date(year: year, month: month, day: day))
    }

    private func dayAccessibilityLabel(number: Int, count: Int, selected: Bool) -> String {
        var parts = [L.format("calendar_day_number_a11y", number)]
        if count > 0 { parts.append(L.plural("calendar_day_item_count", count)) }
        if selected { parts.append(L.string("calendar_day_selected")) }
        return parts.joined(separator: ", ")
    }
}
