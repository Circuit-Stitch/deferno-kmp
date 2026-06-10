import Deferno
import SwiftUI

/// The Auth shell (pre-Account): paste a **personal access token**, validate it, enter the app (#15,
/// ADR-0023). The SwiftUI twin of the Compose `SignInScreen` — a thin renderer of `SignInComponent`:
/// a masked, reveal-toggleable token field, the two gentle errors, and a submit button gated on
/// `canSubmit`. There is **no success state** — establishing the Account flips the Active Account and
/// `RootView` swaps this surface for the Main shell. The token is never logged (ADR-0009).
struct SignInView: View {
    let component: SignInComponent
    @StateObject private var state: StateFlowObserver<SignInState>
    @Environment(\.defernoColors) private var colors
    @State private var revealed = false

    init(component: SignInComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.signInStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
        ZStack {
            colors.background.ignoresSafeArea()
            VStack(spacing: 16) {
                Brandmark(height: 56)
                Text("Deferno")
                    .font(.largeTitle.weight(.semibold))
                    .foregroundStyle(colors.onSurface)
                Text("Sign in with a personal access token")
                    .font(.subheadline)
                    .foregroundStyle(colors.inkMuted)
                    .multilineTextAlignment(.center)

                tokenField(value)
                if let error = value.error {
                    errorText(error)
                }
                signInButton(value)

                Text("Create a token in Deferno on the web: Settings → Tokens.")
                    .font(.footnote)
                    .foregroundStyle(colors.inkMuted)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: 420)
            .padding(24)
        }
    }

    private var tokenBinding: Binding<String> {
        Binding(get: { state.value.token }, set: { component.onTokenChange(token: $0) })
    }

    @ViewBuilder
    private func tokenField(_ value: SignInState) -> some View {
        HStack(spacing: 8) {
            Group {
                if revealed {
                    TextField("Personal access token", text: tokenBinding)
                } else {
                    SecureField("Personal access token", text: tokenBinding)
                }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
            .disabled(value.isValidating)
            .foregroundStyle(colors.onSurface)

            Button(revealed ? "Hide" : "Show") { revealed.toggle() }
                .font(.footnote)
                .accessibilityLabel(revealed ? "Hide token" : "Show token")
        }
        .padding(12)
        .frame(minHeight: Layout.minTouchTarget)
        .background(colors.surfaceCard, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(value.error != nil ? colors.error : colors.outline)
        )
    }

    @ViewBuilder
    private func errorText(_ error: SignInError) -> some View {
        Text(error === SignInError.invalidtoken
             ? "That token isn't valid. Check it and try again."
             : "Couldn't reach Deferno. Check your connection and try again.")
            .font(.footnote)
            .foregroundStyle(colors.error)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func signInButton(_ value: SignInState) -> some View {
        Button { component.onSubmit() } label: {
            Text(value.isValidating ? "Signing in…" : "Sign in")
                .frame(maxWidth: .infinity)
                .frame(minHeight: Layout.minTouchTarget)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!value.canSubmit)
    }
}
