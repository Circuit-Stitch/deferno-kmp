package com.circuitstitch.deferno.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.DevAccounts
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.core.di.createAppComponent
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.PlatformContext
import com.circuitstitch.deferno.desktop.chrome.AwtChromeBackend
import com.circuitstitch.deferno.desktop.chrome.ChromeBackend
import com.circuitstitch.deferno.desktop.chrome.PlanBadge
import com.circuitstitch.deferno.desktop.chrome.activeMainShell
import com.circuitstitch.deferno.desktop.chrome.installAppMenuHandlers
import com.circuitstitch.deferno.desktop.chrome.openPreferences
import com.circuitstitch.deferno.desktop.shell.RootShell
import com.circuitstitch.deferno.desktop.update.ConveyorUpdateBackend
import com.circuitstitch.deferno.desktop.update.UpdateAction
import com.circuitstitch.deferno.desktop.update.UpdateManager
import com.circuitstitch.deferno.desktop.update.UpdateState
import com.circuitstitch.deferno.desktop.update.presentUpdate
import com.circuitstitch.deferno.shell.AccountComponentSession
import com.circuitstitch.deferno.shell.DefaultRootComponent
import com.circuitstitch.deferno.shell.MainShellComponent
import com.circuitstitch.deferno.shell.OverlayRoute
import com.circuitstitch.deferno.shell.RootComponent
import com.circuitstitch.deferno.shell.ui.label
import dev.hydraulic.conveyor.control.SoftwareUpdateController
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.decodeToSvgPainter
import software.amazon.app.kmplogger.LogLevel
import software.amazon.app.kmplogger.Logger

/**
 * Desktop (JVM) Compose entry point — the desktop counterpart of Android's [DefernoApplication] +
 * `MainActivity` (ADR-0017). On launch it builds the **process-global** [AppComponent] from the real
 * JVM DI graph (ADR-0014), hydrates the persisted [[Account]] roster, idempotently seeds the dev-PAT
 * Account(s), and renders the **shared** [RootComponent] (Auth ↔ Main → Destination graph, ADR-0013)
 * with the desktop-native Views. There is no boolean auth gate: the shell keys off the Active Account.
 *
 * Desktop affordances ("its best self", ADR-0007: desktop-class input is a v1 criterion): an **in-app,
 * themed menu bar** ([DefernoMenuBar]: File → Quit; View → switch Destination / New / Search / Refresh;
 * Account → switch Account / Sign out) with keyboard shortcuts (Ctrl+1..5 destinations, Ctrl+N New,
 * Ctrl+F Search, Ctrl+R refresh, Ctrl+Q quit, **Esc** dismisses the overlay / foreground pane), a
 * persistent rail that expands to a drawer by width, and a window title that tracks the active
 * Destination. The native AWT menu bar is avoided (it can't follow the Compose dark theme), and the
 * default AWT window icon is blanked out.
 *
 * **Runtime requirements** (ADR-0017): a configured dev-PAT (`local.properties` →
 * [DesktopDevConfig], parsed by the shared [DevAccounts]), **network** to staging, and an **OS keychain**
 * (`DesktopSecretVault` over libsecret/Keychain/Credential Store) — a headless host with no Secret
 * Service surfaces a `SecureStorageException` during roster load / dev seeding (isolated to [appScope]
 * below, so the window still opens into the Auth shell). The per-Account DB is file-backed SQLite with
 * **no SQLCipher** yet (OS disk protection; a tracked follow-up).
 */
