package com.circuitstitch.deferno.shell.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.SectionLabel
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.shell.ChromeActionKind
import com.circuitstitch.deferno.shell.ChromeSpec
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.MainShellComponent
import com.circuitstitch.deferno.shell.OverlayRoute
import kotlinx.coroutines.launch

/**
 * The shared **Main shell chrome** (ADR-0013 / ADR-0017): one implementation of the navigation surface
 * rendered by both the Android and desktop shells (the #27 "Compose Views in a sibling Android+JVM
 * module" pattern applied to the shell). It is a **reveal drawer** — the whole content screen slides
 * aside to expose a navigation menu sitting *underneath* it — plus a slim, **adaptive** top bar driven
 * by the shell-computed [ChromeSpec] (Cand 1): a ☰ menu + title + create actions at a Destination root,
 * and a ← back + the detail's title when drilled into a tier-3 detail. One bar, so the per-screen headers
 * are gone and the buttons no longer come and go arbitrarily.
 *
 * The chrome is a pure renderer of the shared [MainShellComponent]: it reads [activeDestination] to
 * highlight the drawer row, [MainShellComponent.chrome] for the top-bar spec, and renders the foreground
 * Destination via the platform-supplied [body] (which references the per-platform `:feature:*:ui` screens
 * this module can't depend on). The top bar's New/Brain dump handlers — including the **Calendar pre-date**
 * special case (#74) — are wired in the shell's chrome computation, so this module needs no
 * `:feature:calendar` dependency. Drawer open/close is **hoisted state**
 * ([drawerOpen] / [onDrawerOpenChange]) so each platform can route its own back affordance (Android
 * `BackHandler`) to close the drawer before anything else. Beyond the hamburger toggle, a freeform
 * **left-edge swipe** opens the drawer and a drag on the open content closes it — both track the finger
 * 1:1 and settle to the nearer end on release.
 *
 * The two trailing-action glyphs ([brainDumpIcon] = voice_chat, [newIcon] = add_task) are **injected by
 * the platform**, not loaded here: Android sources them from native `res/drawable` (reliable under
 * Robolectric, which doesn't serve a dependency module's `composeResources`), desktop from
 * `composeResources` (loaded off the JVM classpath). Same glyphs, platform-appropriate loader.
 *
 * The top bar respects [WindowInsets.statusBars] (empty on desktop), which is what keeps the trailing
 * actions clear of the device status bar / battery — the bug that motivated this restructure.
 */
