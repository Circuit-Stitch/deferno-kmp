# API contracts

The Deferno backend contract this client is built against — pinned, so it can't drift silently.

| File | What it is |
|---|---|
| `openapi-0.1.json` | Pinned snapshot of the backend OpenAPI spec (envelope `version: 0.1`). The contract of record. |
| `CONTRACT-NOTES.md` | The empirically-verified facts that the spec under-documents or gets wrong — read this before writing any wire/DTO/persistence code. |
| `fixtures/` | Golden envelopes captured from the live staging backend — the source of truth for the tolerant reader + DTO tests (#19). |
| `refresh.sh` | Re-fetch the spec from a running backend and `git diff` it. Drift = a reviewable diff. |

## Definition of Ready (wire-touching issues)

An issue that calls a backend endpoint (#17–#24) isn't `ready-for-agent` until it **links this folder
and names the DTO fields / fixture it depends on.** This keeps the contract single-sourced instead of
re-reverse-engineered per agent. See `CONTRACT-NOTES.md` for the facts to cite.

## Provenance

`openapi-0.1.json` was pinned from `http://localhost:3000/openapi.json`; fixtures were captured from
the staging origin (`app2.defernowork.com`). Free-text (`title`/`description`/`comment`) and the
`/auth/me` identity are scrubbed to placeholders; **every structural field — ids, `kind`/`type`,
status enums, nullability, timestamps — is preserved verbatim.** Refresh with `./refresh.sh`.

**Re-pinning the spec without a running backend.** `refresh.sh` needs a live server, and the staging
origin does **not** serve the spec — its SPA catch-all answers `/openapi.json` with `200 text/html`,
so a naive `curl` "succeeds" and writes a web page over the contract. The backend repo commits its own
copy at `docs/api/openapi.json`, generated from the same router that serves traffic and guarded on
every CI run by `scripts/check-openapi-spec-current.sh`, so it cannot silently rot. That file is the
better source, and it needs no build:

```sh
python3 -m json.tool ../Deferno/docs/api/openapi.json > contracts/openapi-0.1.json
```

Pinned at #410 from that copy (the `series` block — `SeriesInputs` + `SeriesOverrideView` — plus
`SegmentView` and `/admin/activity/queue`; purely additive, nothing removed).
