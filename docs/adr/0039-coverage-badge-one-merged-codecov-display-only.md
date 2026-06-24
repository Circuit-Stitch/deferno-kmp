# Coverage is surfaced as one merged-core badge via Codecov; Codecov is display-only, never a gate

**Status:** accepted

**Context.** The shared-core line-coverage number is already computed and *gated* in CI — the
`deferno.coverage.aggregation` convention (ADR-0006) enforces a ~85–90% bound over the **merged**
`core/* + feature/*` report via `:koverVerify` — but the number is then thrown away: nothing surfaces
it publicly. This repo is Apache-2.0 open source (ADR-0020) and public on GitHub; transparency about
how well the shared core is tested is a feature, not a leak. Two sibling repos already solve this two
different ways: the public Rust **Janitor** repo renders per-crate badges from **Codecov**-hosted data
(its ADR-0016), and the *private* **Deferno** backend self-hosts a shields.io-endpoint JSON in a GitHub
Gist precisely because Codecov on a private repo is friction. deferno-kmp is public, so Janitor's
rationale — "the report is already public; Codecov's per-file drill-down *is* the transparency we want"
— applies directly, while the gist machinery (a PAT, a gist, a parse step) buys nothing here.

**Decision.**

- **One merged coverage badge, rendered by shields.io from Codecov.** CI runs the root
  `:koverXmlReport` and uploads the merged shared-core report (`core/* + feature/*`, boilerplate excluded
  by `CoverageConfig`) — `build/reports/kover/report.xml` — to Codecov. Codecov re-parses the XML
  per-line and computes its *own* number; by its default a partially-branched line counts as a miss, so
  the badge reads a few points **below** the LINE-based `:koverVerify` gate (≈84% vs ≈88%). That gap is
  kept on purpose (`partials_as_hits` left off): the badge is the stricter, branch-aware public signal,
  `:koverVerify` is the enforced line contract — they intentionally measure slightly differently. A
  second **CI status** badge (GitHub Actions) sits beside it; the repo had no badges at all before.

- **One badge, *not* per-module — deliberately diverging from Janitor's ADR-0016.** Janitor chose
  per-crate badges and explicitly rejected a single workspace badge because it had real testing
  asymmetry (a proven `core` vs a shell-bearing `aws` vs a logic-free `gui`). Here the **merged report
  is the designed unit**: the gate is one aggregate by construction, and most of the ~13 shared modules
  are still scaffolds. One flagless badge also needs no per-flag *path scoping*, which would force
  Codecov to attribute KMP source roots (`core/x/src/commonMain/kotlin/...`) into separate core/feature
  buckets — brittle and pointless when the enforced unit is the merged whole. If a genuine
  foundations-vs-slices asymmetry emerges later, split then (modules "earn" splitting, ADR-0004) — KISS
  until it doesn't.

- **Codecov is display-only — it can never become a second gate.** `codecov.yml` pins
  `coverage.status.project: false`, `patch: false`, `comment: false`. The build's red/green stays
  entirely inside `:koverVerify`; the badge upload uses `fail_ci_if_error: false`, so a missing token or
  a Codecov outage leaves the badge stale but never fails CI. The gate works with no Codecov app at all
  — only the badge needs the `CODECOV_TOKEN` secret and the app enabled on the repo.

- **No new device-side third-party reach (ADR-0038).** Codecov ingests a coverage report in CI; it is
  not a client data-plane peer and holds no user data — orthogonal to the "backend mediates external
  APIs" rule.

## Considered options

- **Self-hosted shields-endpoint JSON in a Gist (the Deferno backend pattern)** — rejected: it exists
  there because that repo is private. For a public repo it adds a PAT, a gist, and a Kover-XML parse
  step to reproduce a number Codecov publishes for free, with no per-file drill-down.
- **Per-module / core-vs-feature badges (Janitor ADR-0016 pattern)** — rejected for now: ~13 mostly-
  scaffold modules is badge spam, and the enforced unit is the merged aggregate, not any single module.
- **Codecov enforcing the gate via status checks** — rejected (as in Janitor ADR-0016): the gate is a
  correctness artifact and must stay self-contained in `:koverVerify`, not depend on a third party or a
  successful upload.
- **A committed static SVG** — rejected: churns git history with badge commits and goes stale whenever
  CI skips the regeneration.

## Consequences

- The shared-core coverage number is now public on the README, reinforcing the open-source testing
  posture (ADR-0006, ADR-0020).
- One-time setup is required before the badge resolves: enable the Codecov app on
  `Circuit-Stitch/deferno-kmp` and add a `CODECOV_TOKEN` repo secret. Until then the badge renders
  "unknown" and CI is unaffected.
- The badge tracks the merged number, so it moves with the gate. Should per-module insight ever be
  wanted, this decision is cheap to revisit — add Codecov flags + per-flag reports — but the default
  stays one honest aggregate.
