package com.circuitstitch.deferno.core.scopes

/*
 * The compile-time DI scope layering (ADR-0008 guardrails G2/G3, refined by ADR-0014). Three nested
 * scopes, each realized as a kotlin-inject merged component in `core:di` (AppComponent /
 * AccountComponent / SceneComponent):
 *
 *   AppScope     — process-global singletons shared across every window/scene: the cross-Account
 *                  infrastructure (network client + token provider, AccountManager, secure vault,
 *                  DatabaseKeyProvider). One graph per process.
 *   AccountScope — the Active Account a window is bound to (ADR-0002), a child of AppScope. The
 *                  per-Account data layer lives here (encrypted DB + stores + repositories + outbox
 *                  + sync), torn down and rebuilt on Active-Account switch (ADR-0014).
 *   SceneScope   — per-window/scene presentation (the Decompose component tree, ViewModels,
 *                  navigation state), a child of AccountScope; each scene gets its own.
 *
 * These markers live in this low-level module — below both the contributors that reference them in
 * @ContributesBinding/@ContributesTo and the `core:di` module that merges those contributions — so
 * distributed contribution does not create a scopes ⇄ merge dependency cycle.
 *
 * Scope markers are plain objects — anvil only uses them to connect contributions to a merged
 * component; they carry no behaviour.
 */

object AppScope

object AccountScope

object SceneScope
