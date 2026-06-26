import Deferno
import SwiftUI

/// The global Search overlay (#73) — a thin renderer of `SearchComponent`. Online-only, one-shot: the
/// query (≥2 chars, gated in the component) plus optional status / tag / date-range / sort filters drive
/// a pull; tapping a result opens it in the Tasks Destination (the shell routes + dismisses). No native
/// date picker — ISO `YYYY-MM-DD` text fields, identical across platforms by design.
struct SearchView: View {
    let component: SearchComponent
    @StateObject private var state: StateFlowObserver<SearchState>
    @Environment(\.defernoColors) private var colors
    @State private var newTag = ""
    @State private var fromText = ""
    @State private var toText = ""

    init(component: SearchComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            HStack {
                Text("Search").font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Close") { component.onDismiss() }
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
                    dateRangeFilter
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
            TextField("Search tasks", text: Binding(get: { state.value.query }, set: { component.onQueryChanged(query: $0) }))
                .textFieldStyle(.roundedBorder)
                .submitLabel(.search)
                .onSubmit { component.onSubmit() }
            // A visible submit affordance — the keyboard's Search key alone is undiscoverable (#73).
            Button("Search") { component.onSubmit() }
                .buttonStyle(.borderedProminent)
                .tint(colors.primary)
                .disabled(!state.value.canSearch || state.value.isSearching)
        }
    }

    private func statusFilter(_ value: SearchState) -> some View {
        filterSection("Status") {
            wrap(WorkingState.ordered.map { ("\($0.label)", $0) }) { status in
                chip(status.0, selected: ShellBridgeKt.searchHasStatus(state: value, status: status.1)) {
                    component.onStatusToggled(status: status.1)
                }
            }
        }
    }

    private func tagsFilter(_ value: SearchState) -> some View {
        filterSection("Tags") {
            HStack {
                TextField("Add a tag", text: $newTag).textFieldStyle(.roundedBorder)
                Button("Add") {
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

    private var dateRangeFilter: some View {
        filterSection("Date range") {
            HStack(spacing: 8) {
                dateField("From (YYYY-MM-DD)", text: $fromText)
                dateField("To (YYYY-MM-DD)", text: $toText)
            }
        }
    }

    private func dateField(_ placeholder: String, text: Binding<String>) -> some View {
        TextField(placeholder, text: text)
            .textFieldStyle(.roundedBorder)
            .autocorrectionDisabled(true)
            .onChange(of: text.wrappedValue) { _ in
                component.onDateRangeChanged(from: ShellBridgeKt.parseLocalDate(text: fromText),
                                             to: ShellBridgeKt.parseLocalDate(text: toText))
            }
    }

    private func sortFilter(_ value: SearchState) -> some View {
        let current = ShellBridgeKt.searchCurrentSortKey(state: value)
        return filterSection("Sort") {
            wrap(ShellBridgeKt.searchSortValues().map { (sortLabel(ShellBridgeKt.searchSortKey(sort: $0)), $0) }) { sort in
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
            LoadingStrip(label: "Searching…")
        } else if value.sessionExpired {
            // The banner above already explains the expired session — don't also show a "couldn't reach the
            // server" state (#297).
            EmptyView()
        } else if value.searchFailed {
            // A failed pull (offline / server error) is NOT "no matches" — say so (#73 follow-up).
            EmptyStateView(title: "Search is unavailable",
                           message: "Something went wrong reaching the server. Check your connection and try again.")
        } else if value.hasSearched {
            EmptyStateView(title: "No matches",
                           message: "Nothing matched your search. Try a different word or fewer filters.")
        } else {
            EmptyStateView(title: "Search your tasks",
                           message: "Type at least two characters to find tasks by title or description.")
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

    private func sortLabel(_ key: String) -> String {
        switch key {
        case "Relevance": return "Best match"
        case "TitleAsc": return "Title (A–Z)"
        case "DeadlineAsc": return "Soonest due"
        default: return key
        }
    }
}

/// A single global-search result. `SearchHit` is a lightweight, kind-agnostic projection (title · kind ·
/// optional human `ref`), so the row renders it directly rather than a full `Task` (the search pull no
/// longer hydrates the whole record). A terminal hit (Done/Dropped/…) is de-emphasized, mirroring the
/// Item-tree row and app/iosApp's SearchHitRow.
private struct SearchHitRow: View {
    let hit: SearchHit
    let onTap: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(hit.title)
                        .font(.headline)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    if let ref = hit.ref {
                        Text(ref)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 12)
                Text("›").font(.title3).foregroundStyle(.secondary)
            }
            .frame(minHeight: Layout.rowMinHeight)
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, Layout.rowVerticalPadding)
            .contentShape(Rectangle())
            .opacity(hit.isTerminal ? 0.5 : 1.0)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(hit.title)
        .accessibilityHint("Open")
    }
}
