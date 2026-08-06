import Deferno
import Foundation

/// Localized strings for the native macOS SwiftUI surfaces (#327), backed by `Localizable.xcstrings`.
/// The 5-locale Compose catalog (en/es/de/hi/pt) is the source of truth; its subset the bridges carry is
/// ported here. The shell hands Swift **typed** state (ADR-0003): the bridge accessors return stable
/// enum-name tokens (the Kotlin twins — `ChromeTitle`, `NewStatus.FailedReason`,
/// `FeedbackResult.Failed.Reason`, `InboxNote`, `AssistantError`, `InferenceFailureReason`), and these
/// helpers map each token to a catalog key. Server-authored prose (feedback / inbox / assistant
/// `ServerMessage`) renders verbatim.
enum L {

    /// A localized string by catalog key.
    static func string(_ key: String) -> String {
        Bundle.main.localizedString(forKey: key, value: key, table: nil)
    }

    /// A localized format string filled with positional args (`%@`, `%lld`).
    static func format(_ key: String, _ args: CVarArg...) -> String {
        String(format: string(key), locale: .current, arguments: args)
    }

    /// A localized, plural-agreed count string (backed by the catalog's `plural` variations).
    static func plural(_ key: String, _ count: Int) -> String {
        String.localizedStringWithFormat(string(key), count)
    }


    /// A localized Item-kind label from its bridge enum name ("Task" → common_kind_task).
    static func kindLabel(_ name: String) -> String {
        switch name {
        case "Task": return string("common_kind_task")
        case "Chore": return string("common_kind_chore")
        case "Habit": return string("common_kind_habit")
        case "Event": return string("common_kind_event")
        default: return name
        }
    }

    // MARK: Chrome title (typed ChromeTitle)

    /// The top-bar title for a `ChromeSpec`: user-authored text renders verbatim; a Destination /
    /// Settings-category screen name and the Task-detail fallback localize; no title → empty.
    static func chromeTitle(_ spec: ChromeSpec) -> String {
        if let verbatim = ShellBridgeKt.chromeTitleVerbatim(spec: spec) { return verbatim }
        if let destination = ShellBridgeKt.chromeTitleDestination(spec: spec) {
            return destinationLabel(ShellBridgeKt.destinationName(destination: destination))
        }
        if let category = ShellBridgeKt.chromeTitleSettingsCategory(spec: spec) {
            return settingsCategoryLabel(ShellBridgeKt.settingsCategoryName(category: category))
        }
        if ShellBridgeKt.chromeTitleIsTaskFallback(spec: spec) { return string("common_kind_task") }
        return ""
    }

    /// A Destination's nav/chrome label, keyed off its stable enum name ("Plan" → shell_destination_plan).
    static func destinationLabel(_ name: String) -> String {
        string("shell_destination_" + name.lowercased())
    }

    /// A Settings category's chrome label, keyed off its stable enum name.
    static func settingsCategoryLabel(_ name: String) -> String {
        let key: String
        switch name {
        case "Appearance": key = "settings_category_appearance"
        case "TaskBehavior": key = "settings_category_task_behavior"
        case "SpeechEngine": key = "settings_category_speech_engine"
        case "Agent": key = "settings_category_agent"
        case "Assistant": key = "settings_category_assistant"
        case "Storage": key = "settings_category_storage"
        case "DataPrivacy": key = "settings_category_data_privacy"
        case "HelpFeedback": key = "settings_category_help_feedback"
        case "AppPermissions": key = "settings_category_app_permissions"
        case "Legal": key = "settings_category_legal"
        case "Account": key = "settings_category_account"
        case "Security2FA": key = "settings_category_security_2fa"
        case "Integrations": key = "settings_category_integrations"
        default: key = "shell_destination_settings"
        }
        return string(key)
    }

    // MARK: Activity feed (typed ActivitySummary / ActivitySource)