fun main() {
    // NOTE: interactive window resize briefly shows a white bleed in the newly-grown area before
    // Compose repaints it — a known upstream Skiko/Compose-Desktop artifact on Linux (the GPU render
    // surface lags the resize). AWT background colouring and software rendering were both tried and
    // didn't resolve it, so we stay on the default (GPU) renderer; revisit when Skiko/JBR Wayland
    // resize handling improves.

    // Name the app "Deferno" in the macOS menu bar (so the app menu + its About/Quit items read
    // "Deferno", not the "MainKt" main-class name) and anywhere AWT surfaces the application name.
    // Must be set before AWT initializes — i.e. before the first Desktop/Taskbar/Window touch — so it
    // lands here at the very top of main(). A packaged build gets the name from its bundle instead;
    // this only governs the unpackaged dev `run`. Harmless (just an unread property) off macOS. (#117)
    System.setProperty("apple.awt.application.name", "Deferno")

    val timeZone = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(timeZone)
    // Debug dev builds talk to staging (the dev-PAT target, ADR-0012); reused to derive the web-app
    // origin for the Settings deep-links. A real environment selector is a follow-up.
    val environment = DefernoEnvironment.Staging

    // Configure the shared logger ONCE per process, before the DI graph builds or anything logs
    // (amzn/kmp-logger). The `prefix` makes tags "Deferno: <Tag>"; the default strategy prints to
    // stdout. A shipped (Conveyor-packaged) build emits only WARN + ERROR; a dev `./gradlew run`
    // keeps DEBUG (see startupLogLevel). main() is a top-level function (no class receiver for the
    // `logger` extension), so log via Logger("Tag").
    Logger.configure(minLogLevel = startupLogLevel(), prefix = "Deferno")
    Logger("DesktopMain").i { "Deferno (desktop) starting — environment=$environment" }

    // The process-global AppScope DI graph (ADR-0008 G2 / ADR-0014): the cross-Account infrastructure
    // (network client + token provider, AccountManager + secure vault, registry) and a per-Account
    // file-backed SQLite location. Presentation (the RootComponent) is per-window below.
    val appComponent = createAppComponent(
        platform = PlatformContext(defernoDatabasesDir()),
        environment = environment,
    )

    // Startup work (roster hydration + dev seeding) runs off the EDT; the AccountManager's StateFlows
    // then drive the reactive shell. SupervisorJob so one failure (e.g. no OS keychain on a headless
    // host → SecureStorageException) is isolated — the window still opens into the Auth shell.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // In-app self-update (#103, ADR-0021): drives Conveyor's update engine from the desktop "Check for
    // updates" Help menu + a menu-bar badge. The backend is a no-op when the app isn't Conveyor-packaged
    // (dev `run`, where SoftwareUpdateController.getInstance() is null) and reports "package-manager
    // updates" on Linux — so checks/installs are safely inert except in a packaged Win/Mac build. The
    // generated DesktopBuildConfig.APP_VERSION (= ProjectConfig.APP_VERSION) is the version shown when
    // unpackaged; a real package reports the same value via the injected `app.version` property.
    val updateManager = UpdateManager(
        backend = ConveyorUpdateBackend.create(DesktopBuildConfig.APP_VERSION),
        scope = appScope,
    )

    // OS-native chrome (#117): the macOS app-menu items (About / Preferences / Quit) routed into
    // the app, plus a dock badge carrying today's remaining-plan count. Capability-guarded inside
    // the backend, so Linux/Windows — no native app menu, no text badge — are unaffected and keep
    // the in-app menu bar as the only chrome.
    val chrome: ChromeBackend = AwtChromeBackend
    val planBadge = PlanBadge(chrome, appScope)

    // Honour the OS dark/light preference. Compose Desktop's isSystemInDarkTheme() doesn't read the
    // Linux desktop setting, so on Linux we ask the XDG Desktop Portal directly (KDE/GNOME implement
    // it); null elsewhere → fall back to Compose's detector (which works on Windows/macOS). It feeds
    // themeMode.resolveDark(...) below so an explicit Light/Dark Appearance still wins (ADR-0017).
    val systemDark: Boolean? = systemPrefersDark()

    // One Decompose root per window, with its own Essenty lifecycle (the desktop counterpart of
    // Android's `retainedComponent`; the window state drives the lifecycle via LifecycleController).
    // Decompose requires its component tree to be built on the UI (main) thread — which on Compose
    // Desktop is the AWT event-dispatch thread, not the JVM `main` thread — so the root is constructed
    // via [runOnUiThread], and its collector runs on Dispatchers.Swing (the EDT) so navigation stays
    // on the UI thread.
    val lifecycle = LifecycleRegistry()
    val root: RootComponent = runOnUiThread {
        DefaultRootComponent(
            componentContext = DefaultComponentContext(lifecycle),
            accountManager = appComponent.accountManager,
            // The Profile Destination's /auth/me identity fetch (#70), Active-Account-aware (the bearer
            // plugin attaches the Active Account's PAT per request, ADR-0012).
            authRepository = appComponent.authRepository,
            // Build the per-Account data layer for an Active Account from the DI graph (ADR-0014).
            accountSession = { account ->
                val component = createAccountComponent(appComponent, account)
                // The dock badge follows the Active Account (#117): re-point it at this fresh
                // session's today-plan (a session is rebuilt per activation/switch, ADR-0014);
                // sign-out clears it via the activeAccount collector below.
                planBadge.trackPlan(component.planRepository.observePlan(today, timeZone.id))
                AccountComponentSession(component)
            },
            // The paste-PAT sign-in service (#15, ADR-0023) the Auth shell drives. Dev-PAT seeding still
            // runs at startup below, so a configured dev build skips the sign-in screen entirely.
            signInService = appComponent.signInService,
            today = today,
            timeZone = timeZone.id,
            // Settings → App Permissions is Android-only — there is no per-app OS settings screen on
            // desktop, so onOpenOsAppSettings stays the no-op default.
            // Settings → Data & Privacy has no client endpoint at v0.1 (ADR-0015): open the web app's
            // surface in the default browser (origin derived from the environment).
            onOpenDataExportImport = { browse(webAppUrl(environment, "settings/data")) },
            // Settings → Help & Feedback (#375): an in-app shell overlay now, submitting through this
            // AppScope service (the authed client carries the Active Account's PAT).
            feedbackRepository = appComponent.feedbackRepository,
            // Settings → Security & 2FA: open the Active Account's Zitadel console URL in the browser.
            onOpenConsoleUrl = { url -> browse(url) },
            // Dictation (#94, ADR-0018): the on-device whisper engine from the AppScope DI graph (the
            // selector over the JVM `whisper-jni` engine) + the device locale it recognizes (a non-English
            // locale reports unavailable rather than mis-transcribing). The New surface's mic drives this.
            speechToText = appComponent.speechToText,
            locale = Locale.getDefault().toLanguageTag(),
            // Speech-engine App setting (#93/#94, ADR-0018): the device-local engine catalog from the same
            // AppScope graph — the Settings Destination's "Speech engine" row reads + writes it (whisper is
            // now a real desktop option, no longer only "Automatic").
            speechEngineCatalog = appComponent.speechEngineCatalog,
            // Agent inference-engine choice + entitlement gate (#150, ADR-0027): threaded from the AppScope
            // graph so the gate exists app-wide; the desktop Settings hides the Agent row until a desktop
            // engine lands.
            inferenceEngineCatalog = appComponent.inferenceEngineCatalog,
            // Storage-provider App setting (#210): threaded from the AppScope graph so on-device storage works
            // app-wide; the desktop Settings hides the Storage row until the desktop attach UI lands.
            storageProviderCatalog = appComponent.storageProviderCatalog,
            // Brain dump's recordBrainDump seam (#150 Stage 4) is left at the no-op default on desktop: the
            // mic-record → on-device-transcribe flow is Android-only, and desktop renders the
            // BrainDumpPlaceholder, so there is no recorder to thread until a JVM one lands.
            // The New surface's foreclosed-dictation-permission deep-link (#120): introspect the Sidecar
            // permission port live at click time and open the blocked capability's macOS Privacy pane
            // (mic or Speech Recognition). Off-macOS deepLink() is null and the click no-ops.
            onOpenDictationPermissionSettings = {
                appScope.launch { appComponent.dictationPermissionSettings.deepLink()?.let(::browse) }
            },
            // The AppScope connectivity monitor (#158): the outbox driver flushes on the
            // offline→online edge and skips passes while known-offline.
            connectivity = appComponent.connectivity,
            coroutineContext = Dispatchers.Swing,
        )
    }

    // Hydrate the persisted roster, then idempotently seed the dev-PAT Account(s) — mirroring
    // DefernoApplication. The root (already subscribed to activeAccount) swaps Auth → Main reactively.
    appScope.launch {
        appComponent.accountManager.load()
        seedDevAccounts(appComponent)
    }

    // No Active Account (sign-out, or pre-auth) → nothing to count: clear the dock badge (#117).
    // The non-null side is handled where the per-Account session is (re)built — accountSession above.
    appScope.launch {
        appComponent.accountManager.activeAccount.collect { account ->
            if (account == null) planBadge.trackPlan(null)
        }
    }

    // One-shot best-effort probe so a packaged Win/Mac build surfaces an "Update available" badge at
    // launch without the user opening the menu (a no-op everywhere else). Conveyor also auto-checks in
    // the background on its own schedule; this just makes the in-app state fresh on open.
    updateManager.checkForUpdates()

    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 832.dp))
        // Bind the Essenty lifecycle to the window (resume/pause/stop on focus/minimise/close).
        LifecycleController(lifecycle, windowState)

        // The macOS app-menu handlers (#117), installed once from the application scope — the only
        // place exitApplication is in scope. About opens the themed dialog below; Preferences (⌘,)
        // switches the Main shell to Settings (a no-op pre-Account); Quit (⌘Q / Dock → Quit) is the
        // same graceful exit as window close / Ctrl+Q. Installs nothing without a native app menu.
        var aboutVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            installAppMenuHandlers(
                backend = chrome,
                onAbout = { aboutVisible = true },
                onPreferences = { openPreferences(root) },
                onQuit = ::exitApplication,
            )
        }

        // The window/taskbar icon: the brand flame — the same mark conveyor.conf ships as the
        // packaged app icon (single-sourced off the classpath, see build.gradle.kts) — replacing the
        // earlier transparent placeholder, which KDE rendered as a blank square in the taskbar. If
        // the resource ever goes missing, fall back to transparent rather than crash the window (and
        // never show the default AWT/Java icon either way).
        val density = LocalDensity.current
        val windowIcon = remember(density) {
            runCatching { readClasspathResource("flame.svg").decodeToSvgPainter(density) }
                .getOrDefault(BitmapPainter(ImageBitmap(1, 1)))
        }

        // The Dock icon (#117): the same flame, rasterized for AWT. A packaged build gets its Dock
        // icon from the bundle; this covers the unpackaged dev `run`, which otherwise shows the
        // default Java icon. Capability-guarded inside the backend (ICON_IMAGE), so desktops whose
        // dock takes the icon from the window (Linux/Windows) stay no-ops.
        LaunchedEffect(windowIcon) {
            chrome.setDockIcon(windowIcon.toAwtImage(density, LayoutDirection.Ltr, Size(512f, 512f)))
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = rememberWindowTitle(root),
            icon = windowIcon,
            // Global shortcuts: Esc → shell back (dismiss the overlay, then the active pane, then fall
            // back to Plan home); Ctrl+digit → switch Destination; Ctrl+N → New; Ctrl+F → Search;
            // Ctrl+R → refresh; Ctrl+Q → quit. Bound here (not on the in-app menu) so they work
            // regardless of focus — a precursor to ADR-0007's command registry.
            onPreviewKeyEvent = { event -> handleRootKey(event, root, ::exitApplication) },
        ) {
            // Drive the theme from the Active Account's settings so Appearance changes apply LIVE across
            // the whole app (#72, ADR-0017): the family selects the palette, and the mode resolves to a
            // dark boolean (Auto follows the OS preference). Before any Account is active the StateFlow
            // seeds the default (Deferno / follow-system), so the Auth shell is themed too.
            val settings by root.themeSettings.collectAsState()
            val updateState by updateManager.state.collectAsState()
            DefernoTheme(
                palette = when (settings.themeFamily) {
                    ThemeFamily.Deferno -> DefernoPalette.Deferno
                    ThemeFamily.Mono -> DefernoPalette.Mono
                },
                darkTheme = settings.themeMode.resolveDark(systemDark ?: isSystemInDarkTheme()),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val rootStack by root.stack.subscribeAsState()
                        val mainComponent =
                            (rootStack.active.instance as? RootComponent.Child.Main)?.component
                        // The menu bar belongs to the Main shell only — the Auth shell is a bare
                        // pre-Account placeholder with no Destinations/account actions (ADR-0013).
                        if (mainComponent != null) {
                            DefernoMenuBar(
                                main = mainComponent,
                                updateState = updateState,
                                onCheckForUpdates = updateManager::checkForUpdates,
                                onInstallUpdate = updateManager::installUpdate,
                                onViewReleases = { browse(RELEASES_URL) },
                                onQuit = ::exitApplication,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            RootShell(root)
                        }
                    }
                }
                if (aboutVisible) {
                    AboutDialog(
                        version = updateState.currentVersion,
                        onDismiss = { aboutVisible = false },
                    )
                }
            }
        }
    }
}

