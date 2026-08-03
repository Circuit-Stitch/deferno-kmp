import Deferno
import Foundation
import SwiftUI

/// The global Search overlay (#73, #311) — a thin renderer of `SearchComponent`. Offline + one-shot: the
/// query (≥2 chars, or the "has attachment" filter) plus optional status / tag / date-range / attachment /
/// sort filters drive a local read; tapping a result opens it in the Tasks Destination (the shell routes +
/// dismisses). The date range is two independent native pickers, each edge separately optional.
struct SearchView: View {
    let component: SearchComponent
    @StateObject private var state: StateFlowObserver<SearchState>
    @Environment(\.defernoColors) private var colors
    @State private var newTag = ""

    init(component: SearchComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            HStack {
                Text(L.string("common_search")).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button(L.string("common_close")) { component.onDismiss() }
            }
            .padding(.horizontal, Layout.gutter)
            .frame(minHeight: 56)

            // Search is online-only and presented above the chrome, so it can't rely on the shell banner —
            // a 401'd search shows the re-auth prompt here too (#297). "Sign in again" closes the overlay.
            if value.sessionExpired { SessionExpiredBanner { component.onDismiss() } }

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    queryField
                    statusFilter(value)
                    tagsFilter(value)
                    dateRangeFilter(value)
                    attachmentFilter(value)
                    sortFilter(value)
                    Divider().background(colors.outlineVariant)
                    results(value)
                }
                .padding(.horizontal, Layout.gutter)
                .padding(.vertical, 12)
            }
        }
        .background(colors.background)
    }

    private var queryField: some View {
        HStack(spacing: 8) {
            TextField(L.string("search_field_label"), text: Binding(get: { state.value.query }, set: { component.onQueryChanged(query: $0) }))
                .textFieldStyle(.roundedBorder)
                .submitLabel(.search)
                .onSubmit { component.onSubmit() }
            // A visible submit affordance — the keyboard's Search key alone is undiscoverable (#73).
            Button(L.string("common_search")) { component.onSubmit() }
                .buttonStyle(.borderedProminent)
                .tint(colors.primary)
                .disabled(!state.value.canSearch || state.value.isSearching)
        }
    }

    private func statusFilter(_ value: SearchState) -> some View {
        filterSection(L.string("search_section_status")) {
            wrap(WorkingState.ordered.map { ("\($0.label)", $0) }) { status in
                chip(status.0, selected: ShellBridgeKt.searchHasStatus(state: value, status: status.1)) {
                    component.onStatusToggled(status: status.1)
                }
            }
        }
    }

    private func tagsFilter(_ value: SearchState) -> some View {
        filterSection(L.string("search_filter_tags")) {
            HStack {
                TextField(L.string("search_add_tag_label"), text: $newTag).textFieldStyle(.roundedBorder)
                Button(L.string("common_add")) {
                    let trimmed = newTag.trimmingCharacters(in: .whitespaces)
                    if !trimmed.isEmpty { component.onLabelToggled(label: trimmed); newTag = "" }
                }
                .disabled(newTag.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            let labels = ShellBridgeKt.searchLabels(state: value)
            if !labels.isEmpty {
                wrap(labels.map { ($0, $0) }) { label in
                    chip(label.0, selected: true) { component.onLabelToggled(label: label.1) }
                }
            }
        }
    }

    /// The date-range filter: two independently-optional edges, each a native picker.
    ///
    /// Both edges are nullable — an open-ended range is the normal case — so each row stays on "—" + Add
    /// until asked for, and Clear reopens it. The two `setSearch*Date` seams each move **one** edge, reading
    /// the other live off the component: the ISO text fields these replaced shared one `.onChange` that
    /// re-parsed *both* strings on every keystroke, so a half-typed "to" silently wiped an applied "from".
    private func dateRangeFilter(_ value: SearchState) -> some View {
        filterSection(L.string("search_filter_date_range")) {
            VStack(spacing: 4) {
                OptionalDatePickerRow(
                    label: L.string("common_from"),
                    accessibilityLabel: L.string("common_from"),
                    epochSeconds: ShellBridgeKt.searchFromEpochSeconds(state: value),
                    onPick: { ShellBridgeKt.setSearchFromDate(component: component, epochSeconds: $0) },
                    onAdd: {
                        ShellBridgeKt.setSearchFromDate(component: component, epochSeconds: Date().timeIntervalSince1970)
                    },
                    onClear: { ShellBridgeKt.setSearchFromDate(component: component, epochSeconds: -1) }
                )
                OptionalDatePickerRow(
                    label: L.string("common_to"),
                    accessibilityLabel: L.string("common_to"),
                    epochSeconds: ShellBridgeKt.searchToEpochSeconds(state: value),
                    onPick: { ShellBridgeKt.setSearchToDate(component: component, epochSeconds: $0) },
                    onAdd: {
                        ShellBridgeKt.setSearchToDate(component: component, epochSeconds: Date().timeIntervalSince1970)
                    },
                    onClear: { ShellBridgeKt.setSearchToDate(component: component, epochSeconds: -1) }
                )
            }
        }
    }

    // The "has attachment" filter (#311) — a single toggle chip. A bare attachment filter searches with no
    // text, so it runs on its own (the Settings → Storage "biggest attachments" deep-link relies on it).
    private func attachmentFilter(_ value: SearchState) -> some View {
        filterSection(L.string("tasks_detail_section_attachments")) {
            chip(L.string("search_filter_has_attachment"), selected: value.hasAttachment) { component.onHasAttachmentToggled() }
        }
    }

    private func sortFilter(_ value: SearchState) -> some View {
        let current = ShellBridgeKt.searchCurrentSortKey(state: value)
        return filterSection(L.string("search_filter_sort")) {
            wrap(ShellBridgeKt.searchSortValues().map { (searchSortLabel(ShellBridgeKt.searchSortKey(sort: $0)), $0) }) { sort in
                chip(sort.0, selected: ShellBridgeKt.searchSortKey(sort: sort.1) == current) {
                    ShellBridgeKt.setSearchSort(component: component, sort: sort.1)
                }
            }
        }
    }

    @ViewBuilder
    private func results(_ value: SearchState) -> some View {
        if !value.results.isEmpty {
            VStack(spacing: 0) {
                // `results` is now a list of `SearchHit` (a lightweight kind/title/ref projection), not
                // full `Task`s (the search pull no longer hydrates the whole record). Render the hit
                // directly and forward it to `onResultClicked` (the component resolves it to the Tasks
                // Destination). Mirrors app/iosApp's SearchHitRow.
                ForEach(value.results, id: \.id) { hit in
                    SearchHitRow(hit: hit) { component.onResultClicked(hit: hit) }
                    Divider().background(colors.outlineVariant)
                }
            }
        } else if value.isSearching {
            LoadingStrip(label: L.string("search_searching"))
        } else if value.sessionExpired {
            // The banner above already explains the expired session — don't double up (#297).
            EmptyView()
        } else if value.hasSearched {
            EmptyStateView(title: L.string("search_no_matches_title"),
                           message: L.string("search_no_matches_body"))
        } else {
            EmptyStateView(title: L.string("search_initial_title_desktop"),
                           message: L.string("search_initial_body_desktop"))
        }
    }

    // MARK: Atoms

    private func filterSection<Content: View>(_ heading: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(heading).font(.subheadline.weight(.semibold)).foregroundStyle(colors.inkMuted)
            content()
        }
    }

    private func chip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        SelectableChip(label: label, selected: selected, prominence: .low, compact: true, action: action)
    }

    /// A simple flowing wrap of chips (SwiftUI has no native FlowLayout pre-iOS 16 helper here).
    private func wrap<T>(_ items: [(String, T)], @ViewBuilder chip: @escaping ((String, T)) -> some View) -> some View {
        let columns = [GridItem(.adaptive(minimum: 90), spacing: 8)]
        return LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
            ForEach(items.indices, id: \.self) { i in chip(items[i]) }
        }
    }
}

