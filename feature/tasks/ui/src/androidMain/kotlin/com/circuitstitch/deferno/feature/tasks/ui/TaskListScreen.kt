package com.circuitstitch.deferno.feature.tasks.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.circuitstitch.deferno.feature.tasks.ItemTreeComponent
import com.circuitstitch.deferno.feature.tasks.ShakeOutcome
import kotlin.math.sqrt

/**
 * The Tasks primary pane (ADR-0034, #227): the nested, collapsible **Item tree** across all kinds. A thin
 * renderer of [ItemTreeComponent] — it observes [ItemTreeComponent.state] and forwards toggle/open/refresh
 * to it, holding no logic (the row-state logic lives in `buildItemTree`, the rendering in [ItemTreeContent]).
 *
 * Undo (ADR-0034 decision 8, #230): a **top-anchored** "Moved · Undo" snackbar on a structural move
 * (reparent / indent / outdent — not a plain reorder), a persistent "Undo move" menu entry, and
 * shake-to-undo (a confirm prompt, the accidental-fire safety). All three revert through the same single
 * [ItemTreeComponent.undoLastMove] path.
 */
@Composable
fun TaskListScreen(
    component: ItemTreeComponent,
    modifier: Modifier = Modifier,
    // The "See the trees" header affordances (#231): the read-only search bar opens the global Search
    // overlay, "Add a tree" starts a new item. Both are shell concerns the ItemTreeComponent doesn't own,
    // so they're threaded in with no-op defaults — the integrator wires them from the shell.
    onSearch: () -> Unit = {},
    onAdd: () -> Unit = {},
    // Hoisted so the shell can dock a compact search into the top bar once this scrolls off (#…).
    listState: LazyListState = rememberLazyListState(),
) {
    val state by component.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // The top "Moved · Undo" snackbar fires once per *structural* move (keyed on the move token so two
    // indents in a row each raise it); a plain reorder records an undoable but shows no snackbar (#230).
    LaunchedEffect(state.lastMove?.takeIf { it.structural }?.id) {
        if (state.lastMove?.structural != true) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Moved",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) component.undoLastMove()
    }

    // Shake-to-undo: a shake asks the component what to do (it gates on the device-local toggle + emits the
    // no-target tracking event itself); a Confirm raises the "Undo [operation]?" prompt before reverting.
    var pendingUndo by remember { mutableStateOf<String?>(null) }
    ShakeDetector {
        when (val outcome = component.onShake()) {
            is ShakeOutcome.Confirm -> pendingUndo = outcome.operation
            ShakeOutcome.Nothing -> Unit
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ItemTreeContent(
            rows = state.rows,
            isRefreshing = state.isRefreshing,
            onToggleExpand = component::onToggleExpand,
            onOpenDetail = component::onOpenDetail,
            onRefresh = component::onRefresh,
            onSearch = onSearch,
            onAdd = onAdd,
            showBlocked = state.showBlocked,
            onSetShowBlocked = component::onSetShowBlocked,
            listState = listState,
            pinSearch = false,
            // Search lives in the shell's native top bar on Android (the Files-style pill), so the inline
            // band shows only the local filter — no duplicate search box.
            searchInList = false,
            moveMode = state.moveMode,
            onEnterMoveMode = component::onEnterMoveMode,
            onMoveUp = component::onMoveUp,
            onMoveDown = component::onMoveDown,
            onIndent = component::onIndent,
            onOutdent = component::onOutdent,
            onExitMoveMode = component::onExitMoveMode,
            canUndo = state.lastMove != null,
            onUndoMove = component::undoLastMove,
        )
        // Top-anchored, out of thumb reach (ADR-0034 decision 8): the Material default is bottom, so align top.
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.TopCenter))
    }

    pendingUndo?.let { operation ->
        AlertDialog(
            onDismissRequest = { pendingUndo = null },
            confirmButton = {
                TextButton(onClick = {
                    pendingUndo = null
                    component.undoLastMove()
                }) { Text("Undo") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUndo = null }) { Text("Cancel") }
            },
            title = { Text("Undo $operation?") },
        )
    }
}

/** Shake threshold in g (total acceleration / gravity); ~2.7g clears normal handling but is easy to trigger deliberately. */
private const val SHAKE_G_THRESHOLD = 2.7f

/** Ignore repeat shakes within this window (ms) so one shake gesture fires [ShakeDetector.onShake] once. */
private const val SHAKE_DEBOUNCE_MS = 1_000L

/**
 * Registers the accelerometer while composed and calls [onShake] on a deliberate shake (#230). A
 * threshold-on-g-force detector with a debounce — deliberately minimal; literal motion-classification is
 * unnecessary for a confirm-gated gesture. ponytail: the sensor runs whenever the Tasks tree is shown
 * (cheap, UI-rate); the device-local toggle gates the *action* in [ItemTreeComponent.onShake], not the
 * sensor, so no preference plumbing reaches the View. A no-op on a device with no accelerometer.
 */
@Composable
private fun ShakeDetector(onShake: () -> Unit) {
    val context = LocalContext.current
    val currentOnShake by rememberUpdatedState(onShake)
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensorManager == null || accelerometer == null) return@DisposableEffect onDispose {}

        var lastShakeMs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (gForce <= SHAKE_G_THRESHOLD) return
                val nowMs = event.timestamp / 1_000_000 // sensor timestamp is ns since boot
                if (nowMs - lastShakeMs > SHAKE_DEBOUNCE_MS) {
                    lastShakeMs = nowMs
                    currentOnShake()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }
}
