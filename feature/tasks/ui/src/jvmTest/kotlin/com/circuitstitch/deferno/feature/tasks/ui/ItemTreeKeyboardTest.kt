package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.feature.tasks.ItemRow
import com.circuitstitch.deferno.feature.tasks.MoveMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The modal move-mode keyboard mirror (#228): Alt+↑/↓ reorder, Tab / Shift-Tab indent / outdent, Esc =
 * Done. A Compose-Multiplatform UI test on the JVM-fast path driving the stateless [ItemTreeContent]'s
 * `onPreviewKeyEvent` (the wiring the component tests in :feature:tasks can't reach). It asserts each
 * shortcut routes to its handler, and that the keys are inert outside move mode (so normal Tab-traversal
 * still works in the read-only tree). The legality-greying + the moves themselves are unit-tested in
 * :feature:tasks (ItemTreeComponentTest / ItemTreeTest).
 */
@OptIn(ExperimentalTestApi::class)
class ItemTreeKeyboardTest {

    private val row = ItemRow(
        item = Item(id = "a", kind = ItemKind.Task, title = "Alpha"),
        depth = 0,
        hasChildren = false,
        isExpanded = false,
    )

    private class Spy {
        val calls = mutableListOf<String>()
    }

    @Test
    fun moveMode_keyboardShortcuts_routeToHandlers() = runComposeUiTest {
        val spy = Spy()
        setContent { Themed { Tree(spy, moveMode = MoveMode("a", true, true, true, true)) } }
        onNodeWithTag(ItemTreeTag).requestFocus()

        onNodeWithTag(ItemTreeTag).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) } }
        onNodeWithTag(ItemTreeTag).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } }
        onNodeWithTag(ItemTreeTag).performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag(ItemTreeTag).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag(ItemTreeTag).performKeyInput { pressKey(Key.Escape) }

        assertEquals(listOf("up", "down", "indent", "outdent", "exit"), spy.calls)
    }

    @androidx.compose.runtime.Composable
    private fun Tree(spy: Spy, moveMode: MoveMode?) {
        ItemTreeContent(
            rows = listOf(row),
            isRefreshing = false,
            onToggleExpand = { _, _ -> },
            onOpenDetail = { _, _ -> },
            onRefresh = {},
            moveMode = moveMode,
            onMoveUp = { spy.calls += "up" },
            onMoveDown = { spy.calls += "down" },
            onIndent = { spy.calls += "indent" },
            onOutdent = { spy.calls += "outdent" },
            onExitMoveMode = { spy.calls += "exit" },
        )
    }

    @androidx.compose.runtime.Composable
    private fun Themed(content: @androidx.compose.runtime.Composable () -> Unit) {
        DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}
