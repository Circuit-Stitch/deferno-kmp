import Deferno
import SwiftUI

/// The **recurring definition** detail pane (#383) — the surface a Habit, Chore or Event opens into.
/// Thin renderer of `DefinitionDetailComponent`: it observes the hydrating record and forwards exactly one
/// intent, Close.
///
/// **Read-only, by design and for this slice.** Every write on a recurring definition — editing the rule,
/// per-field patches, delete — belongs to #378/#388/#389, whose seams are still `TaskId`-typed. The one
/// write that already works kind-neutrally, the Archive/Restore light switch, lives in the tree row's
/// command menu (#299) and is deliberately not duplicated here. So this pane has no pickers, no editable
/// rows, no overflow menu and no composer: the Task detail's write affordances are absent because there is
/// nothing behind them yet, not because they were forgotten.
///
/// It is a **sibling** of `TaskDetailView`, not a variant of it — the two components are separate for the
/// reasons `DefinitionDetailComponent`'s KDoc sets out, and the surfaces differ the same way. What is
/// shared is the chrome and the atoms (`DrilledBackBar`, `SectionTitle`, `MarkdownDescription`,
/// `SectionLabel`), which is why those became internal rather than being copied.
///
/// **`state.eras` is deliberately not rendered.** The `SeriesChain` rides the detail read and this slice
/// only carries it; which era you are looking at, when each split and what the earlier rules were is #395.
struct DefinitionDetailView: View {
    let component: DefinitionDetailComponent
    /// Hide the header's Back control. Set at a detached window's root (#196) — it has nothing to pop and
    /// the window's own chrome closes it. Default false: the inline Tasks pane keeps its Back, which
    /// routes through the component's `Closed` output to dismiss the detail slot.
    var hidesBackControl: Bool = false
    @StateObject private var state: StateFlowObserver<DefinitionDetailState>
    @Environment(\.defernoColors) private var colors

