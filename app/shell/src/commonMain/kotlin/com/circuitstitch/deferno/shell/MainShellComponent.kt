package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.assistant.AssistantComponent
import com.circuitstitch.deferno.feature.braindumps.InboxComponent
import com.circuitstitch.deferno.feature.calendar.CalendarComponent
import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.tasks.SearchComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

/**
 * The **Main shell** (ADR-0013): the post-[[Account]] surface that hosts the **Destination graph**.
 * It exposes the [Destination] registry ([destinations]) the View renders as a nav suite, the active
 * Destination as a Decompose [ChildStack], and a single shell-level **overlay route** ([overlay]) that
 * sits above the whole graph (ADR-0015).
 *
 * Tier-1 switching ([selectDestination]) is **lateral and state-preserving — multiple back stacks**
 * (ADR-0007 tier 1 / ADR-0008 G5): each Destination's component (and its own tier-2 panes / tier-3
 * drill-downs) is **retained** while another Destination is foreground, so leaving Tasks for Plan and
 * returning restores Tasks exactly. This is realized with Decompose `bringToFront`, which reuses the
 * existing child for a Destination already on the stack rather than recreating it. Destinations are
 * created lazily (only on first visit), so the registry scales to a dozen-plus without eager cost.
 *
 * The **overlay route** ([openOverlay] / [dismissOverlay]) is the shared mechanism Search and New will
 * reuse (ADR-0015): a route pushed *above* the foreground Destination and dismissed back to origin,
 * leaving the Destination's retained state untouched. v1 ships only a trivial [OverlayRoute.Placeholder]
 * so the mechanism — render position and back precedence — exists and is exercised; the real routes
 * land with #71 (New) and #73 (Search).
 *
 * The shell also routes the cross-feature intents the Destinations emit (a Plan tap opens that Task in
 * an overlay above the dashboard; a Tasks "add to plan" and a Profile "sign out" bubble up via [Output]
 * for the host to apply against the Active Account — the same Output-up routing the demo host owned).
 */
interface MainShellComponent {
    /**
     * The ordered Destination registry the nav suite renders — not a fixed count, and **not constant**:
     * the conditionally-present [Destination.Assistant] is omitted until the Org is `entitled` (ADR-0040),
     * so this is a [StateFlow] the View observes (the Assistant row appears once availability resolves).
     */
    val destinations: StateFlow<List<Destination>>

    /** The foreground Destination + the retained, state-preserving back stack of visited Destinations. */
    val stack: Value<ChildStack<*, DestinationChild>>

    /**
     * The shell-level overlay route, or empty when none is open (ADR-0015). The View renders the
     * [OverlayChild] above the foreground Destination; [onBack] dismisses it first.
     */
    val overlay: Value<ChildSlot<*, OverlayChild>>

    /**
     * The active Destination + open overlay, mirrored as [StateFlow]s for the SwiftUI Views to observe
     * via SKIE (which bridges `StateFlow` + the sealed children → Swift enums, but not Decompose's
     * [Value]/[ChildStack]/[ChildSlot]). The Compose/Android side keeps observing [stack]/[overlay].
     */
    val activeDestination: StateFlow<DestinationChild>
    val activeOverlay: StateFlow<OverlayChild?>

    /** Switch foreground Destination laterally, preserving every Destination's retained state. */
    fun selectDestination(destination: Destination)

    /** Push [route] as a shell-level overlay above the foreground Destination (ADR-0015). */
    fun openOverlay(route: OverlayRoute)

    /** Dismiss the open overlay back to the foreground Destination (a no-op if none is open). */
    fun dismissOverlay()

    /**
     * Route Android system-back within the Main shell: dismiss an open [overlay] first, then let the
     * active Destination dismiss its own tier-2/tier-3 state, then fall back from a non-home Destination
     * to the [Destination.Plan] home. Returns whether back was consumed (`false` → the host lets the
     * platform exit the scene).
     */
    fun onBack(): Boolean

    /** The Accounts on this device + the Active one, for the in-shell account switcher (ADR-0014). */
    val accounts: StateFlow<List<Account>>
    val activeAccount: StateFlow<Account?>

