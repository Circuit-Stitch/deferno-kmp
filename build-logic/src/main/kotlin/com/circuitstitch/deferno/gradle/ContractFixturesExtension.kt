package com.circuitstitch.deferno.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * Per-module configuration for the `deferno.contract-fixtures` convention — which captured JSON a
 * module embeds into its `commonTest`, and under what name.
 *
 * Everything here was a hardcoded `core:network` string until the convention gained a second
 * consumer (#401's recurrence corpus, ADR-0053 decision 5). That is the bar this repo sets for an
 * abstraction: earned at the second consumer, not before (ADR-0004, and the same note
 * `deferno.apple.framework` carries).
 *
 * A configuration *object*, unlike the sibling `ProjectConfig` / `CoverageConfig` / `AppleFrameworkConfig`
 * compile-time constants, because this is genuinely per-module: two modules must be able to embed two
 * different directories at once, which a shared constant cannot express.
 *
 * **[packageName] has no default, deliberately.** Defaulting it to `core:network`'s value would make
 * the block optional — and a new consumer that forgot it would silently emit a second object into
 * `core.network.fixtures`, in the wrong module, compiling perfectly and pinning nothing.
 */
abstract class ContractFixturesExtension {

    /**
     * The directory of `*.json` files to embed. Read **non-recursively** (one flat directory), and
     * each file's stem becomes a constant name — so no file may begin with a digit.
     *
     * Defaults to the repo-root `contracts/fixtures`, the captured golden envelopes (#19).
     */
    abstract val sourceDir: DirectoryProperty

    /** The package the generated object is emitted into. Required — see the class KDoc. */
    abstract val packageName: Property<String>

    /** The generated object's name. Defaults to `ContractFixtures`, its name before it was named. */
    abstract val objectName: Property<String>
}