    init(component: DefinitionDetailComponent, hidesBackControl: Bool = false) {
        self.component = component
        self.hidesBackControl = hidesBackControl
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            DrilledBackBar(onBack: hidesBackControl ? nil : { component.onCloseClicked() })
            if value.isHydrating && value.definition == nil {
                LoadingStrip(label: L.string("tasks_detail_loading"))
            }
            if value.isMissing {
                // The KIND-NEUTRAL pair (#383), not the Task-worded `tasks_detail_not_found_*`: this pane
                // reaches the empty state before it can know which kind went missing, and an item that
                // was never a Task must not be mourned as one.
                EmptyStateView(
                    title: L.string("tasks_detail_item_not_found_title"),
                    message: L.string("tasks_detail_item_not_found_body")
                )
            } else if let definition = value.definition {
                definitionBody(definition, value)
            } else {
                Spacer() // the brief hydrating gap before the cached row is observed; the strip shows it
            }
        }
        .background(colors.background)
    }

    @ViewBuilder
    private func definitionBody(_ definition: RecurringDefinition, _ value: DefinitionDetailState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header(definition)
                notesSection(definition, isHydrating: value.isHydrating)
                Divider()
                propertiesSection(definition, value)
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
        }
    }

    /// The heading: title + `ref`. Deliberately **not** `TaskDetailView`'s `ConnectedParentHeader` — a
    /// definition carries a `parentId` but this read fetches no parent summary, so there is no title to
    /// draw the connected node with. Inventing one from the id would be a link to an unnamed thing.
    @ViewBuilder
    private func header(_ definition: RecurringDefinition) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(definition.title)
                .font(.title2.weight(.semibold))
                .foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
            if let ref = definition.ref {
                Text(ref).font(.footnote.monospaced()).foregroundStyle(colors.inkMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// NOTES — the eyebrow plus the description, the field the `Item` tree projection deliberately does not
    /// carry (it is one of the two reasons this pane reads the concrete record as well). Rendered as
    /// markdown for the same reason the Task detail is: an imported body is GFM, and raw `**` is the
    /// tracker's markup leaking through. The header hides only during the pre-hydration gap, so an empty
    /// description still gets its "No description yet." once hydration settles.
    @ViewBuilder
    private func notesSection(_ definition: RecurringDefinition, isHydrating: Bool) -> some View {
        let description = definition.itemDescription?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasDescription = !(description ?? "").isEmpty
        if hasDescription || !isHydrating {
            SectionLabel(L.string("new_notes_label"))
        }
        if hasDescription, let description {
            MarkdownDescription(markdown: description, sheetTitle: L.string("new_notes_label"))
        } else if !isHydrating {
            Text(L.string("tasks_detail_no_description"))
                .font(.callout)
                .foregroundStyle(colors.inkMuted)
        }
    }

    // MARK: - Properties (Kind · Status · Repeats · Next due · Today · Labels · Source)

    /// The properties table.
    ///
    /// **Row order carries meaning.** KIND first, because "what kind of thing is this" is the question a
    /// pane reached from a mixed tree answers before any other. STATUS next — the [[Definition state]]
    /// light switch, which is what makes a definition live or dormant. Then the three *schedule* rows as
    /// one block: how often it REPEATS, when it is NEXT DUE, and how TODAY reads. Those three are the
    /// answers to one question asked at three ranges, and separating them would read as three unrelated
    /// facts. LABELS and SOURCE close, both optional and both about provenance rather than schedule.
    ///
    /// Every row is read-only, so none carries a chevron or a picker — see the type doc.
    @ViewBuilder
    private func propertiesSection(_ definition: RecurringDefinition, _ value: DefinitionDetailState) -> some View {
        // ONE bridge crossing for the whole rule (`RecurrenceLineTokens`), shared by REPEATS and NEXT DUE:
        // each read re-derives the cadence *and* the cursor, and the cursor is a reading against today.
        let tokens = value.item.flatMap { RecurrenceReadingKt.recurrenceLineTokens(item: $0) }
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle(L.string("tasks_detail_section_properties"))
            kindRow(definition)
            propertyRow(
                label: L.string("tasks_detail_property_status"),
                value: L.string(BridgeKt.definitionStateToken(state: definition.definitionState))
            )
            propertyRow(label: L.string("tasks_detail_property_repeats"), value: repeatsValue(tokens))
            propertyRow(label: L.string("tasks_detail_property_next_due"), value: tokens.flatMap { L.cursorDay($0) } ?? Self.absent)
            todayRow(value.today)
            if !definition.labels.isEmpty {
                labelsRow(definition.labels)
            }
            if let origin = value.originLabel {
                // The server-derived provenance label — a tracker ref or a calendar's display name. It
                // rides the detail read (there is no `external` block on a definition to derive it from),
                // so this row is the only place it can appear, and only for an imported item.
                propertyRow(label: L.string("tasks_detail_property_source"), value: origin)
            }
        }
    }

    /// The KIND row. The value is the all-caps marker the tree already uses for this vocabulary, so it is
    /// paired with the lowercase spoken noun — the rule every uppercasing atom in this app follows, since
    /// VoiceOver reads all-caps either letter-by-letter or in the shouted voice.
    private func kindRow(_ definition: RecurringDefinition) -> some View {
        HStack {
            Text(L.string("tasks_detail_property_kind"))
                .font(.subheadline).foregroundStyle(colors.inkMuted).frame(width: 72, alignment: .leading)
            Text(kindDisplayLabel(definition.kind))
                .font(.body)
                .foregroundStyle(colors.onSurface)
                .accessibilityLabel(kindA11yLabel(definition.kind))
            Spacer()
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    /// The **TODAY** row — the honesty contract of this pane (ADR-0053 decision 4).
    ///
    /// The whole mapping lives in `BridgeKt.todayCell`, in one exhaustive Kotlin `when`, because the four
    /// answers are easy to collapse and one collapse in particular states something untrue: a grid this
    /// device merely failed to reproduce is **not** a grid that says nothing fires. So this View asks for
    /// the reading and renders what it is handed — there is no `if` chain here to get wrong, and no second
    /// copy of the rule to drift from the iOS twin or from Compose.
    ///
    /// A status reading renders as the calendar's chip; the three grid answers render as plain muted words,
    /// because they are statements about the *schedule* rather than about how a firing went, and a chip
    /// would present them as the latter.
    @ViewBuilder
    private func todayRow(_ today: TodayOccurrence) -> some View {
        let cell = BridgeKt.todayCell(today: today)
        let text = L.string(cell.labelKey)
        HStack {
            Text(L.string("tasks_detail_property_today"))
                .font(.subheadline).foregroundStyle(colors.inkMuted).frame(width: 72, alignment: .leading)
            if cell.isStatus {
                Text(text)
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(cell.isDone ? colors.successContainer : colors.surfaceVariant, in: Capsule())
                    .foregroundStyle(colors.onSurface)
            } else {
                Text(text).font(.body).foregroundStyle(colors.inkMuted)
            }
            Spacer()
        }
        .frame(minHeight: Layout.minTouchTarget)
        .accessibilityElement(children: .ignore)
        // The read-only twin of the Task detail's status-row label: it names the row and reads the cell,
        // and it must not end in "Tap to change" — nothing here is tappable.
        .accessibilityLabel(L.format("tasks_detail_today_row_a11y", text))
    }

    /// The LABELS row — read-only chips, so no `×` per chip and no add field (the Task detail's are its
    /// write affordances). Rendered only when there are labels: an empty section header on a pane with
    /// nothing to add is noise.
    @ViewBuilder
    private func labelsRow(_ labels: [String]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionLabel(L.string("common_labels"))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(labels, id: \.self) { label in
                        Text(label)
                            .font(.subheadline)
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .foregroundStyle(colors.onSurfaceVariant)
                            .background(colors.surfaceVariant, in: Capsule())
                    }
                }
            }
        }
    }

    /// The **REPEATS** value: the cadence phrase and, when the rule is bounded, its end clause — "Weekly on
    /// Mon, Wed · until Jun 14, 2026". The next-due half of the tree row's line is deliberately not here:
    /// it is the NEXT DUE row directly below, and saying it twice on one pane would be two readings of one
    /// cursor.
    ///
    /// **KNOWN GAP, not an oversight.** The catalog carries the monthly/yearly *anchor* grammar this pane
    /// was meant to own — `tasks_cadence_monthly_on_weekday`, `_monthly_on_day`, `_yearly_on` and their
    /// interval plurals — but the shared `RecurrenceLineTokens` does not carry `MonthlyAnchor` or
    /// `Cadence.Yearly`'s month/day, so a monthly rule reads "Monthly" here rather than "Monthly on the
    /// second Wed". Deriving it Swift-side is the one thing this file must not do: `Localization.swift`'s
    /// own rule is that a cadence rule written in Swift is a rule the other three platforms do not have,
    /// and the shared reading is out of this change's scope (`feature/tasks/RecurrenceReading.kt`). The
    /// keys are live and unreachable until that projection grows the anchor — the phrase is vague, never
    /// wrong.
    private func repeatsValue(_ tokens: RecurrenceLineTokens?) -> String {
        guard let tokens else { return Self.absent }
        let phrase = L.cadence(tokens).phrase
        guard let bound = L.cadenceBound(tokens) else { return phrase }
        return L.format("tasks_cadence_with_bound", phrase, bound)
    }

    /// A fixed-width label + flexible value, matching the Task detail's table so the two panes line up in
    /// the same 250pt-minimum column (#194).
    private func propertyRow(label: String, value: String) -> some View {
        HStack {
            Text(label).font(.subheadline).foregroundStyle(colors.inkMuted).frame(width: 72, alignment: .leading)
            Text(value).font(.body).foregroundStyle(value == Self.absent ? colors.inkMuted : colors.onSurface)
            Spacer()
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    /// The table's "nothing here" mark — punctuation, not prose, and the same glyph the Task detail's
    /// table uses so the two read as one design rather than two.
    private static let absent = "—"
}
