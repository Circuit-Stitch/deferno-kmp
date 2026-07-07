package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.data.activity.ActivityLedgerStore
import com.circuitstitch.deferno.core.data.assistant.ConversationStore
import com.circuitstitch.deferno.core.data.attachment.LocalAttachmentRepository
import com.circuitstitch.deferno.core.data.backup.BackupExporter
import com.circuitstitch.deferno.core.data.backup.BackupImporter
import com.circuitstitch.deferno.core.data.braindump.BrainDumpDraftRepository
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.comment.CommentRepository
import com.circuitstitch.deferno.core.data.comment.CommentWriter
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.history.ItemHistoryRepository
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceLocalStore
import com.circuitstitch.deferno.core.data.outbox.OutboxProcessor
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.security.SecurityRepository
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.domain.command.CommandExecutor
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.scopes.AccountScope
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent.CreateComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The Active Account scope ([AccountScope], ADR-0002 / ADR-0008) — a child of [AppScope]. Takes
 * the [AppComponent] as a kotlin-inject `@Component` parent, so process-singleton bindings resolve
 * through it.
 *
 * Its real binding is the **Active [Account] this scope is bound to** (issue #14): supplied at
 * creation as a `@Provides` value, it is resolvable by every Account- and Scene-scoped binding —
 * "Account context resolvable per scene scope" (ADR-0008 G3). The window/scene graph is created
 * over the active Account, and a future second window can build a second [AccountComponent] over a
 * *different* Account while sharing the one [AppComponent], so this is a scope, not a hard global.
 *
 * Switching the Active Account re-points by tearing down + rebuilding this component for the new
 * Account — cheap, because presentation is scene-scoped and the data layer above (in [AppScope]) is
 * shared. The process-global `AccountManager` (the authority that produces the Active Account) is
 * an [AppScope] binding; wiring it as a contribution is deferred until its data-layer collaborators
 * have production implementations.
 */
@MergeComponent(AccountScope::class)
@SingleIn(AccountScope::class)
abstract class AccountComponent(
    @Component val app: AppComponent,
    @get:Provides val activeAccount: Account,
) {
    // Re-exposed from AppScope to show parent-scoped bindings resolve through a child.
    abstract val appScaffold: AppScaffold

    /**
     * The per-Account data layer (ADR-0014). The scene builds its ViewModels over these; the whole
     * subtree (this Account's encrypted DB + stores + repositories + outbox) is rebuilt when the
     * Active Account switches, because this component is disposed + recreated for the new Account.
     * Exposing them also anchors anvil's compile-time validation of the entire AccountScope chain
     * (DB → driver → key/Context/databasesDir, repositories → AppScope remote sources).
     */
    abstract val taskRepository: TaskRepository
    // Online-only Task detail extras (attachments); an AppScope binding resolved through here. Comments
    // moved to the offline-first [commentRepository] below (ADR-0043).
    abstract val taskDetailRepository: TaskDetailRepository
    abstract val planRepository: PlanRepository

    // The offline-first Task-comment thread + cached server item-history (ADR-0043): the Task detail's
    // ACTIVITY feed observes these from the cache and refreshes them best-effort on open. [commentWriter]
    // is the optimistic post/edit/delete seam (its writes ride the ledger via the outbox choke-point).
    abstract val commentRepository: CommentRepository
    abstract val itemHistoryRepository: ItemHistoryRepository
    abstract val commentWriter: CommentWriter

    /**
     * The cross-kind Item read (ADR-0034, #226/#227): the Tasks Item tree observes the windowed `/items`
     * set across all four kinds through this. The device-local [foldStore] is an AppScope binding resolved
     * through this child — the per-device expand/collapse overrides the tree (and the detail outline) share.
     */
    abstract val itemRepository: ItemRepository
    abstract val foldStore: ItemFoldStore

    /**
     * The user's settings — observed for the Settings Destination + the app-wide live theme (#72).
     * There is deliberately no `SettingsWriter` accessor: User-setting writes route through the
     * [commandExecutor] like every other write (#173, ADR-0007), which anchors the settings write
     * chain's compile-time validation.
     */
    abstract val settingsRepository: SettingsRepository

    /**
     * The Security & 2FA seam (Settings → Security): the first-party MFA management + connected-
     * devices calls, with the step-up cookie held per Account session (see AccountDataBindings —
     * AccountScope deliberately, so an Account switch discards the step-up freshness stamp).
     * Exposing it anchors anvil's compile-time validation and gives the shell its accessor.
     */
    abstract val securityRepository: SecurityRepository

    /**
     * The recurring-kind read seams (#71): the local stores are the read interface — reads are local
     * `Flow`s only (ADR-0001), and the former one-impl repository wrappers added nothing over them
     * (#171). Exposing the stores anchors anvil's compile-time validation of the create flow's
     * AccountScope chain (the CreateWriter seeds these same stores), and lets the shell observe a
     * freshly created Habit/Chore/Event like a Task.
     */
    abstract val habitLocalStore: HabitLocalStore
    abstract val choreLocalStore: ChoreLocalStore
    abstract val eventLocalStore: EventLocalStore

    /**
     * The Occurrence (firing-level) read seam (#71 AC #4, #171): observe-only over the local cache,
     * like the recurring-definition stores. Exposing it anchors anvil's compile-time validation of
     * the Occurrence chain (store → DB).
     */
    abstract val occurrenceLocalStore: OccurrenceLocalStore

    /**
     * The Calendar feed read repository (#74): the windowed month grid + day agenda source, observed
     * from the local cache. Exposing it anchors anvil's compile-time validation of the calendar chain
     * (store + series-kind index → DB, repo → AppScope feed source) and gives the shell its accessor.
     */
    abstract val calendarRepository: CalendarRepository

    /**
     * The on-device Brain dump draft store (ADR-0027): local-only persisted draft Tasks the worker
     * writes and the Brain dumps Destination observes + accepts/dismisses. Exposing it anchors anvil's
     * compile-time validation of its AccountScope chain (repository → DB).
     */
    abstract val brainDumpDraftRepository: BrainDumpDraftRepository

    /**
     * The on-device attachment store (#210): per-Account attachment records + their bytes (via the AppScope
     * [com.circuitstitch.deferno.core.data.attachment.AttachmentBytesStore]). Exposing it anchors anvil's
     * compile-time validation of its AccountScope chain (repository → DB + the re-exposed AppScope byte store)
     * and readies the consumer (#211 audio retention); feedback attachments never use it (always backend).
     */
    abstract val localAttachmentRepository: LocalAttachmentRepository

    /**
     * The on-device export engine (#313, ADR-0041): the Backup-zip builder over this Account's four local
     * stores. Exposing it anchors anvil's compile-time validation of its AccountScope chain (stores → DB)
     * and gives the shell its accessor (the AccountComponentSession surfaces buildBackupZip from it).
     */
    abstract val backupExporter: BackupExporter

    /**
     * The on-device import/restore engine (#314, ADR-0041): replays a Backup zip's items as id-preserving
     * creates on this Account's outbox. The inverse of [backupExporter]; the AccountComponentSession
     * surfaces importBackup from it.
     */
    abstract val backupImporter: BackupImporter

    /** The command-registry dispatch site (ADR-0007) over this Account's write seams. */
    abstract val commandExecutor: CommandExecutor

    /** The offline outbox replay engine (#23) for this Account; the app drives [OutboxProcessor.flush]. */
    abstract val outboxProcessor: OutboxProcessor

    /**
     * The offline-first activity ledger (#260): the durable, append-only journal of every applied write,
     * read reverse-chronologically by the Activity Destination. Exposing it anchors anvil's compile-time
     * validation of its AccountScope chain (store → DB) and gives the shell its read accessor.
     */
    abstract val activityLedgerStore: ActivityLedgerStore

    /**
     * The on-device [[Assistant]] Conversation cache (#282, ADR-0040): local-only persisted chat history
     * the component writes as a turn streams and reads back offline (the switcher list + message log).
     * Exposing it anchors anvil's compile-time validation of its AccountScope chain (store → DB) and gives
     * the shell its read/write accessor (the SSE turn stream + request client live elsewhere).
     */
    abstract val conversationStore: ConversationStore
}

@CreateComponent
expect fun createAccountComponent(app: AppComponent, activeAccount: Account): AccountComponent
