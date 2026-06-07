package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.feature.tasks.TaskPane
import com.circuitstitch.deferno.feature.tasks.TasksComponent

/**
 * The Tasks feature host — the **single-pane** baseline for #27 (the adaptive 2-pane
 * `ListDetailPaneScaffold` of ADR-0007 is a future, large-screen concern). The shared component keeps
 * the detail and tree as **co-resident** slots (both can be open at once), so a phone can't pick by
 * static precedence: drilling from the tree into a child opens that child's *detail* while the tree
 * slot stays open, and a fixed tree>detail order would keep the tree on top — the tap would look like
 * it did nothing (the bug this host fixes).
 *
 * Instead it renders [TasksComponent.activePane] — the **most-recently-foregrounded** pane, owned by
 * the (retained) component so it survives configuration changes and so back-handling and rendering
 * read one source of truth. This View is a pure renderer: it holds no foreground state of its own.
 */
@Composable
fun TasksScreen(component: TasksComponent, modifier: Modifier = Modifier) {
    val detailSlot by component.detail.subscribeAsState()
    val treeSlot by component.tree.subscribeAsState()
    val activePane by component.activePane.subscribeAsState()

    val detail = detailSlot.child?.instance
    val tree = treeSlot.child?.instance

    when {
        activePane == TaskPane.Tree && tree != null -> TaskTreeScreen(tree, modifier)
        activePane == TaskPane.Detail && detail != null -> TaskDetailScreen(detail, modifier)
        // activePane's slot was dismissed out from under it: fall back to whatever remains, then the list.
        tree != null -> TaskTreeScreen(tree, modifier)
        detail != null -> TaskDetailScreen(detail, modifier)
        else -> TaskListScreen(component.list, modifier)
    }
}