    /// The localized one-liner for an Activity row, from its typed verb + optional item-kind token. When
    /// the item ref resolved (`row.itemRef`, e.g. "#41") the ref-capable verbs read "Updated task #41";
    /// otherwise the plain fallback. Mirrors the Compose `ActivityFeedRow.summaryText`.
    static func activitySummary(_ row: ActivityFeedRow) -> String {
        let verb = ShellBridgeKt.activitySummaryVerb(row: row)
        let kind = ShellBridgeKt.activitySummaryKindToken(row: row)
        func s(_ plain: String, _ refKey: String) -> String {
            if let itemRef = row.itemRef { return format(refKey, itemRef) }
            return string(plain)
        }
        switch verb {
        case "ChangedSettings": return string("activity_summary_changed_settings")
        case "Created":
            switch kind {
            case "task": return s("activity_summary_created_task", "activity_summary_created_task_ref")
            case "chore": return s("activity_summary_created_chore", "activity_summary_created_chore_ref")
            case "habit": return s("activity_summary_created_habit", "activity_summary_created_habit_ref")
            case "event": return s("activity_summary_created_event", "activity_summary_created_event_ref")
            default: return s("activity_summary_created_item", "activity_summary_created_item_ref")
            }
        case "MovedItem": return s("activity_summary_moved_item", "activity_summary_moved_item_ref")
        case "UpdatedPlan": return string("activity_summary_updated_plan")
        case "DeletedTask": return string("activity_summary_deleted_task")
        case "UpdatedTask": return s("activity_summary_updated_task", "activity_summary_updated_task_ref")
        case "ClearedOccurrence":
            switch kind {
            case "chore": return string("activity_summary_cleared_occurrence_chore")
            case "habit": return string("activity_summary_cleared_occurrence_habit")
            default: return string("activity_summary_cleared_occurrence_event")
            }
        case "UpdatedOccurrence":
            switch kind {
            case "chore": return string("activity_summary_updated_occurrence_chore")
            case "habit": return string("activity_summary_updated_occurrence_habit")
            default: return string("activity_summary_updated_occurrence_event")
            }
        // Comment rows previously fell through to "Updated an item" — now their own line, with the ref.
        case "Commented": return s("activity_summary_commented", "activity_summary_commented_ref")
        // The server ledger's own vocabulary (#364). None is ref-capable: these arrive on rows from
        // other surfaces, where the touched item is often not in this device's cache at all.
        case "StatusChanged": return string("activity_summary_status_changed")
        case "DeletedItem": return string("activity_summary_deleted_item")
        case "Split": return string("activity_summary_split")
        case "Merged": return string("activity_summary_merged")
        case "Converted": return string("activity_summary_converted")
        case "Rescheduled": return string("activity_summary_rescheduled")
        case "CommentEdited": return string("activity_summary_comment_edited")
        case "CommentDeleted": return string("activity_summary_comment_deleted")
        case "AttachmentAdded": return string("activity_summary_attachment_added")
        case "AttachmentDeleted": return string("activity_summary_attachment_deleted")
        case "AttachmentCaptioned": return string("activity_summary_attachment_captioned")
        case "PlanAdded": return string("activity_summary_plan_added")
        case "PlanRemoved": return string("activity_summary_plan_removed")
        case "PlanReordered": return string("activity_summary_plan_reordered")
        // Kotlin's ActivityVerb `when` is exhaustive, so a verb added there is a compile error on
        // Compose but would silently land here — this arm is the safety net, not the design.
        default: return string("activity_summary_updated_item")
        }
    }

