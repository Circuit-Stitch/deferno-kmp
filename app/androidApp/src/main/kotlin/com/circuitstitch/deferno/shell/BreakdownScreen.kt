package com.circuitstitch.deferno.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circuitstitch.deferno.DefernoApplication
import com.circuitstitch.deferno.core.agent.InferenceImpedimentClassifier
import com.circuitstitch.deferno.core.agent.hasGeneralPurposeEngine
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_composer_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_focus_prefix
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_keep_it
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_let_it_go
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_added_prerequisite
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_added_to_plan
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_broke_into
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_classifier_retry
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_confirm_drop
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_dropped
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_finished_dropped
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_finished_ready
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_kept_reask
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_more_urgent
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_name_first_piece
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_ready_to_go
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_transient_obstacle
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_waiting_dependency
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_msg_whats_stopping
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_prereq_define_done
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_prereq_figure_out
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_thinking
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_title
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_unavailable_body
import com.circuitstitch.deferno.core.designsystem.resources.breakdown_unavailable_title
import com.circuitstitch.deferno.core.designsystem.resources.common_close
import com.circuitstitch.deferno.core.designsystem.resources.common_done
import com.circuitstitch.deferno.core.designsystem.resources.common_send
import com.circuitstitch.deferno.core.designsystem.resources.tasks_menu_add_to_plan
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The Android **Breakdown** surface (Deferno#525) — the Compose render of the on-device impediment flow,
 * the platform twin of iOS's SwiftUI `BreakdownView`: *"what's stopping you?"* → the selected on-device
 * engine classifies the answer → the deterministic [BreakdownEngine] routes one concrete move → recurse →
 * stop when everything's Ready.
 *
 * The engine + its state live on the **retained** [BreakdownComponent] (so the conversation survives config
 * change), making this a thin renderer: it checks availability (the on-device classifier needs a
 * general-purpose engine — the cross-platform analogue of iOS's `AppleIntelligence.isAvailable`), then hands
 * the component the on-device classifier via [BreakdownComponent.bind] and renders the engine it builds.
 */
@Composable
fun BreakdownScreen(component: BreakdownComponent, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The AppScope inference engine + its catalog, sourced at the platform edge like BrainDumpScreen's mic
    // spectrum (the classifier is a device capability, not a per-Account seam — ADR-0027).
    val appComponent = remember(context) { (context.applicationContext as DefernoApplication).appComponent }

    // null = still probing; true/false = a capable engine is/ isn't selected (Settings → Agent).
    var available by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(appComponent) {
        available = appComponent.inferenceEngineCatalog.hasGeneralPurposeEngine()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            BreakdownHeader(onClose = component::onClose)
            when (available) {
                false -> BreakdownUnavailable(Modifier.weight(1f))
                true -> {
                    val classifier = remember(appComponent) {
                        InferenceImpedimentClassifier(appComponent.inferenceEngine)
                    }
                    // Idempotent: the component builds the retained engine once the row resolves. The two
                    // fallback prerequisite titles are *persisted* as server-synced subtasks, so they're
                    // resolved to the person's locale here (suspend getString) and injected at creation time.
                    LaunchedEffect(component, classifier) {
                        component.bind(
                            classifier,
                            BreakdownEngine.Prerequisites(
                                figureOutHow = getString(Res.string.breakdown_prereq_figure_out),
                                defineDone = getString(Res.string.breakdown_prereq_define_done),
                            ),
                        )
                    }
                    val engine by component.engine.collectAsStateWithLifecycle()
                    val built = engine
                    if (built != null) {
                        BreakdownChat(built, component, Modifier.weight(1f))
                    } else {
                        LoadingBody(Modifier.weight(1f)) // engine builds once the local row resolves
                    }
                }
                // Probing availability.
                null -> LoadingBody(Modifier.weight(1f))
            }
        }
    }
}

/** A compact top bar: a calm chevron-down "Close" at the start, the surface title centered (mirrors the iOS nav bar). */
@Composable
private fun BreakdownHeader(onClose: () -> Unit) {
    val close = stringResource(Res.string.common_close)
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = close, onClick = onClose)
                .padding(vertical = 4.dp, horizontal = 4.dp),
        ) {
            Icon(
                imageVector = DefernoIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.defernoColors.amberDeep,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(close, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.defernoColors.amberDeep)
        }
        Text(
            text = stringResource(Res.string.breakdown_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
    }
}

/** The unavailable state (parity with iOS): no capable engine is selected, so the flow can't run on-device. */
@Composable
private fun BreakdownUnavailable(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.breakdown_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(Res.string.breakdown_unavailable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * The chat itself — a pure render of the retained [engine]'s state. Reads are the engine's StateFlows;
 * writes go through the [component] (so they run on the retained component scope and survive config change).
 */
@Composable
private fun BreakdownChat(engine: BreakdownEngine, component: BreakdownComponent, modifier: Modifier = Modifier) {
    val messages by engine.messages.collectAsStateWithLifecycle()
    val phase by engine.phase.collectAsStateWithLifecycle()
    val focusTitle by engine.focusTitle.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        // The item currently in focus — it follows the recursion (root → each new part), so it's clear which
        // leaf "what's stopping you?" is about. Hidden at the terminal (focus is null).
        focusTitle?.let { title ->
            Text(
                text = stringResource(Res.string.breakdown_focus_prefix, title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.defernoColors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { Bubble(it) }
            if (phase is BreakdownEngine.Phase.Working) item { TypingIndicator() }
        }
        when (val p = phase) {
            BreakdownEngine.Phase.Asking, BreakdownEngine.Phase.Working ->
                Composer(enabled = p == BreakdownEngine.Phase.Asking, onSend = component::submit)

            BreakdownEngine.Phase.ConfirmingDrop ->
                DropConfirm(
                    onKeep = { component.confirmDrop(false) },
                    onLetGo = { component.confirmDrop(true) },
                )

            is BreakdownEngine.Phase.Finished ->
                FinishedBar(
                    outcome = p.outcome,
                    onAddToPlan = component::addRootToPlan,
                    onDone = component::onClose,
                )
        }
    }
}

/** One chat bubble: the person's answers on the right (accent), the assistant's prompts on the left. */
@Composable
private fun Bubble(message: BreakdownEngine.Message) {
    val isUser = message.role == BreakdownEngine.Role.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = messageText(message),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Resolves a typed engine [message] to its prose at render time: the person's own words verbatim
 * ([BreakdownEngine.MessageKind.UserText] — user content, never localized), everything else via its
 * localized `breakdown_msg_*` resource with the engine's args interpolated.
 */
@Composable
private fun messageText(message: BreakdownEngine.Message): String = when (message.kind) {
    BreakdownEngine.MessageKind.UserText -> message.arg.orEmpty()
    BreakdownEngine.MessageKind.WhatsStopping ->
        stringResource(Res.string.breakdown_msg_whats_stopping, message.arg.orEmpty())
    BreakdownEngine.MessageKind.BrokeInto ->
        stringResource(Res.string.breakdown_msg_broke_into, listed(message.args))
    BreakdownEngine.MessageKind.NameFirstPiece -> stringResource(Res.string.breakdown_msg_name_first_piece)
    BreakdownEngine.MessageKind.AddedPrerequisite ->
        stringResource(Res.string.breakdown_msg_added_prerequisite, message.arg.orEmpty())
    BreakdownEngine.MessageKind.ConfirmDrop ->
        stringResource(Res.string.breakdown_msg_confirm_drop, message.arg.orEmpty())
    BreakdownEngine.MessageKind.Dropped -> stringResource(Res.string.breakdown_msg_dropped, message.arg.orEmpty())
    BreakdownEngine.MessageKind.KeptReask ->
        stringResource(Res.string.breakdown_msg_kept_reask, message.arg.orEmpty())
    BreakdownEngine.MessageKind.TransientObstacle -> stringResource(Res.string.breakdown_msg_transient_obstacle)
    BreakdownEngine.MessageKind.MoreUrgent -> stringResource(Res.string.breakdown_msg_more_urgent)
    BreakdownEngine.MessageKind.WaitingOnDependency -> stringResource(Res.string.breakdown_msg_waiting_dependency)
    BreakdownEngine.MessageKind.ReadyToGo ->
        stringResource(Res.string.breakdown_msg_ready_to_go, message.arg.orEmpty())
    BreakdownEngine.MessageKind.AddedToPlan -> stringResource(Res.string.breakdown_msg_added_to_plan)
    BreakdownEngine.MessageKind.ClassifierRetry -> stringResource(Res.string.breakdown_msg_classifier_retry)
    BreakdownEngine.MessageKind.FinishedDropped -> stringResource(Res.string.breakdown_msg_finished_dropped)
    BreakdownEngine.MessageKind.FinishedReady -> stringResource(Res.string.breakdown_msg_finished_ready)
}

/** “A”, “B”, … — the ", " joiner stays hardcoded (locale-aware list formatting is a follow-up); the surrounding sentence is a resource. */
private fun listed(titles: List<String>): String = titles.joinToString(", ") { "“$it”" }

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.breakdown_thinking), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.defernoColors.inkMuted)
    }
}

/** The answer composer — a text field + Send, disabled (but visible) while the engine is classifying. */
@Composable
private fun Composer(enabled: Boolean, onSend: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val canSend = enabled && draft.isNotBlank()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            enabled = enabled,
            placeholder = { Text(stringResource(Res.string.breakdown_composer_placeholder)) },
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                val text = draft
                draft = ""
                onSend(text)
            },
            enabled = canSend,
        ) { Text(stringResource(Res.string.common_send)) }
    }
}

/** The one destructive move (PRD #26): an explicit yes/no before a drop, never a swipe-by. */
@Composable
private fun DropConfirm(onKeep: () -> Unit, onLetGo: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(onClick = onKeep, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.breakdown_keep_it)) }
        Button(onClick = onLetGo, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.breakdown_let_it_go)) }
    }
}

/** The terminal footer: a Ready item offers "Add to today's plan" (one-shot); both outcomes offer Done. */
@Composable
private fun FinishedBar(outcome: BreakdownEngine.Outcome, onAddToPlan: () -> Unit, onDone: () -> Unit) {
    var addedToPlan by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (outcome == BreakdownEngine.Outcome.Ready) {
            Button(
                onClick = {
                    addedToPlan = true
                    onAddToPlan()
                },
                enabled = !addedToPlan,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.tasks_menu_add_to_plan)) }
        }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.common_done)) }
    }
}