@Composable
fun ShellChrome(
    component: MainShellComponent,
    activeDestination: Destination,
    drawerOpen: Boolean,
    onDrawerOpenChange: (Boolean) -> Unit,
    brainDumpIcon: Painter,
    newIcon: Painter,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val chrome by component.chrome.collectAsState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Wide enough to read as a drawer, but always leaving a peek of content on the largest phones;
        // capped so a desktop window doesn't get an absurdly wide menu.
        val drawerWidth = minOf(maxWidth * 0.82f, 320.dp)
        val density = LocalDensity.current
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        // A flick faster than this (px/s, in either direction) commits open/closed regardless of how far
        // it travelled — the standard fling feel layered on top of the halfway position threshold.
        val flingVelocityPx = with(density) { 400.dp.toPx() }
        val scope = rememberCoroutineScope()
        val velocityTracker = remember { VelocityTracker() }

        // Open fraction (0 closed … 1 open). [animProgress] drives the button toggle / back close and
        // the settle animation; [dragProgress] is non-null only while a finger is dragging and makes the
        // drawer track the finger 1:1. The effective fraction is `dragProgress ?: animProgress.value`,
        // read in the layout phase below so a drag/animation never recomposes the body.
        val animProgress = remember { Animatable(0f) }
        var dragProgress by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(drawerOpen) {
            if (dragProgress == null) animProgress.animateTo(if (drawerOpen) 1f else 0f)
        }
        // Each horizontal delta moves the open fraction by that fraction of the drawer width.
        fun onDrag(dragAmount: Float) {
            dragProgress = ((dragProgress ?: animProgress.value) + dragAmount / drawerWidthPx).coerceIn(0f, 1f)
        }
        // Release: a fast flick wins by its direction; otherwise the nearer end wins. Hand the fraction
        // back to the animation and report the committed state up so back/Esc + hamburger stay in sync.
        fun settle(velocityX: Float) {
            val from = dragProgress ?: return
            val opened = when {
                velocityX > flingVelocityPx -> true
                velocityX < -flingVelocityPx -> false
                else -> from >= 0.5f
            }
            dragProgress = null
            scope.launch {
                animProgress.snapTo(from)
                animProgress.animateTo(if (opened) 1f else 0f)
            }
            onDrawerOpenChange(opened)
        }
        // The swipe gesture shared by the closed-state edge handle and the open content: track the finger
        // (and its velocity) horizontally, then settle on release.
        val drawerDrag = Modifier.pointerInput(drawerWidthPx) {
            detectHorizontalDragGestures(
                onDragStart = { velocityTracker.resetTracking() },
                onDragEnd = { settle(velocityTracker.calculateVelocity().x) },
                onDragCancel = { settle(0f) },
                onHorizontalDrag = { change, dragAmount ->
                    velocityTracker.addPointerInputChange(change)
                    onDrag(dragAmount)
                },
            )
        }

        // Layer 1 — drawn first, so it sits UNDERNEATH the sliding content.
        ShellDrawer(
            component = component,
            activeDestination = activeDestination,
            // The two top-of-drawer capture glyphs reuse the platform-injected painters (the same
            // add_task / voice_chat glyphs the top bar uses) — not the design-system composeResources
            // directly, which Robolectric can't serve from a dependency module (see this file's KDoc).
            newIcon = newIcon,
            brainDumpIcon = brainDumpIcon,
            onSelectDestination = {
                onDrawerOpenChange(false)
                component.selectDestination(it)
            },
            onSearch = {
                onDrawerOpenChange(false)
                component.openOverlay(OverlayRoute.Search)
            },
            // The two top-of-drawer capture triggers (re-skin: the drawer surfaces the same overlay
            // routes the top-bar create actions raise, so a capture path is always a tap away).
            onNewTask = {
                onDrawerOpenChange(false)
                component.openOverlay(OverlayRoute.New())
            },
            onBrainDump = {
                onDrawerOpenChange(false)
                component.openOverlay(OverlayRoute.BrainDump)
            },
            modifier = Modifier.align(Alignment.CenterStart).width(drawerWidth).fillMaxHeight(),
        )

        // Layer 2 — the app content, drawn ON TOP. One graphicsLayer (a draw-phase read of the open
        // fraction, so a drag/animation never recomposes the body) slides it right to reveal the menu,
        // rounds its leading (top-/bottom-left) corners, and casts a shadow off its left edge onto the
        // drawer beneath — slide, radius, and shadow all scale with how far open it is, so it reads as a
        // card lifting over the menu. Flat rectangle with no shadow when fully closed (p = 0).
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = dragProgress ?: animProgress.value
                    translationX = p * drawerWidthPx
                    val radius = ShellChromeDefaults.ContentCornerRadius.toPx() * p
                    shape = RoundedCornerShape(topStart = radius, bottomStart = radius)
                    clip = true
                    shadowElevation = ShellChromeDefaults.ContentShadowElevation.toPx() * p
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    ShellTopBar(
                        chrome = chrome,
                        brainDumpIcon = brainDumpIcon,
                        newIcon = newIcon,
                        onMenu = { onDrawerOpenChange(!drawerOpen) },
                        onBack = { component.onBack() },
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) { body() }
                }
                // When open, the slid-aside content is the dismiss target: tap to close, or drag it back
                // toward the edge (finger-tracking) to close.
                if (drawerOpen) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .then(drawerDrag)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = "Close menu",
                            ) { onDrawerOpenChange(false) },
                    )
                }
            }
        }

        // The left-edge open handle: a freeform swipe inward from the start edge opens the drawer,
        // tracking the finger. Present only while closed, and while present it reserves this strip from
        // the OS back gesture (systemGestureExclusionCompat) so the swipe-to-open isn't stolen on Android
        // gesture-nav. Once the drawer is open this handle is gone, so the system back gesture is allowed
        // again (e.g. to close the drawer / navigate back). The content (above) handles closing.
        if (!drawerOpen) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(ShellChromeDefaults.EdgeSwipeWidth)
                    .systemGestureExclusionCompat()
                    .then(drawerDrag),
            )
        }
    }
}

/**
 * Reserve the receiver's bounds from the OS navigation gestures — on Android gesture-nav, so the
 * swipe-to-open edge zone isn't stolen by the system back gesture. Applied only to the closed-state
 * edge handle, so once the drawer is open (handle gone) the back gesture is allowed again. No-op on
 * desktop (no system gestures). `Modifier.systemGestureExclusion` is Android-only, hence expect/actual.
 */