    /// The localized "who" chip for an Activity row — a flat map over the already-decided attribution.
    ///
    /// Whether the server's actor or the acting surface names a row is settled once, in the shared
    /// `ActivityAttribution` (#364), and reaches here as a single token. This deliberately does NOT
    /// re-derive it from actor kind + source: that rule used to live here *and* in Compose, two copies of
    /// one decision that nothing would have caught drifting apart.
    static func activitySource(_ row: ActivityFeedRow) -> String {
        switch ShellBridgeKt.activityAttributionToken(row: row) {
        case "Assistant": return string("activity_actor_assistant")
        case "Integration":
            return ShellBridgeKt.activityAttributionProvider(row: row) ?? string("activity_actor_integration")
        case "Mobile": return string("activity_source_mobile")
        case "Website": return string("activity_source_website")
        case "Mcp": return string("activity_source_mcp")
        case "Api": return string("activity_source_api")
        case "System": return string("activity_source_system")
        default: return string("activity_source_unknown")
        }
    }

    // MARK: New / Feedback failure (typed reasons)

    /// The localized create-failure note, or nil when New isn't in a Failed state.
    static func newFailure(_ state: NewState) -> String? {
        guard let reason = ShellBridgeKt.doNewStatusFailedReason(state: state) else { return nil }
        switch reason {
        case "CouldNotSave": return string("new_error_could_not_save")
        default: return string("new_error_could_not_save_retry")
        }
    }

    /// The localized send-failure note, or nil when Feedback isn't in a Failed state. The server-authored
    /// `ServerMessage` arm renders verbatim; upload/send codes fill their format.
    static func feedbackFailure(_ state: FeedbackState) -> String? {
        guard let reason = ShellBridgeKt.feedbackStatusFailedReason(state: state) else { return nil }
        let code = Int(ShellBridgeKt.feedbackStatusFailedStatusCode(state: state))
        switch reason {
        case "PrepareAttachments": return string("feedback_error_presign_failed")
        case "UploadFailed": return format("feedback_error_upload_failed", code)
        case "SendFailed": return format("feedback_error_send_failed", code)
        case "AppOutOfDate": return string("common_error_app_out_of_date")
        default: return ShellBridgeKt.feedbackStatusFailedMessage(state: state)
        }
    }

    // MARK: Inbox note (typed InboxNote)

    /// The gentle row note: the fixed Offline arm localizes, a server message renders verbatim, else nil.
    static func inboxNote(_ row: InboxRow) -> String? {
        if ShellBridgeKt.inboxNoteIsOffline(row: row) { return string("common_reconnect_to_save") }
        return ShellBridgeKt.inboxNoteServerMessage(row: row)
    }

    // MARK: Assistant error (typed AssistantError)

    /// The localized turn-error banner, or nil when there's no error. The server `ServerMessage` arm
    /// renders verbatim.
    static func assistantError(_ state: AssistantState) -> String? {
        guard let kind = ShellBridgeKt.assistantErrorKind(state: state) else { return nil }
        switch kind {
        case "TurnFailed": return string("assistant_error_turn_failed")
        case "EnableFailed": return string("assistant_error_enable_failed")
        case "ApplyFailed": return string("assistant_error_apply_failed")
        default: return ShellBridgeKt.assistantErrorServerMessage(state: state)
        }
    }

    // MARK: Move / undo (typed MoveOperation)

    /// The localized move-operation noun ("reorder" / "indent" / "outdent" token → localized word).
    static func moveOperation(_ token: String) -> String {
        switch token {
        case "reorder": return string("tasks_move_operation_reorder")
        case "indent": return string("tasks_move_operation_indent")
        case "outdent": return string("tasks_move_operation_outdent")
        default: return token
        }
    }

    // MARK: Draft-extract failure (typed InferenceResult.Failure reason, #327)

    /// The localized draft-extraction failure note for a typed `InferenceFailureReason` token. The raw
    /// `detail` is a content-free log string — kept out of the UI (it stays for logs only).
    static func draftExtractError(_ reason: String) -> String {
        switch reason {
        case "NotConfigured": return string("draft_extract_error_not_configured")
        case "MalformedOutput": return string("draft_extract_error_malformed")
        default: return string("draft_extract_error_transport")
        }
    }

