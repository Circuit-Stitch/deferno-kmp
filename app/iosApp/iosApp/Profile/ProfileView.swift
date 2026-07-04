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
        .alert(L.format("profile_sign_out_confirm_title", component.account.label), isPresented: $confirmSignOut) {
            Button(L.string("common_sign_out"), role: .destructive) { component.onSignOut() }
            Button(L.string("common_cancel"), role: .cancel) {}
        } message: {
            Text(L.string("profile_sign_out_reassure_body"))
        }
    }

    @ViewBuilder
    private var identityCard: some View {
        let value = state.value
        if ShellBridgeKt.profileIsLoading(state: value) {
            HStack(spacing: 12) {
                ProgressView()
                Text(L.string("profile_loading")).foregroundStyle(colors.inkMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else if let user = ShellBridgeKt.profileUser(state: value) {
            signedInCard(user)
        } else if ShellBridgeKt.profileIsReauthRequired(state: value) {
            inlineNotice(title: L.string("auth_session_expired_title"),
                         message: L.string("profile_token_refresh_body"),
                         action: L.string("common_sign_in_again"))
        } else {
            inlineNotice(title: L.string("auth_unavailable_title"),
                         message: L.string("profile_unavailable_reassure_body"),
                         action: L.string("common_retry"))
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
                Text(L.format("common_username_handle", user.username))
                    .font(.subheadline.monospaced())
                    .foregroundStyle(colors.inkMuted)
                HStack(spacing: 8) {
                    chip(user.orgSlug.isEmpty ? L.string("profile_org_personal_chip") : user.orgSlug, background: colors.secondaryContainer)
                    if user.isAdmin {
                        chip(L.string("profile_admin_chip"), background: colors.tertiaryContainer)
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
            Text(L.string("common_account")).font(.headline).foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
            labeledRow(L.string("profile_account_active_label"), component.account.label)
            // Time zone moved into Profile (#72) — offline-first, from the local settings cache.
            labeledRow(L.string("profile_account_time_zone_label"), ShellBridgeKt.profileTimeZone(component: component) ?? L.string("profile_account_time_zone_default"))
            labeledRow(L.string("profile_account_credential_label"), L.string("auth_token_field_label"))
            Text(L.string("profile_account_stored_on_device"))
                .font(.caption).foregroundStyle(colors.inkMuted)
            Button(role: .destructive) { confirmSignOut = true } label: {
                Text(L.string("common_sign_out")).frame(maxWidth: .infinity).frame(minHeight: Layout.minTouchTarget)
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
