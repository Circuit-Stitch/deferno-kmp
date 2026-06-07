package com.circuitstitch.deferno.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.feature.plan.ui.PlanScreen
import com.circuitstitch.deferno.feature.tasks.ui.TasksScreen

/**
 * TEMPORARY (#27) demo host UI: a two-destination shell (Plan home + Tasks) over the Android Views.
 * Assumes a `DefernoTheme` is already in scope (MainActivity provides it). Replace with the real
 * navigation surface when DI lands. (A native desktop/iOS shell is a separate follow-up, not this
 * phone layout stretched — ADR-0007.)
 */
@Composable
fun DemoApp(component: DemoComponent, modifier: Modifier = Modifier) {
    val tab by component.selectedTab.subscribeAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { DemoBanner() },
        bottomBar = { DemoBottomBar(selected = tab, onSelect = component::onTabSelected) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                DemoTab.Plan -> PlanScreen(component.plan, Modifier.fillMaxSize())
                DemoTab.Tasks -> TasksScreen(component.tasks, Modifier.fillMaxSize())
            }
        }
    }
}

/** A slim, unmissable marker that this is the throwaway demo running on sample data (not an account). */
@Composable
private fun DemoBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Demo · in-memory sample data (no account yet)",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(4.dp),
        )
    }
}

@Composable
private fun DemoBottomBar(selected: DemoTab, onSelect: (DemoTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            DemoTabItem("Plan", selected == DemoTab.Plan, { onSelect(DemoTab.Plan) }, Modifier.weight(1f))
            DemoTabItem("Tasks", selected == DemoTab.Tasks, { onSelect(DemoTab.Tasks) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowScope.DemoTabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // A filled pill marks the selected tab — a cue that isn't colour-only — and gives its label
        // an on-container colour that meets WCAG AA. (The bare primary-on-surface label was 2.27:1.)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