    // MARK: Journey status + relative day (typed tokens, ADR-0044)

    /// The localized journey-vocabulary label for a token from `BridgeKt.journeyLabelToken(task:)`
    /// ("TODO"/"IN_PROGRESS"/…) → a `tasks_journey_*` string. Display-only vocabulary; never a state name.
    static func journeyLabel(_ token: String) -> String {
        switch token {
        case "TODO": return string("tasks_journey_todo")
        case "IN_PROGRESS": return string("tasks_journey_in_progress")
        case "IN_REVIEW": return string("tasks_journey_in_review")
        case "DONE": return string("tasks_journey_done")
        case "NOT_DOING": return string("tasks_journey_not_doing")
        case "BLOCKED": return string("tasks_journey_blocked")
        default: return token
        }
    }

    /// The localized WHEN-row relative-day suffix for a token from `BridgeKt.taskDueRelativeToken(task:)`
    /// + its count. TODAY/TOMORROW/YESTERDAY are the singular keys; DAYS_AWAY/DAYS_AGO are the plural keys.
    static func relativeDay(_ token: String, _ n: Int) -> String {
        switch token {
        case "TODAY": return string("tasks_detail_due_today")
        case "TOMORROW": return string("tasks_detail_due_tomorrow")
        case "YESTERDAY": return string("tasks_detail_due_yesterday")
        case "DAYS_AWAY": return plural("tasks_detail_due_days_away", n)
        case "DAYS_AGO": return plural("tasks_detail_due_days_ago", n)
        default: return ""
        }
    }

    // MARK: Recurrence cadence / bound / cursor (typed tokens — #384)
    //
    // Every rule these four functions could disagree about — the `EveryNDays(1)` fold, the interval floor,
    // which weekday tokens survive and in what ORDER, which wire parameters never reach a row — is settled
    // before it gets here, by `Item.recurrenceReading` in :feature:tasks, which the Compose row reads too.
    // What is left below is phrasing: a token picks a catalog key. Do not add arithmetic here — a rule
    // written in Swift is a rule the other three platforms don't have.

    /// The **cadence** phrase — "Daily", "Every 3 days", "Weekly on Mon, Wed", "Monthly", "Every 2 years",
    /// "Custom schedule" — paired with whether that phrase is *already a verb*, which is the one thing
    /// [recurrenceLine] must know before wrapping it in the screen-reader prefix.
    ///
    /// The paired counts are **optional**, and each `guard` below states what happens without one rather
    /// than defaulting a number into a sentence. By construction the count is present for exactly the three
    /// arms that read it; if that ever stops being true, the vaguer-but-true "Repeats" beats inventing a
    /// stride. Uses: tasks_cadence_daily, tasks_cadence_every_n_days, tasks_cadence_weekly,
    /// tasks_cadence_weekly_on, tasks_cadence_monthly, tasks_cadence_yearly, tasks_cadence_custom,
    /// tasks_cadence_unknown.
    static func cadence(_ tokens: RecurrenceLineTokens) -> (phrase: String, isBareVerb: Bool) {
        let count = tokens.cadenceCount?.intValue
        switch tokens.cadence {
        case "DAILY": return (string("tasks_cadence_daily"), false)
        // `EveryNDays(1)` never arrives — the reading folds it into DAILY, which is what it means and what
        // several locales insist on (de drops the numeral, so "Every 1 day" isn't grammatical there at all).
        case "EVERY_N_DAYS":
            guard let count else { break }
            return (plural("tasks_cadence_every_n_days", count), false)
        case "WEEKLY": return (weeklyPhrase(tokens.weekdays), false)
        // An interval of 1 reads as the plain adverb — and that IS each plural's `one` arm, so the catalog
        // does the normalising and there is nothing to special-case here.
        case "MONTHLY":
            guard let count else { break }
            return (plural("tasks_cadence_monthly", count), false)
        case "YEARLY":
            guard let count else { break }
            return (plural("tasks_cadence_yearly", count), false)
        // Never the raw rrule: it is machine text, and paraphrasing it would be a guess dressed as a fact.
        case "CUSTOM": return (string("tasks_cadence_custom"), false)
        default: break
        }
        // UNSPECIFIED — plus any cadence a future backend adds that this build has never heard of, and the
        // count-less arms above. All land on the same deliberately-vague verb phrase ("Repeats"), and all
        // must SKIP the a11y prefix: wrapping it speaks "Repeats Repeats". That rule is written on
        // tasks_cadence_unknown itself, and returning the flag from here is what keeps the two in step.
        return (string("tasks_cadence_unknown"), true)
    }

