import Deferno
import SwiftUI

/// The **recurring-definition** detail pane (#383) — a [[Habit]], [[Chore]] or [[Event]]. A thin renderer
/// of `DefinitionDetailComponent`: it observes the hydrating definition and forwards exactly one intent,
/// close. Before this, opening any of the three kinds was impossible on every platform — the tree's `›`
/// was Task-gated and the detail slot could only refuse a recurring row.
///
/// **Read-only by design, for this slice**, which is why there is no FAB, no kebab, no status picker and
/// no add field anywhere below: every write on a definition (the rule, the per-field patches, delete)
/// belongs to #378/#388/#389 and its seams are still `TaskId`-typed. The one write that already works
/// kind-neutrally — the Archive/Restore light switch — lives in the tree's command menu (#299) and is
/// deliberately not duplicated here.
///
/// It is a **sibling** of `TaskDetailView`, not a mode of it, for the reasons `DefinitionDetailComponent`'s
/// KDoc sets out — and it shares that view's `PropertyTableRow` / `DrilledBackBar` / `MarkdownDescription`
/// atoms rather than restating them, because the two render into the *same* pane and a second copy of the
/// table geometry is a second chance for them to drift apart on screen.
struct DefinitionDetailView: View {
    let component: DefinitionDetailComponent
    /// false when the View is **pushed** onto the compact `NavigationStack`: the native bar supplies the
    /// back chevron, so the in-pane `DrilledBackBar` is dropped (it would be a second back control). Same
    /// contract as `TaskDetailView.showsHeader`, so the host can pick the arm without knowing which.
    var showsHeader: Bool = true
    @StateObject private var state: StateFlowObserver<DefinitionDetailState>
    @Environment(\.defernoColors) private var colors

