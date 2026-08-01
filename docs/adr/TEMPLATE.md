# ADR template and house rules

Read this before writing an ADR; do not study it. It freezes the shape the existing 49 records
already converge on — every rule below is the measured plurality of the corpus, not a new
invention. The absence of this file is what allowed two records to claim number 0034, four
different title forms, five spellings of the rejected-alternatives section, and 37 records with no
Status line at all.

## 1. Section order and spelling

Sections are **bold pseudo-headers** — `**Context.**` inline at the start of its paragraph — not
markdown `##` headings. All 49 records use that shape; it is load-bearing for the CI gate and for
grep. Emit them in this order:

| Section | Required | What it is for |
| --- | --- | --- |
| `# <declarative title>` | yes | A sentence stating what was decided. |
| `**Status.**` | yes | Where this record stands today; see the vocabulary below. |
| `**Date.**` | yes | ISO date the decision was accepted, e.g. `2026-07-29`. |
| `**Issue.**` | yes | The issue or PR that drove it, e.g. `#289`, or `none` if there is none. |
| `**Context.**` | yes | The forces in play *at that date* — what was true, what constrained us. |
| `**Decision.**` | yes | What we will do, as bullets a reader can check the code against. |
| `**Considered & rejected.**` | if alternatives existed | Each real alternative and why it lost. |
| `**Consequences.**` | yes | What this costs, what it makes easy, what it forecloses. |

Measured basis: `**Context.**` and `**Decision.**` appear in 49 of 49 records and are
non-negotiable. `**Consequences.**` appears in 46 of 49. The ampersand form
`**Considered & rejected.**` wins with 20 uses against `Rejected` at 15 and
`Considered and rejected` at 6 — use the ampersand form and no other. `**Status.**` with the
period wins 6 to 5 over `**Status:**` — use the period. `**Date.**` and `**Issue.**` are
additions: zero of 49 records are dated and 18 of 49 carry no issue reference, so neither can be
recovered later.

**Titles are declarative.** State the decision as a sentence, the way ADR-0038, ADR-0039 and
ADR-0048 do:

> `# The Activity ledger is an optimistic cache of the server's, merged by a client-minted entry_id`

Not a bare topic label. ADR-0005 ("API version handling"), ADR-0006, ADR-0009 and ADR-0025 are bare
labels; they read as chapter names and force the reader to open the file to learn the outcome. The
filename stays the kebab-case slug: `NNNN-short-slug.md`.

## 2. Status vocabulary

Exactly one of these opens the Status line. Prose may follow it on the same line.

- **`Accepted`** — in force. The decision governs the code as it stands.
- **`Superseded by ADR-NNNN`** — a later record replaced this decision. Cite the successor as a
  relative link.
- **`Amended by ADR-NNNN`** — still in force, but a later record narrowed, widened, or carved an
  exception out of it. Use this rather than `Superseded` when most of the decision survives.
- **`Historical`** — the decision is no longer operative and nothing replaced it, usually because
  the thing it governed is gone.
- **`Deferred`** — a decision *not* to build something yet, plus the guardrails that keep the
  deferral cheap. ADR-0008 and ADR-0015 are the models.

## 3. Policy A0 — an ADR's argument is immutable

**You annotate and supersede. You do not rewrite.** The Context, the Considered & rejected section,
and the original Decision text are the record of what was believed and why, and they stay as
written. What is mutable is *addressing and status*: the Status line, superseded-by and amended-by
pointers, citations, numbering, and the marking of a bullet that later reversed.

Why: the record's whole value is that a reader can reconstruct what was believed on its date, and
2,348 `ADR-NNNN` citations across 701 source files depend on a number resolving to a fixed record
rather than to a document that quietly changed underneath them.

A reversed Decision bullet is annotated in place and left legible. Two forms are proven in this
corpus; use one of them:

- **Blockquote banner** under the title, when the whole record or its headline decision turned over
  — `docs/adr/0016-create-online-only-v1.md` line 3.
- **Inline parenthetical marker** on the affected bullet, when one bullet was refined —
  `docs/adr/0008-multi-window-stage-manager-deferred.md` line 16.

## 4. Numbering

Take **the next free number**, and allocate it by adding the row to `README.md` **in the same
commit** as the new file. The index is the allocation register: a number is taken the moment its row
lands, so two branches cannot both believe 0050 is free. Two records claimed 0034 precisely because
the number was taken by creating a file and nothing else.

## 5. Same-commit back-pointers

A record that supersedes or amends another **edits the target in the same commit** — the target's
Status line gains the pointer back, and any reversed bullet gains its marker. A forward pointer
without its back-pointer leaves the old record reading as current law, which is how a stale decision
gets cited years later. The two proven marker forms are the ones named in section 3: `0008` line 16
and `0016` line 3.

## 6. Mechanical constraints (CI-enforced)

- **No sentence over 60 words.** 19 sentences breach this today; the worst is 116 words.
- **At most one semicolon per paragraph.** A second semicolon means a second sentence.
- **No contractions.** Write "does not", "cannot", "it is".
- **Wrap at ~100 columns.** Hard-wrap prose; never reflow a paragraph you did not otherwise change.
- **Every reference resolves.** Each `ADR-NNNN` names a real record, each `[[wikilink]]` names a
  real `CONTEXT.md` term, and each relative link points at a file that exists.

## 7. Terminology

`CONTEXT.md` at the repo root is the terminology authority: **one term, one concept.** Use its
spelling for every domain word — Item, Task, Destination, Active Account, Org, Plan — and link a
term on first use as `[[Active Account]]`. Its `_Avoid_` lines are machine-checked, so a banned
synonym ("login", "current user", "profile" for an Account) fails the gate rather than merely
reading badly. If an ADR needs a word `CONTEXT.md` does not define, add the term there in the same
commit.

## Skeleton

```markdown
# <A sentence stating what was decided>

**Status.** Accepted (#NNN).

**Date.** YYYY-MM-DD

**Issue.** #NNN

**Context.** <What was true and what constrained us, at that date.>

**Decision.** <One line of framing, then:>

- **<Rule one>** — <what it means in the code.>
- **<Rule two>** — <what it means in the code.>

**Considered & rejected.**

- **<Alternative>** — <why it lost.>

**Consequences.** <What this costs, enables, and forecloses.>
```
