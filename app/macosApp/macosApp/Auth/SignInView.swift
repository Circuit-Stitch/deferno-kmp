import Deferno
import SwiftUI

/// The Auth shell (pre-Account) sign-in surface (#15, ADR-0012/0026): the SwiftUI twin of the Compose
/// `SignInScreen` — a thin renderer of `SignInComponent`. The primary action is **Sign in**, the
/// system-browser OAuth flow, so there is **no in-app credential field** by default (password + MFA +
/// SSO happen in the browser). In debug builds a "Use a token instead" affordance reveals the
/// paste-PAT fallback (ADR-0023): a masked field with a reveal toggle, gated on `canSubmitToken`; the
/// token is never logged (ADR-0009). There is **no success state** — establishing the Account flips
/// the Active Account and `RootView` swaps this surface for Main.
struct SignInView: View {
    let component: SignInComponent
    @StateObject private var state: StateFlowObserver<SignInState>
    @Environment(\.defernoColors) private var colors
    @State private var revealed = false

    // The dev paste-PAT affordance ships in debug builds only — the iOS twin of Android's
    // `showDeveloperOptions = BuildConfig.DEBUG`.
    #if DEBUG
    private let showDeveloperOptions = true
    #else
    private let showDeveloperOptions = false
    #endif

    init(component: SignInComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
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
                Text("Sign in to your account")
                    .font(.subheadline)
                    .foregroundStyle(colors.inkMuted)
                    .multilineTextAlignment(.center)

                browserSignInButton(value)
                Text("A secure browser window opens to finish signing in.")
                    .font(.footnote)
                    .foregroundStyle(colors.inkMuted)
                    .multilineTextAlignment(.center)

                // The external browser gives no close event (ADR-0026), so a started-then-abandoned
                // sign-in can't auto-cancel — offer an explicit restart while the leg is in flight.
                if value.canRetryBrowser {
                    Text("Need to try again?")
                        .font(.footnote)
                        .foregroundStyle(colors.inkMuted)
                    Button("Sign in") { component.onRetry() }
                        .font(.footnote)
                }

                // Browser-path error (the paste path shows its error inline under the field instead).
                if let error = value.error, !value.showTokenEntry {
                    errorText(error)
                }

                if showDeveloperOptions {
                    if value.showTokenEntry {
                        tokenEntry(value)
                    } else {
                        Button("Use a token instead") { component.onUseTokenInstead() }
                            .font(.footnote)
                    }
                }
            }
            .frame(maxWidth: 420)
            .padding(24)
        }
    }

    private var tokenBinding: Binding<String> {
        Binding(get: { state.value.token }, set: { component.onTokenChange(token: $0) })
    }

    @ViewBuilder
    private func browserSignInButton(_ value: SignInState) -> some View {
        Button { component.onSignInClick() } label: {
            Text(value.isBusy ? "Signing in…" : "Sign in")
                .frame(maxWidth: .infinity)
                .frame(minHeight: Layout.minTouchTarget)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!value.canStartBrowser)
    }

    /// The developer paste-PAT fallback (ADR-0023): a masked token field + reveal toggle + submit.
    @ViewBuilder
    private func tokenEntry(_ value: SignInState) -> some View {
        tokenField(value)
        if let error = value.error {
            errorText(error)
        }
        tokenSubmitButton(value)
        Text("Create a token in Deferno on the web: Settings → Tokens.")
            .font(.footnote)
            .foregroundStyle(colors.inkMuted)
            .multilineTextAlignment(.center)
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
            .autocorrectionDisabled(true)
            .disabled(value.isBusy)
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
    private func tokenSubmitButton(_ value: SignInState) -> some View {
        Button { component.onSubmit() } label: {
            Text(value.isBusy ? "Signing in…" : "Sign in with token")
                .frame(maxWidth: .infinity)
                .frame(minHeight: Layout.minTouchTarget)
        }
        .buttonStyle(.bordered)
        .disabled(!value.canSubmitToken)
    }

    @ViewBuilder
    private func errorText(_ error: SignInError) -> some View {
        Text(error == SignInError.invalidToken
             ? "That token isn't valid. Check it and try again."
             : "Couldn't reach Deferno. Check your connection and try again.")
            .font(.footnote)
            .foregroundStyle(colors.error)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
