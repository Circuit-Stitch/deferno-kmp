import Deferno
import SwiftUI

/// The Calendar Destination (#74): a single-pane month grid + day agenda over Occurrences. A thin
/// renderer of `CalendarComponent`. Gentle vocabulary throughout — no "overdue"/"late"/"missed"; a
/// scheduled firing simply reads as "Scheduled". Reschedule uses an in-View "pick a new day" mode
/// (identical to Android by design — no native date picker), arming the next day-cell tap.
struct CalendarView: View {
    let component: CalendarComponent
    @StateObject private var state: StateFlowObserver<CalendarState>
    @Environment(\.defernoColors) private var colors
    /// The agenda item awaiting a new day (the local reschedule mode), or nil.
    @State private var rescheduling: CalendarItem?

    private static let monthNames = ["January", "February", "March", "April", "May", "June",
                                     "July", "August", "September", "October", "November", "December"]
    private static let weekdayShort = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    private static let weekdayLong = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

    init(component: CalendarComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.calendarStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
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
                .accessibilityLabel("Previous month")
            Spacer()
            Text(monthLabel(value.visibleMonth))
                .font(.title2.weight(.semibold))
                .foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button { component.onShowNextMonth() } label: { Image(systemName: "chevron.right") }
                .frame(minWidth: Layout.minTouchTarget, minHeight: Layout.minTouchTarget)
                .accessibilityLabel("Next month")
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
            Text("Pick a new day for “\(item.title)”").font(.subheadline)
            Spacer()
            Button("Cancel") { rescheduling = nil }
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
                EmptyStateView(title: "Nothing on this day",
                               message: "A clear day. Add something when you're ready — no pressure.")
            } else {
                List {
                    ForEach(value.agenda, id: \.id) { item in
                        agendaRow(item)
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(colors.background)
                    }
                }
                .listStyle(.plain)
            }
        }
    }

    private func agendaRow(_ item: CalendarItem) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(item.title).font(.headline).foregroundStyle(colors.onSurface)
                Spacer()
                agendaStatusChip(item.status)
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
        let canReschedule = ShellBridgeKt.calendarItemIsEvent(item: item)
        HStack(spacing: 8) {
            if !isHabit {
                actionChip("Start") { component.onMark(itemId: item.id, action: OccurrenceAction.start) }
            }
            actionChip("Done") { component.onMark(itemId: item.id, action: OccurrenceAction.complete) }
            if !isHabit {
                actionChip("Skip") { component.onMark(itemId: item.id, action: OccurrenceAction.skip) }
            }
            actionChip("Clear") { component.onClear(itemId: item.id) }
            if canReschedule {
                actionChip("Reschedule") { rescheduling = item }
            }
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

    private func agendaStatusChip(_ status: WorkingState) -> some View {
        Text(agendaStatusLabel(status))
            .font(.caption.weight(.medium))
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(status == WorkingState.done ? colors.successContainer : colors.surfaceVariant, in: Capsule())
            .foregroundStyle(colors.onSurface)
    }

    // MARK: Labels (gentle vocabulary)

    private func agendaStatusLabel(_ status: WorkingState) -> String {
        if status == WorkingState.open { return "Scheduled" }
        if status == WorkingState.inProgress { return "In progress" }
        if status == WorkingState.inReview { return "In review" }
        if status == WorkingState.done { return "Done" }
        if status == WorkingState.dropped { return "Skipped" }
        return status.label
    }

    private func monthLabel(_ date: LocalDate) -> String {
        let month = Int(ShellBridgeKt.localDateMonthNumber(date: date))
        let year = Int(ShellBridgeKt.localDateYear(date: date))
        return "\(Self.monthNames[month - 1]) \(year)"
    }

    private func dayHeading(_ date: LocalDate) -> String {
        let weekday = Int(ShellBridgeKt.localDateIsoDayOfWeek(date: date))
        let month = Int(ShellBridgeKt.localDateMonthNumber(date: date))
        let day = Int(ShellBridgeKt.localDateDay(date: date))
        return "\(Self.weekdayLong[weekday - 1]), \(Self.monthNames[month - 1]) \(day)"
    }

    private func dayAccessibilityLabel(number: Int, count: Int, selected: Bool) -> String {
        var parts = ["Day \(number)"]
        if count == 1 { parts.append("1 item") } else if count > 1 { parts.append("\(count) items") }
        if selected { parts.append("selected") }
        return parts.joined(separator: ", ")
    }
}
