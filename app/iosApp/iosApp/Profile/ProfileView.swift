import Deferno
import SwiftUI

/// The Profile Destination (#70) — the Active Account's identity card + the Account controls. A thin
/// renderer of `ProfileComponent`: it resolves `/auth/me` into a sealed `ProfileState` and exposes the
/// Active Account + Sign out. Sign out is a host concern (it crosses the Account-isolation boundary),
/// so the button just confirms and forwards the intent; the shell secure-wipes and returns to Auth.
struct ProfileView: View {
    let component: ProfileComponent
    @StateObject private var state: ProfileStateObserver
    @Environment(\.defernoColors) private var colors
    @State private var confirmSignOut = false

    init(component: ProfileComponent) {
        self.component = component
        _state = StateObject(wrappedValue: ProfileStateObserver(component.state))
    }

    var body: some View {
        // No NavigationStack/title here: the single adaptive shell bar (MainShellView) titles "Profile".
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                identityCard
                Divider().background(colors.outlineVariant)
                accountSection
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
        }
        .background(colors.background)
        .alert("Sign out of \(component.account.label)?", isPresented: $confirmSignOut) {
            Button("Sign out", role: .destructive) { component.onSignOut() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your tasks stay safe in Deferno. You can sign back in with your token anytime.")
        }
    }

    @ViewBuilder
    private var identityCard: some View {
        let value = state.value
        if ShellBridgeKt.profileIsLoading(state: value) {
            HStack(spacing: 12) {
                ProgressView()
                Text("Loading your profile…").foregroundStyle(colors.inkMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else if let user = ShellBridgeKt.profileUser(state: value) {
            signedInCard(user)
        } else if ShellBridgeKt.profileIsReauthRequired(state: value) {
            inlineNotice(title: "Session expired",
                         message: "Your token needs a refresh. Sign in again to continue.",
                         action: "Sign in again")
        } else {
            inlineNotice(title: "Can't reach Deferno",
                         message: "Check your connection and try again — nothing was lost.",
                         action: "Retry")
        }
    }

    private func signedInCard(_ user: User) -> some View {
        HStack(alignment: .top, spacing: 16) {
            initialsAvatar(for: user.displayName.isEmpty ? user.username : user.displayName)
            VStack(alignment: .leading, spacing: 6) {
                Text(user.displayName.isEmpty ? user.username : user.displayName)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(colors.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Text("@\(user.username)")
                    .font(.subheadline.monospaced())
                    .foregroundStyle(colors.inkMuted)
                HStack(spacing: 8) {
                    chip(user.orgSlug.isEmpty ? "Personal" : user.orgSlug, background: colors.secondaryContainer)
                    if user.isAdmin {
                        chip("Admin", background: colors.tertiaryContainer)
                    }
                }
            }
            Spacer(minLength: 0)
        }
    }

    private func inlineNotice(title: String, message: String, action: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).foregroundStyle(colors.onSurface)
            Text(message).font(.subheadline).foregroundStyle(colors.inkMuted)
            Button(action) { component.onRetry() }
                .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(colors.surfaceCard, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var accountSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Account").font(.headline).foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
            labeledRow("Active account", component.account.label)
            labeledRow("Credential", "Personal access token")
            Text("Stored only on this device.")
                .font(.caption).foregroundStyle(colors.inkMuted)
            Button(role: .destructive) { confirmSignOut = true } label: {
                Text("Sign out").frame(maxWidth: .infinity).frame(minHeight: Layout.minTouchTarget)
            }
            .buttonStyle(.bordered)
            .tint(colors.error)
        }
    }

    private func labeledRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(colors.inkMuted)
            Spacer()
            Text(value).foregroundStyle(colors.onSurface)
        }
        .font(.subheadline)
    }

    private func initialsAvatar(for name: String) -> some View {
        let initials = name.split(separator: " ").prefix(2).compactMap { $0.first }.map(String.init).joined().uppercased()
        return Text(initials.isEmpty ? "?" : initials)
            .font(.title3.weight(.semibold))
            .foregroundStyle(colors.onSurface)
            .frame(width: 64, height: 64)
            .background(colors.primaryContainer, in: Circle())
    }

    private func chip(_ text: String, background: Color) -> some View {
        Text(text)
            .font(.caption.weight(.medium))
            .foregroundStyle(colors.onSurface)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(background, in: Capsule())
    }
}
