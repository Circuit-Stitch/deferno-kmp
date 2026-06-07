package com.circuitstitch.deferno.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.desktop.demo.DemoPlanRepository
import com.circuitstitch.deferno.desktop.demo.DemoTaskRepository
import com.circuitstitch.deferno.desktop.demo.SampleData
import com.circuitstitch.deferno.desktop.shell.DefaultRootComponent
import com.circuitstitch.deferno.desktop.shell.MainShellComponent
import com.circuitstitch.deferno.desktop.shell.RootComponent
import com.circuitstitch.deferno.desktop.shell.RootShell
import com.circuitstitch.deferno.desktop.shell.StubAuthGate
import com.circuitstitch.deferno.desktop.shell.label
import javax.swing.SwingUtilities
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Desktop (JVM) Compose entry point. It builds **one [RootComponent] per window** (ADR-0008 G2/G4) over
 * the process-global stub repositories, and renders the desktop-native navigation shell (Auth ↔ Main →
 * Destination graph, ADR-0013) — the desktop counterpart of the Android shell (#55).
 *
 * Desktop affordances ("its best self", ADR-0007: desktop-class input is a v1 criterion): an **in-app,
 * themed menu bar** ([DefernoMenuBar]: File → Quit; View → switch Destination / Refresh; Account →
 * Sign out) with keyboard shortcuts (Ctrl+1/2 destinations, Ctrl+R refresh, Ctrl+Q quit, **Esc**
 * dismisses the foreground pane), a persistent rail that expands to a drawer by width, and a window
 * title that tracks the active Destination. The native AWT menu bar is deliberately avoided — it can't
 * follow the Compose dark theme — and the default AWT window icon is blanked out.
 *
 * TODO(DI): source the RootComponent and its dependencies from the DI scene graph (ADR-0008); the
 * in-memory repositories + SampleData are TEMPORARY stubs until then.
 */
fun main() {
    // NOTE: interactive window resize briefly shows a white bleed in the newly-grown area before
    // Compose repaints it — a known upstream Skiko/Compose-Desktop artifact on Linux (the GPU render
    // surface lags the resize). AWT background colouring and software rendering were both tried and
    // didn't resolve it, so we stay on the default (GPU) renderer; revisit when Skiko/JBR Wayland
    // resize handling improves.

    // The data layer is process-global (ADR-0008 G2); presentation (the component tree) is per-window.
    val timeZone = TimeZone.currentSystemDefault()
    val taskRepository = DemoTaskRepository(SampleData.tasks)
    val planRepository = DemoPlanRepository(
        SampleData.planTaskIds.mapNotNull { id -> SampleData.tasks.firstOrNull { it.id == id } },
    )
    val authGate = StubAuthGate()

    // Honour the OS dark/light preference. Compose Desktop's isSystemInDarkTheme() doesn't read the
    // Linux desktop setting, so on Linux we ask the XDG Desktop Portal directly (KDE/GNOME implement
    // it); null elsewhere → fall back to Compose's detector (which works on Windows/macOS).
    val systemDark: Boolean? = systemPrefersDark()

    // One Decompose root per window, with its own Essenty lifecycle (the desktop counterpart of
    // Android's `retainedComponent`; the window state drives the lifecycle via LifecycleController).
    // Decompose requires its component tree to be built on the UI (main) thread — which on Compose
    // Desktop is the AWT event-dispatch thread, not the JVM `main` thread `main()` runs on — so the
    // root is constructed via [runOnUiThread] (otherwise Decompose throws NotOnMainThreadException).
    val lifecycle = LifecycleRegistry()
    val root: RootComponent = runOnUiThread {
        DefaultRootComponent(
            componentContext = DefaultComponentContext(lifecycle),
            authGate = authGate,
            taskRepository = taskRepository,
            planRepository = planRepository,
            today = Clock.System.todayIn(timeZone),
            timeZone = timeZone.id,
            output = { output ->
                when (output) {
                    // Stub mirror of a Tasks "add to plan" into the in-memory plan (the real Plan write
                    // is a domain command that arrives with DI / the API), using the concrete stub repos.
                    is RootComponent.Output.AddToPlanRequested ->
                        planRepository.add(taskRepository.snapshot(output.id))
                }
            },
        )
    }

    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 832.dp))
        // Bind the Essenty lifecycle to the window (resume/pause/stop on focus/minimise/close).
        LifecycleController(lifecycle, windowState)

        // A fully transparent window icon — there is no Deferno desktop icon yet, and we never want the
        // default AWT/Java icon to show in the title bar / taskbar.
        val blankIcon = remember { BitmapPainter(ImageBitmap(1, 1)) }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = rememberWindowTitle(root),
            icon = blankIcon,
            // Global shortcuts: Esc → shell back (dismiss the active pane, then fall back to Plan home);
            // Ctrl+digit → switch Destination; Ctrl+R → refresh; Ctrl+Q → quit. Bound here (not on the
            // in-app menu) so they work regardless of focus — a precursor to ADR-0007's command registry.
            onPreviewKeyEvent = { event -> handleRootKey(event, root, ::exitApplication) },
        ) {
            DefernoTheme(darkTheme = systemDark ?: isSystemInDarkTheme()) {
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
                                authGate = authGate,
                                onQuit = ::exitApplication,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            RootShell(root)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Global keyboard shortcuts: Esc steps back through the active Destination (dismiss pane → Plan home),
 * Ctrl+digit switches Destination, Ctrl+R refreshes the foreground Destination, Ctrl+Q quits. Returns
 * whether the event was consumed.
 */
private fun handleRootKey(event: KeyEvent, root: RootComponent, onQuit: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Escape) return root.onBackClicked()
    if (!event.isCtrlPressed) return false
    val main = (root.stack.value.active.instance as? RootComponent.Child.Main)?.component
    return when (event.key) {
        Key.Q -> {
            onQuit()
            true
        }
        Key.R -> {
            main?.refreshActiveDestination()
            main != null
        }
        else -> {
            val destination = digitIndex(event.key)?.let { main?.destinations?.getOrNull(it) } ?: return false
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
 * shared command registry): File → Quit, View → switch Destination / Refresh, Account → Sign out.
 */
@Composable
private fun DefernoMenuBar(
    main: MainShellComponent,
    authGate: StubAuthGate,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainStack by main.stack.subscribeAsState()
    val activeDestination = mainStack.active.instance.destination
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.height(40.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuBarMenu(label = "File") { dismiss ->
                MenuRow(text = "Quit", shortcut = "Ctrl+Q") { dismiss(); onQuit() }
            }
            MenuBarMenu(label = "View") { dismiss ->
                main.destinations.forEachIndexed { index, destination ->
                    MenuRow(
                        text = destination.label,
                        shortcut = "Ctrl+${index + 1}",
                        selected = destination == activeDestination,
                    ) { dismiss(); main.selectDestination(destination) }
                }
                HorizontalDivider()
                MenuRow(text = "Refresh", shortcut = "Ctrl+R") { dismiss(); main.refreshActiveDestination() }
            }
            MenuBarMenu(label = "Account") { dismiss ->
                MenuRow(text = "Sign out") { dismiss(); authGate.signOut() }
            }
        }
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

/** A single menu entry, with an optional shortcut hint and a check for the selected Destination. */
@Composable
private fun MenuRow(
    text: String,
    shortcut: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(text = text, fontWeight = if (selected) FontWeight.SemiBold else null)
        },
        onClick = onClick,
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

/** Refresh the data of whichever Destination is foreground (the View → Refresh / Ctrl+R action). */
private fun MainShellComponent.refreshActiveDestination() {
    when (val active = stack.value.active.instance) {
        is MainShellComponent.DestinationChild.Plan -> active.component.onRefresh()
        is MainShellComponent.DestinationChild.Tasks -> active.component.list.onRefresh()
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