    init(component: DefinitionDetailComponent, showsHeader: Bool = true) {
        self.component = component
        self.showsHeader = showsHeader
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            if showsHeader {
                DrilledBackBar(onBack: { component.onCloseClicked() })
            }
            // Only while there's genuinely nothing on screen yet. Once the cached row is observed we render
            // it and let the background hydrate fill in description/labels/coverage silently.
            if value.isHydrating && value.definition == nil {
                LoadingStrip(label: L.string("tasks_detail_loading"))
            }
            // `isMissing` is the component's own "hydrate finished and produced nothing" — never a bare
            // `definition == nil`, which is also the ordinary cold-start frame. The copy is the KIND-NEUTRAL
            // pair (tasks_detail_item_not_found_*): this surface reaches the arm before it can know whether
            // the missing thing was a Habit, a Chore or an Event, so it says "item" and means it.
            if value.isMissing {
                EmptyStateView(
                    title: L.string("tasks_detail_item_not_found_title"),
                    message: L.string("tasks_detail_item_not_found_body")
                )
            } else if let definition = value.definition {
                definitionBody(definition, state: value)
            } else {
                Spacer() // brief hydrating gap before the row is observed; the strip above shows it
            }
        }
        .background(colors.background)
        .paneNavigationTitle(nil)
    }

    @ViewBuilder
    private func definitionBody(_ definition: RecurringDefinition, state value: DefinitionDetailState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                titleBlock(definition)
                notesSection(definition, isHydrating: value.isHydrating)
                propertiesTable(definition, state: value)
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
        }
    }

    /// The heading: the title at headline rank plus the short human ref. No kind chip and no external-ref
    /// prefix — the KIND row below carries the kind in words (colour alone is invisible to VoiceOver), and
    /// a recurring definition carries no `ExternalProvenance`.
    @ViewBuilder
    private func titleBlock(_ definition: RecurringDefinition) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(definition.title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(colors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            if let ref = shortRef(definition.ref) {
                MonoMeta(ref)
            }
        }
    }

    /// NOTES: the (markdown) description, or a muted "no description" once hydration settles. `description`
    /// is one of the two fields the `Item` projection deliberately does not carry, which is why this pane
    /// reads the concrete `RecurringDefinition` and not only `state.item`.
    @ViewBuilder
    private func notesSection(_ definition: RecurringDefinition, isHydrating: Bool) -> some View {
        let notes = definition.itemDescription?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !notes.isEmpty || !isHydrating {
            SectionLabel(L.string("new_notes_label"))
        }
        if !notes.isEmpty {
            MarkdownDescription(markdown: notes, sheetTitle: L.string("new_notes_label"))
        } else if !isHydrating {
            Text(L.string("tasks_detail_no_description"))
                .font(.callout)
                .foregroundStyle(colors.inkMuted)
        }
    }

    // MARK: - Properties table (KIND · STATUS · REPEATS · NEXT DUE · TODAY · LABELS)

    /// The properties table — the same tinted-label / hairline / rounded-border geometry as the Task
    /// detail's, answering the questions a definition has and a Task does not: *what* it is, whether the
    /// light switch is on, *how* it repeats, *when* it fires next, and how **today** reads.
    ///
    /// Only the rows this definition actually carries appear, which is the same rule the Task table
    /// follows for OWNER/SOURCE — and here it is load-bearing rather than tidy: REPEATS is absent when the
    /// rule did not survive the wire (#382), and NEXT DUE is absent for an **Archived** definition, whose
    /// stale cursor the shared reading deliberately refuses to believe (it keeps a cursor server-side but
    /// has no *next*). Rendering either as a dash would read as "no schedule", which is a different claim.
    private func propertiesTable(_ definition: RecurringDefinition, state value: DefinitionDetailState) -> some View {
        var rows: [AnyView] = []

        // KIND — always. The word, not the tree's colour dot: colour is invisible to VoiceOver and
        // unreliable for a colour-blind reader, and this is the row that says what the item *is*.
        rows.append(AnyView(
            PropertyTableRow(label: L.string("tasks_detail_property_kind")) {
                valueText(L.kindLabel(definition.kind.name))
            }
        ))
        // STATUS — always, and it is the [[Definition state]] light switch (Active / In review / Archived),
        // never a `WorkingState`: a Habit is never "done", it is switched on or off. Read-only text rather
        // than the Task row's `Menu` — the write is the tree's (#299).
        rows.append(AnyView(
            PropertyTableRow(label: L.string("tasks_detail_property_status")) {
                valueText(L.definitionStateLabel(definition.definitionState.name))
            }
        ))

        // REPEATS + NEXT DUE come from the ONE shared normalisation every platform reads
        // (`RecurrenceReading.recurrenceLineTokens`), crossed once — not re-derived per row. `nil` means the
        // item carries no rule at all, so both rows are simply absent.
        if let item = value.item, let tokens = RecurrenceReadingKt.recurrenceLineTokens(item: item) {
            rows.append(AnyView(
                PropertyTableRow(label: L.string("tasks_detail_property_repeats")) {
                    valueText(L.cadenceWithBound(tokens))
                }
            ))
            // The bare day ("Tomorrow", "3 days ago", "Series ended") — NOT `L.cursor`'s "Next: %@" wrapper,
            // which is the phrasing for an inline tree-row subtitle; under a row labelled NEXT DUE it would
            // read "NEXT DUE | Next: Tomorrow". A cursor pointing *backwards* is normal rather than corrupt:
            // a missed Habit's cursor sits where it stopped advancing.
            if let nextDue = L.cursorDay(tokens) {
                rows.append(AnyView(
                    PropertyTableRow(label: L.string("tasks_detail_property_next_due")) {
                        valueText(nextDue)
                    }
                ))
            }
        }

        // TODAY — always. The two halves of the reading are orthogonal and this row must not conflate them,
        // so *which* of the four things it says is decided in one Kotlin `when` (ADR-0053 decision 4); see
        // `BridgeKt.definitionTodayCell`. The whole row is announced through the read-only a11y wrapper —
        // deliberately not the Task STATUS row's, which ends in "Tap to change" and nothing here does.
        let todayReading = BridgeKt.definitionTodayCell(today: value.today)
        rows.append(AnyView(
            PropertyTableRow(label: L.string("tasks_detail_property_today")) { todayCell(todayReading) }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L.format("tasks_detail_today_row_a11y", L.string(todayReading.token)))
        ))

        // LABELS — only when there are any. The Task table keeps an empty LABELS row because that row holds
        // its "add label" field; this one is read-only, so an empty row would be a label column and a dash.
        if !definition.labels.isEmpty {
            rows.append(AnyView(
                PropertyTableRow(label: L.string("common_labels")) { labelsCell(definition.labels) }
            ))
        }

        return VStack(spacing: 0) {
            ForEach(rows.indices, id: \.self) { i in
                if i > 0 { Divider().overlay { colors.outlineVariant } }
                rows[i]
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(colors.outlineVariant, lineWidth: 1))
    }

    /// Today's cell. The [[Occurrence state]] reading renders as the same capsule chip the Calendar agenda
    /// uses (it is the same reading, in the same shared `common_status_*` vocabulary); the three **grid**
    /// arms render as a plain muted line, because they say something about the *schedule* rather than about
    /// how a firing went, and a status pill would dress them as one.
    @ViewBuilder
    private func todayCell(_ cell: DefinitionTodayCell) -> some View {
        if cell.isState {
            Text(L.string(cell.token))
                .font(.caption.weight(.medium))
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(cell.isDone ? colors.successContainer : colors.surfaceVariant, in: Capsule())
                .foregroundStyle(colors.onSurface)
        } else {
            Text(L.string(cell.token))
                .font(.subheadline)
                .foregroundStyle(colors.inkMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The LABELS cell: the labels as plain chips. No remove affordance and no add field — the Task row's
    /// chips carry an `xmark` because that row writes; this one reads.
    @ViewBuilder
    private func labelsCell(_ labels: [String]) -> some View {
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

    /// A plain read-only value cell — the shape every row on this table uses, since none of them edit.
    private func valueText(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(colors.onSurface)
            .fixedSize(horizontal: false, vertical: true)
    }
}
