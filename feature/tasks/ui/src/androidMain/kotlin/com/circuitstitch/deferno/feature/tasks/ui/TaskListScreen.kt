package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.circuitstitch.deferno.feature.tasks.ItemTreeComponent

/**
 * The Tasks primary pane (ADR-0034, #227): the nested, collapsible **Item tree** across all kinds. A thin
 * renderer of [ItemTreeComponent] — it observes [ItemTreeComponent.state] and forwards toggle/open/refresh
 * to it, holding no logic (the row-state logic lives in `buildItemTree`, the rendering in [ItemTreeContent]).
 */
@Composable
fun TaskListScreen(component: ItemTreeComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    ItemTreeContent(
        rows = state.rows,
        isRefreshing = state.isRefreshing,
        onToggleExpand = component::onToggleExpand,
        onOpenDetail = component::onOpenDetail,
        onRefresh = component::onRefresh,
        modifier = modifier,
    )
}