/**
 * The startup minimum log level: WARN in a shipped (Conveyor-packaged) build so only warnings +
 * errors reach stdout in prod, DEBUG in a dev `./gradlew run`. "Packaged" is the same signal the
 * update backend uses — [SoftwareUpdateController.getInstance] is null only for an unpackaged run
 * (see ConveyorUpdateBackend).
 */
private fun startupLogLevel(): LogLevel =
    if (SoftwareUpdateController.getInstance() != null) LogLevel.WARN else LogLevel.DEBUG

/** Idempotent: add only the dev Accounts not already in the roster (empty when no PAT is configured). */
private suspend fun seedDevAccounts(appComponent: AppComponent) {
    val manager = appComponent.accountManager
    val existing = manager.accounts.value.map { it.id }.toSet()
    DevAccounts.from(DesktopDevConfig.DEV_ACCOUNTS, DesktopDevConfig.DEV_STAGING_TOKEN)
        .filter { it.account.id !in existing }
        .forEach { devAccount -> manager.addAccount(devAccount.account, devAccount.token) }
}

/**
 * Global keyboard shortcuts: Esc steps back through the shell (dismiss overlay → dismiss pane → Plan
 * home), Ctrl+digit switches Destination, Ctrl+N opens New, Ctrl+F opens Search, Ctrl+R refreshes the
 * foreground Destination, Ctrl+Q quits. Returns whether the event was consumed.
 */
