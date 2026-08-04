package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.ItemView

/**
 * The kind-neutral identity of an [ItemView] — its id and which kind it is, without building the
 * concrete domain entity (#385).
 *
 * The four `asTaskOrNull`/`asHabitOrNull`/`asChoreOrNull`/`asEventOrNull` mappers in this package
 * partition a heterogeneous array into four typed lists, which is exactly what the `/items` cold sync
 * wants and exactly what an **ordered** read must not do: the daily plan is a curation whose whole
 * payload is the order, so partitioning it by kind destroys the only thing it carries. These two
 * accessors let an ordered list be projected in place, one element to one ref, order intact.
 */
val ItemView.itemId: String
    get() = when (this) {
        is ItemView.Task -> id
        is ItemView.Habit -> id
        is ItemView.Chore -> id
        is ItemView.Event -> id
    }

/**
 * The [ItemKind] this variant is. Total rather than nullable: the sealed hierarchy only decodes when
 * the wire's `type` discriminator matched a known token, so by the time an [ItemView] exists its kind
 * is already established. (An *unrecognised* token never reaches here — it fails the decode, which is
 * the tolerant reader's business, not this projection's.)
 */
val ItemView.itemKind: ItemKind
    get() = when (this) {
        is ItemView.Task -> ItemKind.Task
        is ItemView.Habit -> ItemKind.Habit
        is ItemView.Chore -> ItemKind.Chore
        is ItemView.Event -> ItemKind.Event
    }