    /// "Weekly on Mon, Wed" — or the bare "Weekly" when the day list is empty, which is exactly the reading
    /// `tasks_cadence_weekly` exists for (the shared reading empties it when no token could be placed).
    ///
    /// `isoDays` are ISO day numbers, 1 = Monday … 7 = Sunday, already deduped and in week order. The day
    /// NAMES come from CLDR (`Calendar.shortWeekdaySymbols`), never a hand-rolled per-locale table
    /// (CLAUDE.md) — that array is **Sunday-first** (index 0 = Sunday) for every locale and calendar
    /// identifier, which is a property of the symbol array and not of the user's `firstWeekday`, so ISO
    /// modulo 7 lands each day on its own symbol. Deriving an offset from `firstWeekday` instead would
    /// silently rotate every label for anyone whose week starts on Monday.
    ///
    /// The joiner is the catalog's `tasks_cadence_weekday_separator` rather than `ListFormatter`: a list
    /// formatter inserts a conjunction ("Mon, Wed, and Fri") that the Compose twin — which has no
    /// ListFormatter and joins on that same key — could never produce, and this line is specified to read
    /// identically on all four platforms.
    private static func weeklyPhrase(_ isoDays: [KotlinInt]) -> String {
        let symbols = Calendar.current.shortWeekdaySymbols
        guard symbols.count == 7, !isoDays.isEmpty else { return string("tasks_cadence_weekly") }
        let joined = isoDays
            .map { symbols[$0.intValue % 7] }
            .joined(separator: string("tasks_cadence_weekday_separator"))
        return format("tasks_cadence_weekly_on", joined)
    }

    /// The end-**bound** phrase — "until Jun 14, 2026" / "10 times" — or nil for an open-ended rule, the
    /// default, which says nothing at all rather than a word announcing that it is unbounded. Lowercase,
    /// because it always follows a cadence inside `tasks_cadence_with_bound`. A bound whose paired value is
    /// missing renders nothing too: a clause is optional here, so dropping it is a real answer rather than
    /// a fallback. Uses: tasks_cadence_until, tasks_cadence_times.
    static func cadenceBound(_ tokens: RecurrenceLineTokens) -> String? {
        switch tokens.bound {
        case "ON_DATE":
            guard let epochDays = tokens.boundEpochDays?.intValue else { return nil }
            return format("tasks_cadence_until", boundDay(epochDays))
        case "AFTER_COUNT":
            guard let count = tokens.boundCount?.intValue else { return nil }
            return plural("tasks_cadence_times", count)
        default: return nil
        }
    }

    /// An `ON_DATE` bound's day (epoch days, from the reading) as a locale day — "Jun 14, 2026" in en-US,
    /// "14 juin 2026" in fr-FR.
    ///
    /// **Read in UTC, deliberately.** The value is a calendar DAY with no clock and no zone; this
    /// reconstructs UTC midnight, so formatting it in the device zone would print the day before for every
    /// user west of Greenwich. `Foundation.TimeZone` is spelled out because the Deferno framework exports
    /// kotlinx-datetime's `TimeZone` under that same Swift name.
    private static func boundDay(_ epochDays: Int) -> String {
        boundDayFormatter.string(from: Date(timeIntervalSince1970: Double(epochDays) * 86_400))
    }

