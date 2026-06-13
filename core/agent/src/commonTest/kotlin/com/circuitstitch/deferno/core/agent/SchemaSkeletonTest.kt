package com.circuitstitch.deferno.core.agent

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shape skeleton the on-device engine is steered with (ADR-0029 Phase 3) must name the real schema
 * key (`drafts`, not the structure name), type ids as strings, and carry the date format from the field
 * type — the three things a name-only prompt got wrong against the live model.
 */
class SchemaSkeletonTest {

    private val skeleton = InferenceSchema(DraftTasks.serializer()).jsonSkeleton()

    @Test
    fun names_the_root_array_field_not_the_structure_name() {
        assertTrue("\"drafts\":[" in skeleton, skeleton)
    }

    @Test
    fun types_the_id_as_a_string() {
        assertTrue("\"id\":\"string\"" in skeleton, skeleton)
    }

    @Test
    fun carries_the_date_and_time_formats_from_the_field_types() {
        assertTrue("\"completeBy\":\"yyyy-mm-dd?\"" in skeleton, skeleton)
        assertTrue("\"deadlineTimeOfDay\":\"HH:MM?\"" in skeleton, skeleton)
    }

    @Test
    fun marks_nullable_fields_and_numbers() {
        assertTrue("\"description\":\"string?\"" in skeleton, skeleton)
        assertTrue("\"desire\":\"number?\"" in skeleton, skeleton)
    }
}