    /**
     * The count of **Ready** Brain dump drafts awaiting triage — the nav badge on the [Destination.Inbox]
     * row ("empty"/[n], ADR-0015 Inbox amendment). Observed at shell level so the badge shows even before
     * the (lazily-built) Inbox Destination is first visited.
     */
    val inboxReadyCount: StateFlow<Int>

    /**
     * The adaptive top-bar [ChromeSpec] for the foreground **in-chrome** surface (Cand 1) — one bar,
     * computed in the shell from the active Destination + its tier-3 drill-down. The View renders it, so
     * the per-screen headers are gone and the header buttons no longer "come and go" arbitrarily.
     */
    val chrome: StateFlow<ChromeSpec>

    /**
     * Whether the Active Account's session has expired (#297) — a `401` on an authenticated request the
     * shared client surfaced (vs. a transient network blip, which stays silent). The read surfaces
     * (Tasks / Plan / …) render a "Session expired — sign in again" banner off this so a dead token can't
     * masquerade as a stale cache; signing back in clears it on the next successful sync. A process-wide
     * `StateFlow` so the banner shows even on a surface mounted *after* the session died.
     */
    val sessionExpired: StateFlow<Boolean>

    /** Switch the Active Account — re-keys the shell for the new Account, no re-auth (ADR-0002/0012). */
    fun switchAccount(id: AccountId)

    /**
     * Request sign-out of the Active Account from a **shell-chrome** affordance (the desktop Account
     * menu, ADR-0017) — emits [Output.SignOutRequested] for the host/root to secure-wipe the Account
     * (ADR-0009/0012). It is the same intent the Profile Destination's sign-out button raises, surfaced
     * at shell level so chrome outside the Destination graph (a menu bar) can raise it too.
     */
    fun signOut()

    /** A live Destination instance, tagged with which [Destination] it is so the View can render it. */
    sealed interface DestinationChild {
        val destination: Destination

        /**
         * The Plan Destination — now a tier-3 drill-down (ADR-0007 t3, #51): [stack] is the dashboard at
         * the base and any drilled-in single-Task [detail][PlanChild.Detail] above it, rendered INSIDE the
         * shell chrome (the drawer stays live), not as a shell overlay above it. [onBack] pops an open
         * detail toward the dashboard, returning whether it consumed the back.
         */
        class Plan(
            val stack: Value<ChildStack<*, PlanChild>>,
            /** [stack]'s active child mirrored for SwiftUI to observe via SKIE (see [activeDestination]). */
            val activeChild: StateFlow<PlanChild>,
            val onBack: () -> Boolean,
        ) : DestinationChild {
            override val destination: Destination = Destination.Plan
        }

        /** The Calendar Destination (#74): a single-pane month grid + day agenda over Occurrences. */
        class Calendar(val component: CalendarComponent) : DestinationChild {
            override val destination: Destination = Destination.Calendar
        }

        class Tasks(val component: TasksComponent) : DestinationChild {
            override val destination: Destination = Destination.Tasks
        }

        /**
         * The Assistant Destination (ADR-0040, #282): the server-mediated conversational AI chat. Holds
         * the shared [AssistantComponent] state machine; the iOS SwiftUI View renders it (the
         * Android/desktop Compose Views are deferred — they show a placeholder body). Only built when the
         * Org is `entitled`, so it is reached only on iOS in v1, where the client is wired.
         */
        class Assistant(val component: AssistantComponent) : DestinationChild {
            override val destination: Destination = Destination.Assistant
        }

        /** The Inbox Destination (ADR-0015 amendment): the triage queue for persisted Brain dump drafts. */
        class Inbox(val component: InboxComponent) : DestinationChild {
            override val destination: Destination = Destination.Inbox
        }

        class Profile(val component: ProfileComponent) : DestinationChild {
            override val destination: Destination = Destination.Profile
        }

        /** The Settings tier-3 drill-down (#72): a category list → per-category detail (ADR-0007 t3). */
        class Settings(val component: SettingsComponent) : DestinationChild {
            override val destination: Destination = Destination.Settings
        }

        /** The Activity Destination (#260): a reverse-chronological feed of the offline-first ledger. */
        class Activity(val component: ActivityComponent) : DestinationChild {
            override val destination: Destination = Destination.Activity
        }