    /// Cached so a tree row's `body` never allocates a `DateFormatter`. The `yMMMd` CLDR **skeleton**, not
    /// a hand-written pattern: field order and separators are the locale's to decide, not ours. It is the
    /// Apple-side twin of the `settings_security_device_date_pattern` ("MMM d, yyyy") the Compose row reads
    /// — the year earns its place on an `UNTIL` that can sit years out — expressed as a skeleton because
    /// this side has no pattern resource to read and inventing one would be a hardcoded format string.
    private static let boundDayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.timeZone = Foundation.TimeZone(identifier: "UTC")
        formatter.setLocalizedDateFormatFromTemplate("yMMMd")
        return formatter
    }()

    /// The next-due phrase: "Next: Tomorrow" for a live series, "Series ended" for one that hit its bound,
    /// nil for no cursor at all (a Task, or an Archived definition whose stale cursor the reading
    /// deliberately refuses to believe).
    ///
    /// The five relative-day arms are the SAME tokens the Task-detail WHEN row uses, so they pass straight
    /// through [relativeDay] and the `tasks_detail_due_*` keys instead of being re-mapped here. A cursor
    /// pointing *backwards* is normal rather than corrupt — a missed Habit's cursor sits where it stopped
    /// advancing — so "3 days ago" is an honest reading, not an error state. "Series ended" stays factual
    /// for the same reason: the server also reports it when its 400-day lookahead finds nothing, so a rare
    /// enough rule can read as ended while still being live. Uses: tasks_recurrence_series_ended,
    /// tasks_recurrence_next_due.
    static func cursor(_ tokens: RecurrenceLineTokens) -> String? {
        guard let day = cursorDay(tokens) else { return nil }
        // EXHAUSTED is already a whole clause ("Series ended"), not a day, so it must NOT take the
        // "Next: %@" wrapper — that renders "Next: Series ended", which contradicts itself. This guard
        // used to be an early return inside `cursor`; splitting `cursorDay` out for #383's detail row
        // left it below the wrapper and the tree row said exactly that, in all five locales. The iOS
        // twin kept it (`Localization.swift`, same line) — keep the two shaped identically.
        if tokens.cursor == "EXHAUSTED" { return day }
        return format("tasks_recurrence_next_due", day)
    }

    /// The next-due **day on its own** — "Tomorrow", "3 days ago", "Series ended" — without the
    /// `tasks_recurrence_next_due` ("Next: %@") wrapper [cursor] puts around it.
    ///
    /// Split out for #383's recurring detail, whose NEXT DUE row already carries
    /// `tasks_detail_property_next_due` as its label: wrapping there would read "Next due — Next:
    /// Tomorrow". The tree row still wants the wrapper, because its cadence subtitle is one running line
    /// with no labels in it. Same reading either way, phrased for its frame — which is why this is one
    /// function with two wrappers rather than two mappings of the same six tokens.
    static func cursorDay(_ tokens: RecurrenceLineTokens) -> String? {
        guard let token = tokens.cursor else { return nil }
        if token == "EXHAUSTED" { return string("tasks_recurrence_series_ended") }
        let days = tokens.cursorCount?.intValue
        // DAYS_AWAY/DAYS_AGO are the only arms that read a count, and it is non-nil for exactly those. If
        // one ever arrives without it there is no honest number to say, so drop the clause entirely rather
        // than let a placeholder render "in 0 days" — the cadence still carries the row.
        if days == nil, token == "DAYS_AWAY" || token == "DAYS_AGO" { return nil }
        return relativeDay(token, days ?? 0)
    }

    /// The whole recurrence subtitle for an Item-tree row (#384) — the visible line and its VoiceOver
    /// reading — or nil when the item carries no rule at all (every Task, and a recurring definition whose
    /// rule did not survive the wire). Assembled here, once, because the composition carries every trap:
    ///
    /// - the spoken reading wraps only the CADENCE in `tasks_recurrence_a11y_prefix`, and skips it for the
    ///   bare-verb arm, which is already "Repeats" and would otherwise speak "Repeats Repeats";
    /// - `tasks_cadence_with_bound` ("%1$@ · %2$@") is the only joiner this vocabulary has, so it carries
    ///   both joins — cadence to bound, and that whole phrase to the next-due reading;
    /// - the cadence always shows once a rule exists, while the bound and the cursor each independently may
    ///   not, so the line degrades to a bare "Weekly" without leaving a dangling separator behind.
    ///
    /// **One bridge crossing per row.** `recurrenceLineTokens` returns every piece as a single value, so the
    /// cadence and the cursor are each derived once. The shape this replaced was eight paired getters that
    /// re-derived both from scratch on every call — per row, per frame, in a scrolling tree.
    ///
    /// The row hides the visible line from VoiceOver and speaks `spoken` as part of the title's label: a
    /// muted mono line looks decorative but is load-bearing, and it is far too easy to swipe past.
    static func recurrenceLine(_ item: Item) -> (text: String, spoken: String)? {
        guard let tokens = RecurrenceReadingKt.recurrenceLineTokens(item: item) else { return nil }
        let read = cadence(tokens)
        var text = read.phrase
        var spoken = read.isBareVerb ? read.phrase : format("tasks_recurrence_a11y_prefix", read.phrase)
        func append(_ part: String?) {
            guard let part else { return }
            text = format("tasks_cadence_with_bound", text, part)
            spoken = format("tasks_cadence_with_bound", spoken, part)
        }
        append(cadenceBound(tokens))
        append(cursor(tokens))
        return (text, spoken)
    }

    // MARK: Trail — enriched history line + change diff (ADR-0046)

    /// The enriched Trail history label from a bridged `HistoryLine` — mirrors Kotlin `historyLabel()`.
    /// Uses: activity_history_status_transition, activity_history_{split,moved,parent,folded,merged}_peer,
    /// activity_history_peer_unknown, activity_history_merged_into_parent, activity_history_created,
    /// activity_history_unknown (+ activity_history_updated[_fields] via historyUpdated).
    static func historyEnriched(_ line: HistoryLine) -> String {
        switch line.verb {
        case "STATUS_CHANGED":
            let from = line.statusFrom?.label ?? string("activity_history_unknown")
            let to = line.statusTo?.label ?? string("activity_history_unknown")
            return format("activity_history_status_transition", from, to)
        case "SPLIT":            return peerLine("activity_history_split_peer", line.peerTitle)
        case "MOVED":            return peerLine("activity_history_moved_peer", line.peerTitle)
        case "PARENT_ASSIGNED":  return peerLine("activity_history_parent_peer", line.peerTitle)
        case "FOLDED_INTO":      return peerLine("activity_history_folded_peer", line.peerTitle)
        case "MERGED_CHILD":     return peerLine("activity_history_merged_peer", line.peerTitle)
        case "MERGED_INTO_PARENT": return string("activity_history_merged_into_parent")
        case "UPDATED":          return historyUpdated(line.changedFields, generic: line.updatedIsGeneric)
        case "CREATED":          return string("activity_history_created")
        default:                 return string("activity_history_unknown")
        }
    }

    private static func peerLine(_ key: String, _ peer: String?) -> String {
        format(key, peer ?? string("activity_history_peer_unknown"))
    }

    /// The UPDATED-fields summary — mirrors Kotlin `updatedLabel()`. Uses: activity_history_updated,
    /// activity_history_updated_fields (+ diffFieldLabel for each token).
    static func historyUpdated(_ fieldTokens: [String], generic: Bool) -> String {
        if generic || fieldTokens.isEmpty { return string("activity_history_updated") }
        let names = fieldTokens.map { diffFieldLabel($0) }.joined(separator: ", ")
        return format("activity_history_updated_fields", names)
    }

    /// A diff-row / changed-field label from a field token — mirrors `ActivityDiffFormat.activityFieldLabel`.
    /// DESCRIPTION deliberately reads `new_notes_label` (not an `activity_field_*` key) to match Compose.
    /// Uses: activity_field_title, new_notes_label, activity_field_deadline, common_labels,
    /// activity_field_status, activity_field_pinned, activity_field_target_date, activity_field_priority.
    static func diffFieldLabel(_ token: String) -> String {
        switch token {
        case "TITLE":       return string("activity_field_title")
        case "DESCRIPTION": return string("new_notes_label")
        case "DEADLINE":    return string("activity_field_deadline")
        case "LABELS":      return string("common_labels")
        case "STATUS":      return string("activity_field_status")
        case "PINNED":      return string("activity_field_pinned")
        // The soft target reads as its own field, never "deadline" (#375) — the Trail must not report a
        // deadline change for an edit that never touched `complete_by`.
        case "TARGET_DATE": return string("activity_field_target_date")
        case "PRIORITY":    return string("activity_field_priority")
        default:            return token
        }
    }

    /// The resolved display text for one diff side — mirrors `toDiffValue`/`formatFieldValue`. CLEARED /
    /// UNAVAILABLE render a word (never struck); PRESENT is formatted per field. DEADLINE and TARGET_DATE are
    /// parsed + formatted Swift-side (see `TrailDateFormat`); STATUS reuses the wire-token status label,
    /// PRIORITY the wire-token bucket label; PINNED = yes/no. Uses: activity_diff_value_cleared,
    /// activity_diff_value_unavailable, activity_value_pinned, activity_value_unpinned (+ statusWireLabel,
    /// priorityWireLabel).
    static func diffValueText(fieldToken: String, side: TrailDiffSide) -> String {
        switch side.kind {
        case "CLEARED":     return string("activity_diff_value_cleared")
        case "UNAVAILABLE": return string("activity_diff_value_unavailable")
        default:            break // PRESENT
        }
        let raw = side.value ?? ""
        switch fieldToken {
        // Both are captured as instants, so both render through the one instant formatter. They stay
        // separate *fields* (see diffFieldLabel) — they just share a value shape.
        case "DEADLINE", "TARGET_DATE": return TrailDateFormat.instantValue(raw)
        case "STATUS":   return statusWireLabel(raw)
        case "PRIORITY": return priorityWireLabel(raw)
        case "PINNED":   return raw == "true" ? string("activity_value_pinned")
                                              : string("activity_value_unpinned")
        default:         return raw
        }
    }

    /// A wire status token → localized label (the diff-row STATUS value). Reuses the same keys as the
    /// shipping WorkingState.label so the vocabulary matches. Uses: tasks_menu_open,
    /// common_status_in_progress, common_status_in_review, calendar_action_done, tasks_set_aside.
    static func statusWireLabel(_ token: String) -> String {
        switch token {
        case "open":        return string("tasks_menu_open")
        case "in-progress": return string("common_status_in_progress")
        case "in-review":   return string("common_status_in_review")
        case "done":        return string("calendar_action_done")
        case "dropped":     return string("tasks_set_aside")
        default:            return token
        }
    }

    /// A wire priority token → localized bucket label (the diff-row PRIORITY value, #375). The Swift twin of
    /// Compose `activityPriorityLabel`, reusing the same words the detail row shows; an unrecognised token
    /// degrades to itself. Uses: common_priority_fire, common_priority_normal, common_priority_backlog.
    static func priorityWireLabel(_ token: String) -> String {
        switch token {
        case "fire":    return string("common_priority_fire")
        case "normal":  return string("common_priority_normal")
        case "backlog": return string("common_priority_backlog")
        default:        return token
        }
    }
}