/// A `SearchSort` enum name → its localized chip label.
///
/// **The `default:` arm below renders the RAW enum name**, so a sort added to `core/data`'s `SearchSort` and
/// not added here silently ships a chip reading "PriorityRank" — which is exactly what happened when the
/// ranked sort landed (#375). File-level and `internal` rather than a method on `SearchView` so
/// `macosAppTests` can walk every `SearchSort` entry and fail on the next one that has no arm; a `switch`
/// over a String key cannot be made exhaustive by the compiler, so the test is the only thing that can.
func searchSortLabel(_ key: String) -> String {
    switch key {
    case "Relevance": return L.string("search_sort_best_match")
    case "TitleAsc": return L.string("search_sort_title_asc")
    case "DeadlineAsc": return L.string("search_sort_soonest_due")
    case "AttachmentSizeDesc": return L.string("search_sort_biggest_attachments")
    // The canonical ranked order (#375): urgency bucket, then the soonest of the soft target date / hard
    // deadline, then the deadline, then age. NOT a deadline sort — "DeadlineAsc" above is that one.
    case "PriorityRank": return L.string("search_sort_priority")
    default: return key
    }
}

/// Formats an attachment-size rollup as "N files · 1.2 MB" for a search hit (#311); empty when none.
func attachmentSummary(count: Int32, totalSize: Int64) -> String? {
    guard count > 0 else { return nil }
    let files = L.plural("search_file_count", Int(count))
    let size = ByteCountFormatter.string(fromByteCount: totalSize, countStyle: .file)
    return "\(files) · \(size)"
}

