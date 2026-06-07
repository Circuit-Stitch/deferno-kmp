package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.scopes.SceneScope
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent.CreateComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Per-window/scene presentation scope ([SceneScope], ADR-0008) — a child of
 * [AccountScope]. Takes the [AccountComponent] as a kotlin-inject @Component parent;
 * the Decompose component tree + ViewModels will contribute here. Trivial stand-in
 * binding for now.
 */
data class SceneScaffold(val value: String)

@ContributesTo(SceneScope::class)
interface SceneScaffoldBindings {
    @Provides
    @SingleIn(SceneScope::class)
    fun provideSceneScaffold(): SceneScaffold = SceneScaffold("scene")
}

@MergeComponent(SceneScope::class)
@SingleIn(SceneScope::class)
abstract class SceneComponent(
    @Component val account: AccountComponent,
) {
    abstract val sceneScaffold: SceneScaffold

    // Re-exposed from AccountScope (one level up): the Active Account this scene is bound to,
    // proving Account context is resolvable per scene scope (ADR-0008 G3).
    abstract val activeAccount: Account
}

@CreateComponent
expect fun createSceneComponent(account: AccountComponent): SceneComponent