expect fun Modifier.systemGestureExclusionCompat(): Modifier

/**
 * The slim top bar, driven by the shell-computed [ChromeSpec] (Cand 1): the adaptive leading affordance
 * (☰ menu at a Destination root → opens the drawer; ← back when drilled → pops the drill-down), the
 * surface title, and the surface's trailing [ChromeAction]s — all clear of the status bar. The two
 * injected glyphs ([brainDumpIcon], [newIcon]) are mapped from the action [kind][ChromeActionKind];
 * Refresh uses a material glyph.
 */
@Composable
private fun ShellTopBar(
    chrome: ChromeSpec,
    brainDumpIcon: Painter,
    newIcon: Painter,
    onMenu: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chrome.drilled) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu")
            }
        }
        Text(
            text = chrome.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
        )
        chrome.actions.forEach { action ->
            IconButton(onClick = action.onInvoke) {
                when (action.kind) {
                    ChromeActionKind.Refresh -> Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    ChromeActionKind.BrainDump -> Icon(brainDumpIcon, contentDescription = "Brain dump")
                    ChromeActionKind.New -> Icon(newIcon, contentDescription = "New")
                }
            }
        }
    }
}

/**
 * The reveal-drawer menu, restyled to the "See the trees" direction: the Deferno wordmark/flame at the
 * top, an `ADD SOMETHING` band with the two capture triggers pinned beneath it (a solid **New task**
 * and an outlined **Brain dump** — the two paths the design keeps a tap away), then the calm
 * destination rows (a Search row + one per [Destination], the active one highlighted, the Inbox
 * carrying its Ready badge), and a quiet account footer. Search + each Destination + the two capture
 * triggers close the drawer via the caller's handlers, which raise the same overlay routes the top bar
 * does — a pure re-skin, no new component surface (ADR-0015).
 */
@Composable
private fun ShellDrawer(
    component: MainShellComponent,
    activeDestination: Destination,
    newIcon: Painter,
    brainDumpIcon: Painter,
    onSelectDestination: (Destination) -> Unit,
    onSearch: () -> Unit,
    onNewTask: () -> Unit,
    onBrainDump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accounts by component.accounts.collectAsState()
    val activeAccount by component.activeAccount.collectAsState()
    // The Inbox nav badge (ADR-0015 Inbox amendment): always declares state — "empty" or the Ready count.
    val inboxCount by component.inboxReadyCount.collectAsState()
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // The Deferno wordmark + flame — the brand at the top of the menu.
            DrawerWordmark()

            Spacer(Modifier.height(20.dp))

            // The two capture paths, pinned at the top (design: a tap away).
            SectionLabel("ADD SOMETHING")
            Spacer(Modifier.height(10.dp))
            CaptureButton(
                text = "New task",
                subtitle = "Fill in the details",
                icon = newIcon,
                onClick = onNewTask,
                solid = true,
            )
            Spacer(Modifier.height(10.dp))
            CaptureButton(
                text = "Brain dump",
                subtitle = "Speak or type — sorts to Inbox",
                icon = brainDumpIcon,
                onClick = onBrainDump,
                solid = false,
            )

            Spacer(Modifier.height(22.dp))

            // The destinations — calm rows. Search first, then the Destination registry.
            DrawerRow(
                label = "Search",
                icon = Icons.Filled.Search,
                selected = false,
                onClick = onSearch,
            )
            component.destinations.forEach { destination ->
                DrawerRow(
                    label = destination.label,
                    icon = destination.icon,
                    selected = destination == activeDestination,
                    // The Inbox always declares whether there's anything to triage (ADR-0015 amendment).
                    badge = if (destination == Destination.Inbox) {
                        if (inboxCount == 0) "empty" else inboxCount.toString()
                    } else {
                        null
                    },
                    onClick = { onSelectDestination(destination) },
                )
            }

            // The drawer scrolls, so the footer follows the destination list rather than being pinned to
            // the bottom (a weight spacer is inert inside a verticalScroll).
            Spacer(Modifier.height(24.dp))

            // The quiet account footer, with the switcher when more than one Account.
            if (accounts.size > 1) {
                AccountSwitcher(
                    accounts = accounts,
                    active = activeAccount,
                    onSwitch = component::switchAccount,
                )
            } else {
                DrawerAccountFooter(active = activeAccount)
            }
        }
    }
}