private fun handleRootKey(event: KeyEvent, root: RootComponent, onQuit: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Escape) return root.onBackClicked()
    if (!event.isCtrlPressed) return false
    val main = root.activeMainShell()
    return when (event.key) {
        Key.Q -> {
            onQuit()
            true
        }
        Key.R -> {
            main?.refreshActiveDestination()
            main != null
        }
        Key.N -> {
            main?.openNew()
            main != null
        }
        Key.F -> {
            main?.openOverlay(OverlayRoute.Search)
            main != null
        }
        else -> {
            val destination = digitIndex(event.key)?.let { main?.destinations?.value?.getOrNull(it) } ?: return false
            main?.selectDestination(destination)
            true
        }
    }
}

/** Maps Ctrl+1..9 to a zero-based Destination index; null for any other key. */
private fun digitIndex(key: Key): Int? = when (key) {
    Key.One -> 0
    Key.Two -> 1
    Key.Three -> 2
    Key.Four -> 3
    Key.Five -> 4
    Key.Six -> 5
    Key.Seven -> 6
    Key.Eight -> 7
    Key.Nine -> 8
    else -> null
}

/**
 * The in-app, themed menu bar — a Compose replacement for the native AWT menu bar (which can't follow
 * the dark theme). It is the desktop binding surface for the shell's intents (a precursor to ADR-0007's
 * shared command registry): File → Quit; View → switch Destination / New / Search / Refresh; Account →
 * switch the Active Account (no re-auth) / Sign out (ADR-0017); Help → version + self-update (#103).
 *
 * The [updateState] feeds the Help menu's "Check for updates" row and a trailing "Update available" /
 * "Updating…" badge, so an available update is visible without opening the menu (ADR-0021).
 */
