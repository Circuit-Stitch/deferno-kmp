package com.circuitstitch.deferno.core.di

/*
 * The compile-time DI scope layering (ADR-0008, guardrails G2/G3). Three nested scopes,
 * each a kotlin-inject component (one file per scope: AppComponent / AccountComponent /
 * SceneComponent) merged from its anvil contributions:
 *
 *   AppScope     — process-global singletons shared across every window/scene: the data
 *                  layer (repositories, SQLDelight, the sync engine, AccountManager, the
 *                  secure vault). One graph per process.
 *   AccountScope — the Active Account a window is bound to (ADR-0002). A child of AppScope;
 *                  a future second window can hold a different Active Account, so this is a
 *                  scope, not a hard global.
 *   SceneScope   — per-window/scene presentation (the Decompose component tree, ViewModels,
 *                  navigation state). A child of AccountScope; each scene gets its own.
 *
 * Layering is kotlin-inject parent components: a child @MergeComponent takes its parent
 * merged component as a @Component constructor argument, so a parent-scoped binding
 * resolves through its children.
 *
 * The graph is intentionally EMPTY in v1 (issue #10) — these are the scopes the data and
 * presentation layers will contribute into via @ContributesTo / @ContributesBinding as
 * they land. Each scope currently carries one trivial `*Scaffold` binding that proves it
 * compiles, layers, and resolves (the app-scope one a process-singleton); replace those
 * with real bindings rather than adding to them.
 *
 * Scope markers are plain classes — anvil only uses them to connect contributions to a
 * merged component; they carry no behaviour.
 */

object AppScope

object AccountScope

object SceneScope