/** The Deferno wordmark with a small accent-tile flame standing in for the brand mark in the menu head. */
@Composable
private fun DrawerWordmark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            // A small flame glyph stand-in: the brand mark, tinted onto the accent tile.
            Text("🔥", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "Deferno",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * A drawer capture trigger — the design's two prominent "add something" buttons. [solid] = the filled
 * primary New task button; otherwise the calm outlined Brain dump sibling. Both carry a leading glyph +
 * title + subtitle on a ≥56dp rounded surface, fully labelled for TalkBack as one button. (Built here,
 * not via the shared [com.circuitstitch.deferno.core.designsystem.component.PrimaryActionButton], because
 * the design wants a leading **Painter** glyph + a subtitle line the single-line shared atom doesn't carry.)
 */
@Composable
private fun CaptureButton(
    text: String,
    subtitle: String,
    icon: Painter,
    onClick: () -> Unit,
    solid: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (solid) scheme.primary else scheme.surface
    val content = if (solid) scheme.onPrimary else scheme.onSurface
    val subtitleColor = if (solid) scheme.onPrimary.copy(alpha = 0.82f) else MaterialTheme.defernoColors.inkMuted
    val iconTint = if (solid) scheme.onPrimary else scheme.primary
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(16.dp),
        border = if (solid) null else BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$text. $subtitle" },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            Column {
                Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
        }
    }
}

/**
 * A single calm destination row: a leading glyph + label, the active row lifted onto the primary
 * container, an optional trailing [badge] (the Inbox Ready count). ≥48dp, an honest tab role + state
 * for TalkBack.
 */
@Composable
private fun DrawerRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(scheme.primaryContainer) else Modifier)
            .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.defernoColors.amberDeep else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Surface(
                color = if (selected) scheme.surface else scheme.surfaceVariant,
                contentColor = MaterialTheme.defernoColors.inkMuted,
                shape = CircleShape,
            ) {
                MonoMeta(text = badge, modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp))
            }
        }
    }
}

/**
 * The quiet single-Account footer: the Account name over a calm mono meta line, a divider above. (v1
 * [Account] carries no Org — Org is a soft filter within an Account, not part of its identity, ADR-0002
 * — so the meta line reads "Signed in" rather than inventing an org label on the wire.)
 */
@Composable
private fun DrawerAccountFooter(active: Account?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Signed in as ${active?.label ?: "Deferno"}"
            },
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.height(12.dp))
        Text(
            text = active?.label ?: "Deferno",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        MonoMeta(text = "Signed in", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
    }
}

/**
 * The Active-Account selector (#68, ADR-0014): a dropdown of the device's Accounts. Picking one calls
 * [MainShellComponent.switchAccount], which re-keys the shell for that Account — fast user switching
 * with no re-auth (ADR-0002/0012).
 */
@Composable
private fun AccountSwitcher(
    accounts: List<Account>,
    active: Account?,
    onSwitch: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(active?.label ?: "Select account")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch account")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.label) },
                        onClick = {
                            expanded = false
                            if (account.id != active?.id) onSwitch(account.id)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The **desktop Brain dump stand-in** (ADR-0027). The dictation-driven Extractor shipped on Android
 * first (#150) — the on-device shacl floor is Android-only — so the real surface lives in the Android
 * app's `BrainDumpScreen`; desktop shows this until a JVM inference engine lands. [onDismiss] pops the
 * overlay.
 */
@Composable
fun BrainDumpPlaceholder(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Brain dump",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Speak freely and Deferno will turn it into draft tasks. Brain dump is available on Android; desktop support is on the way.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 24.dp)) {
                Text("Close")
            }
        }
    }
}

/** The nav label for a [Destination] — a View concern, kept out of the shared registry (also read by
 *  the desktop menu bar, [com.circuitstitch.deferno.desktop.Main]). */
val Destination.label: String
    get() = when (this) {
        Destination.Plan -> "Plan"
        Destination.Calendar -> "Calendar"
        Destination.Tasks -> "Tasks"
        Destination.Inbox -> "Inbox"
        Destination.Activity -> "Activity"
        Destination.Profile -> "Profile"
        Destination.Settings -> "Settings"
    }

/** The drawer glyph for a [Destination] — a View concern, material-icons-core only. */
private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Plan -> Icons.Filled.Home
        Destination.Calendar -> Icons.Filled.DateRange
        Destination.Tasks -> Icons.AutoMirrored.Filled.List
        Destination.Inbox -> Icons.Filled.MailOutline
        Destination.Activity -> Icons.Filled.Notifications
        Destination.Profile -> Icons.Filled.Person
        Destination.Settings -> Icons.Filled.Settings
    }