@Composable
private fun DefernoMenuBar(
    main: MainShellComponent,
    updateState: UpdateState,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onViewReleases: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainStack by main.stack.subscribeAsState()
    val activeDestination = mainStack.active.instance.destination
    // The rendered registry (ADR-0040): the conditionally-present Assistant row appears once entitled.
    val destinations by main.destinations.collectAsState()
    val update = presentUpdate(updateState)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.height(40.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuBarMenu(label = "File") { dismiss ->
                MenuRow(text = "Quit", shortcut = "Ctrl+Q") { dismiss(); onQuit() }
            }
            MenuBarMenu(label = "View") { dismiss ->
                destinations.forEachIndexed { index, destination ->
                    MenuRow(
                        text = destination.label,
                        shortcut = "Ctrl+${index + 1}",
                        selected = destination == activeDestination,
                    ) { dismiss(); main.selectDestination(destination) }
                }
                HorizontalDivider()
                MenuRow(text = "New…", shortcut = "Ctrl+N") { dismiss(); main.openNew() }
                MenuRow(text = "Search…", shortcut = "Ctrl+F") { dismiss(); main.openOverlay(OverlayRoute.Search) }
                HorizontalDivider()
                MenuRow(text = "Refresh", shortcut = "Ctrl+R") { dismiss(); main.refreshActiveDestination() }
            }
            MenuBarMenu(label = "Account") { dismiss ->
                // The Active-Account switcher (#68, ADR-0014): picking one re-keys the shell for that
                // Account — fast user switching with no re-auth (ADR-0002/0012). Sign out emits
                // SignOutRequested up to the root, which secure-wipes the Account (ADR-0009/0012).
                val accounts by main.accounts.collectAsState()
                val active by main.activeAccount.collectAsState()
                accounts.forEach { account ->
                    MenuRow(text = account.label, selected = account.id == active?.id) {
                        dismiss()
                        if (account.id != active?.id) main.switchAccount(account.id)
                    }
                }
                if (accounts.isNotEmpty()) HorizontalDivider()
                MenuRow(text = "Sign out") { dismiss(); main.signOut() }
            }
            MenuBarMenu(label = "Help") { dismiss ->
                // Self-update (#103, ADR-0021): the running version, then a single state-driven row —
                // "Check for updates…" / "Restart to update…" / "View all releases…" depending on the
                // update state, plus an optional status line (up to date, available, error).
                MenuRow(text = update.versionLine, enabled = false) {}
                HorizontalDivider()
                MenuRow(text = update.actionLabel, enabled = update.actionEnabled) {
                    dismiss()
                    when (update.action) {
                        UpdateAction.CHECK -> onCheckForUpdates()
                        UpdateAction.INSTALL -> onInstallUpdate()
                        UpdateAction.VIEW_RELEASES -> onViewReleases()
                        UpdateAction.NONE -> Unit
                    }
                }
                update.statusLine?.let { status -> MenuRow(text = status, enabled = false) {} }
            }

            // Always-visible desktop-class affordances (ADR-0007, #86/#87): a search control and a
            // "+" both sit in the toolbar so the global overlays are reachable without opening a menu —
            // the toolbar counterparts of Ctrl+F / Ctrl+N. Pushed to the trailing edge by the spacer.
            Spacer(Modifier.weight(1f))
            // A visible self-update badge (#103): shown only when an update is available/applying, in the
            // primary accent so it stands out; clicking it applies an available update (inert while
            // "Updating…"). The full controls live in the Help menu.
            update.badge?.let { badge ->
                ToolbarAction(text = badge, color = MaterialTheme.colorScheme.primary) {
                    if (update.action == UpdateAction.INSTALL) onInstallUpdate()
                }
            }
            ToolbarAction(text = "Search") { main.openOverlay(OverlayRoute.Search) }
            ToolbarAction(text = "+ New") { main.openNew() }
        }
    }
}