/// A single global-search result. `SearchHit` is a lightweight, kind-agnostic projection (title · kind ·
/// optional human `ref`), so the row renders it directly rather than a full `Task` (the search pull no
/// longer hydrates the whole record). A terminal hit (Done/Dropped/…) is de-emphasized, mirroring the
/// Item-tree row and app/iosApp's SearchHitRow.
private struct SearchHitRow: View {
    let hit: SearchHit
    let onTap: () -> Void
    @Environment(\.defernoColors) private var colors

    /// "TASK  ·  ACME-12" — the calm mono meta line Compose's `SearchResultRow` renders. Search mixes
    /// all four kinds in one flat list with no tree to imply them, so the kind marker is spelled out
    /// rather than left to the dot's colour (#393); the human `ref` follows when the hit carries one.
    private var meta: String {
        [kindDisplayLabel(hit.kind), hit.ref].compactMap { $0 }.joined(separator: "  ·  ")
    }

    /// The row `.ignore`s its children, so this one phrase is the whole row to VoiceOver: title, then
    /// the kind as the spoken lowercase noun (never the all-caps `meta` text), then the blocked flag.
    private var a11yLabel: String {
        var parts = [hit.title, kindA11yLabel(hit.kind)]
        if hit.blocked { parts.append(L.string("common_blocked")) }
        return parts.joined(separator: ", ")
    }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                // The kind accent, matching the iOS twin and the Item-tree row (this row had no kind
                // cue at all — neither visual nor spoken, #393).
                KindDot(color: kindColor(hit.kind, colors))
                VStack(alignment: .leading, spacing: 2) {
                    Text(hit.title)
                        .font(.headline)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Text(meta)
                        .font(.caption.monospaced())
                        .foregroundStyle(colors.inkMuted)
                    // Attachment rollup (#311) — visible so the "biggest attachments" sort is legible.
                    if let summary = attachmentSummary(count: hit.attachmentCount, totalSize: hit.attachmentTotalSize) {
                        Text(summary).font(.caption).foregroundStyle(colors.inkMuted)
                    }
                }
                Spacer(minLength: 12)
                // Blocked search hits are still returned, just flagged so they aren't mistaken
                // for actionable — the tree's blocked marking (#290/#292).
                if hit.blocked {
                    DependencyBadge(text: L.string("common_blocked"), tone: .neutral, semanticLabel: L.string("common_blocked"))
                }
                DefernoIcon.chevronRight.image(size: 16).foregroundStyle(colors.inkMuted)
            }
            .frame(minHeight: Layout.rowMinHeight)
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, Layout.rowVerticalPadding)
            .contentShape(Rectangle())
            .opacity(hit.isTerminal ? 0.5 : 1.0)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(a11yLabel)
        .accessibilityHint(L.string("tasks_menu_open"))
    }
}
