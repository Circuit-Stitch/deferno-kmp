package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.recipe.KindRecipe
import com.circuitstitch.deferno.core.model.recipe.KindRow
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe

/**
 * Wire rows as cache rows, for a test that has a [Task]/[Habit]/[Chore]/[Event] to hand and wants the
 * [CachedItem] the store would hold for it (#422).
 *
 * The store's vocabulary is the plugin-shaped record, and every fixture in this suite is still written
 * as one of the four wire rows — that is what the reconcile reads and what an assertion compares
 * against. These take the same path production takes: `KindRecipe.read`, with the kind noted alongside.
 * A test therefore never hand-builds a plugin list, so a recipe change moves the fixtures with it.
 *
 * The reverse direction is `CachedItem.asKindRow()` / `asTaskOrNull()`, which the store itself exposes.
 */
internal fun Task.cached(recipe: KindRecipe = ParityRecipe): CachedItem =
    CachedItem(recipe.read(this), ItemKind.Task)

internal fun Habit.cached(recipe: KindRecipe = ParityRecipe): CachedItem =
    CachedItem(recipe.read(this), ItemKind.Habit)

internal fun Chore.cached(recipe: KindRecipe = ParityRecipe): CachedItem =
    CachedItem(recipe.read(this), ItemKind.Chore)

internal fun Event.cached(recipe: KindRecipe = ParityRecipe): CachedItem =
    CachedItem(recipe.read(this), ItemKind.Event)

/** The same for a row whose kind is only known at runtime — a corpus sweep, or a store read back. */
internal fun KindRow.cached(recipe: KindRecipe = ParityRecipe): CachedItem =
    CachedItem(recipe.read(this), kind)

/** A map of [CachedItem]s keyed by id, the shape [FakeItemLocalStore] is seeded with. */
internal fun cacheOf(vararg rows: CachedItem): Map<String, CachedItem> = rows.associateBy { it.id }
