import Deferno
import SwiftUI

/// The Task detail pane (#51). Thin renderer of `TaskDetailComponent`: observes the hydrating row and
/// forwards the close / show-breakdown / add-to-plan / set-working-state intents. The component
/// hydrates on creation (summary → full, #22); this View just reflects its state.
struct TaskDetailView: View {
    let component: TaskDetailComponent
    @StateObject private var state: StateFlowObserver<TaskDetailState>

    init(component: TaskDetailComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(BridgeKt.taskDetailStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            // In single-pane the leading control returns to the list, so it reads as "Back".
            PaneHeader(title: value.task?.title ?? "Task", onBack: { component.onCloseClicked() })
            if value.isHydrating {
                LoadingStrip(label: "Loading details…")
            }
            if value.task == nil && !value.isHydrating {
                EmptyStateView(
                    title: "Task not found",
                    message: "This task may have been removed. Head back to your list."
                )
            } else if let task = value.task {
                taskBody(for: task, isHydrating: value.isHydrating)
            } else {
                Spacer() // brief hydrating gap before the row is observed; the strip above shows it
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }

    @ViewBuilder
    private func taskBody(for task: Task, isHydrating: Bool) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let ref = task.ref {
                    Text(ref)
                        .font(.footnote.monospaced())
                        .foregroundStyle(.secondary)
                }

                WorkingStateEditorView(current: task.workingState) { component.onSetWorkingState(target: $0) }

                if let description = task.description_, !description.isEmpty {
                    Text(description).font(.body)
                } else if !isHydrating {
                    Text("No description yet.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                Button { component.onAddToPlanClicked() } label: {
                    Text("Add to today's plan").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(minHeight: Layout.minTouchTarget)

                if !task.children.isEmpty {
                    let count = task.children.count
                    Button { component.onShowTreeClicked() } label: {
                        Text(count == 1 ? "Show its 1 step" : "Show its \(count) steps")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .frame(minHeight: Layout.minTouchTarget)
                }
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
        }
    }
}

/// The interactive working-state control (#73): a selectable chip per state with the current one
/// highlighted, so the user can move the Task across all five states. Tapping forwards the intent;
/// the component issues the offline-first Command and the badge flips optimistically. Plain labels,
/// large touch targets, and a self-describing VoiceOver label per chip (design-principles.md).
private struct WorkingStateEditorView: View {
    let current: WorkingState
    let onSet: (WorkingState) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Working state")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(WorkingState.ordered, id: \.self) { state in
                        chip(state)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func chip(_ state: WorkingState) -> some View {
        let selected = state === current
        Button { onSet(state) } label: {
            Text(state.label)
                .font(.subheadline)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    selected ? Color.accentColor : Color(.secondarySystemFill),
                    in: Capsule()
                )
                .foregroundStyle(selected ? Color.white : Color.primary)
        }
        .frame(minHeight: Layout.minTouchTarget)
        .accessibilityLabel(selected ? "\(state.label), current working state" : "Set to \(state.label)")
    }
}
