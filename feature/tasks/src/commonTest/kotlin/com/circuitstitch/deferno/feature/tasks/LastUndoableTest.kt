package com.circuitstitch.deferno.feature.tasks

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The single-level last-undoable register (ADR-0034 decision 8, #230): records the inverse of the most
 * recent undoable command, replays it once, and is shaped to grow into a general last-action undo.
 */
class LastUndoableTest {

    @Test
    fun recordsThenUndoRunsTheActionAndClears() = runTest {
        val register = LastUndoable()
        var ran = 0
        register.record(MoveOperation.Indent, structural = true) { ran++ }

        assertEquals(MoveOperation.Indent, register.current.value?.operation)
        assertTrue(register.current.value!!.structural)

        val entry = register.undo()
        assertEquals(1, ran, "undo runs the recorded inverse")
        assertEquals(MoveOperation.Indent, entry?.operation)
        assertNull(register.current.value, "single-level: cleared after one undo, never replayed twice")
    }

    @Test
    fun undoWithNothingRecordedReturnsNull() = runTest {
        assertNull(LastUndoable().undo())
    }

    @Test
    fun recordReplacesThePreviousEntry() = runTest {
        val register = LastUndoable()
        var first = 0
        var second = 0
        register.record(MoveOperation.Reorder, structural = false) { first++ }
        register.record(MoveOperation.Outdent, structural = true) { second++ }

        assertEquals(MoveOperation.Outdent, register.current.value?.operation, "single-level: the latest wins")
        register.undo()
        assertEquals(0, first, "the replaced action never runs")
        assertEquals(1, second)
    }

    @Test
    fun clearDropsTheEntryWithoutRunningIt() = runTest {
        val register = LastUndoable()
        var ran = 0
        register.record(MoveOperation.Indent, structural = true) { ran++ }

        register.clear()
        assertNull(register.current.value)
        assertEquals(0, ran)
    }

    @Test
    fun eachRecordBumpsTheToken() {
        val register = LastUndoable()
        register.record(MoveOperation.Reorder, structural = false) {}
        val first = register.current.value!!.id
        register.record(MoveOperation.Indent, structural = false) {}

        assertTrue(register.current.value!!.id > first, "a fresh token re-fires the single-shot snackbar effect")
        assertFalse(register.current.value!!.structural)
    }
}