/**
 * The About box the macOS app menu opens (#117) — themed like the rest of the app rather than a
 * native NSPanel, the same trade the in-app menu bar makes. [version] is the running app version
 * (the Conveyor-reported value when packaged, DesktopBuildConfig.APP_VERSION otherwise, #103).
 */
@Composable
private fun AboutDialog(version: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deferno") },
        text = { Text("Version $version") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A trailing toolbar button on the in-app menu bar (the desktop "+ New" / "Search" / update badge). */
@Composable
private fun ToolbarAction(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A top-level menu (File/View/…): a label button that opens a themed [DropdownMenu] beneath it. */
@Composable
private fun MenuBarMenu(
    label: String,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

/** A single menu entry, with an optional shortcut hint and a check for the selected item. */
@Composable
private fun MenuRow(
    text: String,
    shortcut: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(text = text, fontWeight = if (selected) FontWeight.SemiBold else null)
        },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = if (selected) {
            { Text("✓") }
        } else {
            null
        },
        trailingIcon = shortcut?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * Open the **New** create overlay, pre-dated to the Calendar's selected day when the Calendar is the
 * foreground Destination (the desktop counterpart of the Android calendar FAB, #74) — an undated New
 * elsewhere. The date stays the Calendar's concern: it routes through the component's
 * `onNewForSelectedDay`, which emits CreateForDay and lets the shared shell open New(date).
 */
private fun MainShellComponent.openNew() {
    when (val active = stack.value.active.instance) {
        is MainShellComponent.DestinationChild.Calendar -> active.component.onNewForSelectedDay()
        else -> openOverlay(OverlayRoute.New())
    }
}

/** Refresh the data of whichever Destination is foreground (the View → Refresh / Ctrl+R action). */
private fun MainShellComponent.refreshActiveDestination() {
    when (val active = stack.value.active.instance) {
        // Plan is a tier-3 stack now (#51): refresh only when the dashboard is foregrounded (a drilled
        // Task detail has nothing to refresh).
        is MainShellComponent.DestinationChild.Plan ->
            (active.stack.value.active.instance as? MainShellComponent.PlanChild.Dashboard)
                ?.component?.onRefresh() ?: Unit
        is MainShellComponent.DestinationChild.Tasks -> active.component.tree.onRefresh()
        // Calendar (auto-refreshes its window on open/nav), Inbox (re-queries on resume), Profile,
        // Settings, Activity (a live ledger feed), placeholder: no manual refresh.
        is MainShellComponent.DestinationChild.Calendar,
        is MainShellComponent.DestinationChild.Inbox,
        is MainShellComponent.DestinationChild.Profile,
        is MainShellComponent.DestinationChild.Settings,
        is MainShellComponent.DestinationChild.Activity,
        // The Assistant chat manages its own streaming refresh (onRefresh re-checks availability); the desktop
        // View is deferred + the row hidden, so the shell's generic Refresh is a no-op here.
        is MainShellComponent.DestinationChild.Assistant,
        is MainShellComponent.DestinationChild.Placeholder,
        -> Unit
    }
}

/** The window title: "Deferno" in the Auth shell, "Deferno — <Destination>" in the Main shell. */
@Composable
private fun rememberWindowTitle(root: RootComponent): String {
    val rootStack by root.stack.subscribeAsState()
    return when (val child = rootStack.active.instance) {
        is RootComponent.Child.Auth -> "Deferno"
        is RootComponent.Child.Main -> "Deferno — ${activeDestinationLabel(child.component)}"
    }
}

@Composable
private fun activeDestinationLabel(component: MainShellComponent): String {
    val stack by component.stack.subscribeAsState()
    return stack.active.instance.destination.label
}

/**
 * Build a web-app URL for [path] from the configured backend [environment] (#72, AC #3/#4). The web app
 * shares the API host: the env `baseUrl` carries the `/api/` prefix, so the web origin is that base with
 * the `/api/` suffix dropped — the deep-link tracks staging/prod automatically. [path] is a relative
 * web-app route (no leading slash), e.g. `settings/data` or `feedback`.
 */
internal fun webAppUrl(environment: DefernoEnvironment, path: String): String {
    val origin = environment.baseUrl.removeSuffix("/").removeSuffix("/api")
    return "$origin/$path"
}

/**
 * The public GitHub Releases page — the "View all releases" fallback the Help menu opens when in-app
 * self-update isn't available (Linux package-manager updates, or an unpackaged dev run; #103).
 */
private const val RELEASES_URL = "https://github.com/Circuit-Stitch/deferno-kmp/releases"

/** Read a classpath resource fully, throwing when absent (callers decide whether that's fatal). */
private fun readClasspathResource(path: String): ByteArray =
    checkNotNull(object {}.javaClass.classLoader.getResourceAsStream(path)) { "missing resource: $path" }
        .use { it.readBytes() }

/** Open [url] in the OS default browser (the Settings web deep-links, #72). Best-effort + headless-safe. */
private fun browse(url: String) {
    runCatching {
        if (!Desktop.isDesktopSupported()) return@runCatching
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) desktop.browse(URI(url))
    }
}

/**
 * The per-Account SQLite databases directory for the desktop app — a real, file-backed location under
 * the OS user-data dir (ADR-0017; no SQLCipher yet, OS disk protection). XDG_DATA_HOME / APPDATA /
 * `~/Library/Application Support` per platform, else `~/.local/share`. Created if missing.
 */
private fun defernoDatabasesDir(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home").orEmpty()
    val base = when {
        "win" in os -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming"
        "mac" in os || "darwin" in os -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
    }
    return File(base, "Deferno/databases").apply { mkdirs() }.absolutePath
}

/**
 * Run [block] on the AWT event-dispatch thread and return its result. Decompose requires its component
 * tree to be created on the UI (main) thread; on Compose Desktop that is the EDT, not the JVM `main`
 * thread that `fun main()` runs on — so the root must be built here rather than inline in main().
 */
private fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}

/**
 * The OS dark-mode preference, read from the freedesktop **XDG Desktop Portal** (`org.freedesktop.
 * appearance` → `color-scheme`: 1 = prefer dark, 2 = prefer light, 0 = no preference). KDE Plasma and
 * GNOME both implement it; Compose Desktop's own `isSystemInDarkTheme()` does not consult it on Linux,
 * which is why a dark KDE session was rendering the light palette. Returns null when the portal isn't
 * reachable (e.g. no `gdbus`, or Windows/macOS) so the caller can fall back to `isSystemInDarkTheme()`.
 */
private fun systemPrefersDark(): Boolean? = runCatching {
    val process = ProcessBuilder(
        "gdbus", "call", "--session",
        "--dest", "org.freedesktop.portal.Desktop",
        "--object-path", "/org/freedesktop/portal/desktop",
        "--method", "org.freedesktop.portal.Settings.Read",
        "org.freedesktop.appearance", "color-scheme",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    when {
        "uint32 1" in output -> true   // prefer dark
        "uint32 2" in output -> false  // prefer light
        else -> null                   // no preference / unexpected
    }
}.getOrNull()