        /**
         * A Destination whose own slice isn't built yet (Calendar #74) — a logic-less child the View
         * renders as a placeholder body. It is still a real tier-1 Destination with its own retained
         * back-stack entry, so it drops in its slice later with no structural change.
         */
        class Placeholder(override val destination: Destination) : DestinationChild
    }

    /**
     * The Plan Destination's tier-3 children (ADR-0007 t3): the dashboard at the base of
     * [DestinationChild.Plan.stack], and a drilled-in single-Task [Detail] above it. Detail reuses the
     * Tasks slice's [TaskDetailComponent] — the shell composes both slices (ADR-0004), so Plan needs no
     * feature→feature dependency — so it hydrates + edits identically to the Tasks Destination's detail.
     */
    sealed interface PlanChild {
        class Dashboard(val component: PlanComponent) : PlanChild
        class Detail(val component: TaskDetailComponent) : PlanChild
    }

    /** A shell-level overlay instance the View renders above the foreground Destination (ADR-0015). */
    sealed interface OverlayChild {
        /** The v1 stand-in (both Search #73 and New #71 are real routes over the same primitive). */
        data object Placeholder : OverlayChild

        /** The global Search overlay (#73): a real route over the same overlay primitive. */
        class Search(val component: SearchComponent) : OverlayChild

        /** The New create surface (#71): the kind picker + per-kind form, online-only create (ADR-0016). */
        class New(val component: NewComponent) : OverlayChild

        /** The in-app Help → Feedback surface (#375): comment + attachments, online-only submit. */
        class Feedback(val component: FeedbackComponent) : OverlayChild

        /**
         * The **Brain dump** surface (ADR-0027, #150; Stage 4): a voice recorder. It records the mic to a
         * WAV and hands it to the background worker on Stop; transcription + extraction run there and the
         * proposed drafts land in the [Destination.Inbox] for review (no inline review in the overlay).
         */
        class BrainDump(val component: BrainDumpComponent) : OverlayChild

        /**
         * The **Breakdown** surface (Deferno#525): the impediment-driven "what's stopping you?" flow over
         * one stuck item, launched from item detail. A deterministic engine + on-device classifier drive it
         * — shared Kotlin on the Android/desktop Compose chat, a native Swift twin on iOS — over this
         * [BreakdownComponent] holder the View renders.
         */
        class Breakdown(val component: BreakdownComponent) : OverlayChild
    }

    sealed interface Output {
        /** A Tasks "add to plan" intent, re-emitted for the host (the Plan write isn't the shell's job). */
        data class AddToPlanRequested(val id: TaskId) : Output

        /** A Profile "sign out" intent — the host secure-wipes the Active Account (ADR-0009/0012). */
        data object SignOutRequested : Output

        /** A Settings "App Permissions" tap — the host deep-links to the OS app-settings screen (#72). */
        data object OpenOsAppSettings : Output

        /** A Settings "Data & Privacy → export/import" tap — the host deep-links the web app (#72, AC #3). */
        data object OpenDataExportImport : Output

        /** A Settings "Security & 2FA" tap — the host opens the Zitadel console URL when present (#72). */
        data object OpenConsoleUrl : Output

        /** A Settings "Account → View profile" tap — switch laterally to the Profile Destination (#72). */
        data object OpenProfile : Output
    }
}

/** The shell-level overlay routes (ADR-0015): the v1 [Placeholder], plus [Search] (#73) and [New] (#71). */
sealed interface OverlayRoute {
    /** The trivial v1 placeholder so the overlay mechanism is wired and testable. */
    data object Placeholder : OverlayRoute

    /** The global Search route (#73) — launched from the ⌕ in any Destination app bar. */
    data object Search : OverlayRoute

    /**
     * The New create surface (#71): the FAB pushes this above the foreground Destination. [date]
     * pre-dates the form to a chosen day (the Calendar FAB, #74) — `null` opens an undated form.
     */
    data class New(val date: LocalDate? = null) : OverlayRoute

    /** The in-app Help → Feedback surface (#375), opened from Settings → Help & Feedback. */
    data object Feedback : OverlayRoute

    /** The **Brain dump** surface (ADR-0027), opened from the shell top bar's voice_chat action. */
    data object BrainDump : OverlayRoute

    /** The **Breakdown** surface (Deferno#525), opened from item detail's "Break this down" action over [taskId]. */
    data class Breakdown(val taskId: String) : OverlayRoute
}
