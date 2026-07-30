# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase. This is a **single-context** repo: one `CONTEXT.md` and one `docs/adr/` at the root.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root (the domain glossary), if it exists.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in. `docs/adr/README.md` is the
  index (read it to find them); `docs/adr/TEMPLATE.md` is the schema a new ADR follows.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The producer skill (`/grill-with-docs`) creates them lazily when terms or decisions actually get resolved.

## File structure

```
/
├── CONTEXT.md                         ← domain glossary (created lazily)
├── docs/adr/                          ← architecture decision records
│   └── 0001-example.md
└── app/                               ← the Android application module
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/grill-with-docs`).

## Check an ADR is still live before you act on it

An ADR is a record of what was decided *at a point in time*, and a later ADR may have superseded it.
Before you build on one:

- **Read its Status line and any superseded-by pointer.** If it's superseded, follow the pointer and
  act on the successor, not on this one.
- **Read the marked reversals inside the Decision.** A reversed bullet is annotated in place, not
  deleted — a banner under the title or an inline *(Refined by ADR-XXXX: …)* marker. The annotation
  wins over the sentence it annotates.
- **An ADR with no Status line has not been triaged** — its currency is unknown, so don't treat it as
  live on its own. Take the **newest-numbered** ADR touching the same surface as authoritative, and
  **say so in your output** (which ADRs you compared, and which one you followed) so the reader can
  correct you.

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (…) — but worth reopening because…_
