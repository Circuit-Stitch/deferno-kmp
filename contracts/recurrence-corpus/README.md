# The recurrence corpus

The golden [[Occurrence grid]] that pins `expandOccurrenceGrid` (`core/model`) against the backend —
ADR-0053 decision 5, issue #401.

**Generated from the Rust. Never hand-authored, never hand-edited.** Every file here came out of
`Circuit-Stitch/Deferno`'s `backend/examples/dump_recurrence_corpus.rs`, which runs each case through
the exact `Recurrence::to_rrule(tz)` → `defernodate::expand_series` path the occurrence handlers use
and writes down what came out. This directory is the same "capture, don't hand-author" posture as
`contracts/fixtures/` (see that README), for the same reason: an expectation somebody typed is a
second specification, and two specifications with no tiebreak is exactly what this client cannot
afford now that it computes dates itself.

## Why it exists

The client expands the grid locally so a dated surface still answers with the server gone (ADR-0053
decision 1). That makes the Kotlin expander a *second implementation* of rules the Rust already
implements — month-end skipping, `nth` weekdays, two bounds with opposite inclusivity, an excluded
date that still consumes a `COUNT`, and daylight-saving behaviour that **is documented nowhere** and
had to be measured. Nothing but this corpus stands between those two implementations and a silent
divergence.

## Direction of authority

1. Where a case and the Kotlin disagree, **the Kotlin is wrong**.
2. Where a case itself is wrong, the fix lands **in Rust**, and the corpus regenerates.
3. Editing a file here to make a Kotlin test pass converts the corpus into the thing it replaced.

## Regenerating

In `Circuit-Stitch/Deferno`:

```sh
cd backend && cargo run --example dump_recurrence_corpus -- ../docs/api/recurrence-corpus
```

That repo's CI runs `scripts/check-recurrence-corpus-current.sh` in its `lint` job, so a Rust change
that moves the grid fails **there** rather than silently drifting this client. Copy the result across:

```sh
cp ../Deferno/docs/api/recurrence-corpus/*.json contracts/recurrence-corpus/
```

Then run `./gradlew :core:model:jvmTest` and read any failure as "the expander needs to follow".

## How it is read

`build-logic`'s `deferno.contract-fixtures` convention embeds every `*.json` here into `core:model`'s
`commonTest` as `RecurrenceCorpus`, so `RecurrenceCorpusTest` replays it on **every** KMP target with
no runtime file IO — including `iosArm64` and `macosArm64`, neither of which has a Swift test target.
That is the whole argument for keeping these rules in shared Kotlin.

Two consequences worth knowing before adding cases:

- **One flat directory**, and a file stem becomes a Kotlin constant name — so no leading digits, and
  no subdirectories.
- Every case compiles into five test binaries. Cases are cheap but not free; add one because it pins a
  rule no other case pins, and say which in its `note`.

## A case

```jsonc
{
  "name": "…",          // the file stem
  "note": "…",          // why this case exists — which rule it pins
  "rrule": "FREQ=…",    // provenance: what `Recurrence::to_rrule` derived
  "recurrence": { … },  // the real wire shape (cadence flattened, `end` nested)
  "series": { "dtstart_local", "tzid", "until_utc", "exdates", "overrides" },
  "window": { "from", "to" },   // inclusive LOCAL dates in the FROZEN zone
  "outcome": "firings" | "not_expandable",
  "firings": [ { "recurrence_id", "start_local", "is_cancelled", "is_override" } ]
}
```

Two things about that shape are easy to misread:

- **`window` filters on the original slot, not on where a firing lands.** `expand_series` applies its
  range before it applies overrides, which is why a rescheduled instance can come back from outside
  the window it was moved into. The generator reproduces that.
- **`not_expandable` is the *pure* function's answer.** Production degrades those to zero occurrences
  (`expand_series_resilient`, added after one `DTSTART > UNTIL` series 500'd a user's whole calendar).
  The client deliberately keeps the distinction the server throws away: a grid that cannot be computed
  is **absent**, not empty (ADR-0053 decision 4).
