# SQLDelight schema migrations: versioned, immutable, append-only

**Context.** SQLDelight was wired with `verifyMigrations = true` and a `schemaOutputDirectory`
(`databases/`) from the start (#21, ADR-0001 cites "explicit indexes/migrations" as a reason it was
chosen), but no `.sqm` migration files were ever written. Instead every schema change (#23, #71, #72,
#74) edited the `.sq` files and **re-snapshotted `databases/1.db` in place**, leaving `Schema.version`
pinned at `1`. The interim posture — "pre-release, no real users, just wipe the dev DB on a schema
change" — seemed cheap.

It wasn't. Because the version never bumped, the driver (which passes `DefernoDatabase.Schema`) runs
**neither `create()` nor `migrate()`** against an *already-initialised* on-disk DB: at open it reads
`PRAGMA user_version`, sees it already equals `Schema.version` (`1`), and leaves the file untouched. So
any table folded into "v1" after a DB was first created is **absent from that DB forever**, surfacing
as a runtime `SQLiteException: no such table: <name>`. This bit us **twice** — `userSettingsEntity` on
Android (2026-06-07) and `calendarItemEntity` + `seriesKindEntity` on desktop when opening Calendar
(2026-06-08) — each "fixed" only by manually wiping the DB (`adb pm clear`, or deleting the desktop
file). A manual step that must be remembered on every schema change, on every target, is a recurring
foot-gun, not a posture.

**Decision.** Adopt **real, versioned SQLDelight migrations** now. Schema versions are **immutable and
append-only**: each schema change ships a numbered `.sqm` migration *and* a new frozen
`databases/<N>.db` snapshot; a previously-committed snapshot or migration is **never edited**. The
driver then runs `migrate(old → new)` to bring existing DBs forward — no wipe, no data loss.

- **Baseline.** The current full schema is **version 2**. `databases/1.db` is the pre-#74 8-table
  baseline; `1.sqm` is the first migration (adds the Calendar tables); `databases/2.db` is the current
  10-table snapshot. `verifyCommonMainDefernoDatabaseMigration` (already a dependency of `check`, run
  by CI) proves `1.db` + `1.sqm` reproduces the `.sq` schema.
- **The first migration is idempotent (`CREATE TABLE IF NOT EXISTS`)** — *only* because pre-migration
  "v1" was ambiguous: a DB created before #74 has 8 tables, one created from the post-#74 in-place
  re-snapshot has all 10, both stamped `user_version = 1`. The idempotent step heals the former and
  no-ops the latter without erroring. **Future migrations start from an unambiguous version and use
  plain `CREATE`.**
- **Schema authored in `.sq`** (`deriveSchemaFromMigrations` stays off). The `.sq` files remain the
  source of truth for the current schema and queries; `.sqm` files describe only the forward DDL delta.

**Why this is enforced, not just documented.** Once a migration chain exists, the recurrence becomes a
**red build**: if someone edits the `.sq` schema without adding a matching migration,
`1.db` + the migrations no longer reproduce the `.sq` schema and `verifyMigration` fails `check`. The
one residual gap verify can't see — *editing an already-released `.sqm`* (existing DBs already ran the
old text and won't re-run it) — is closed by a CI guard that fails if any committed `*.sqm` is modified
or deleted (additions only). `.db` snapshots need no separate guard: any in-place edit to one is caught
by `verifyMigration` because the migration chain would no longer reproduce it.

**Runbook — changing the schema.**
1. Edit the relevant `.sq` file (add/alter the table or column).
2. Add a migration named `<currentVersion>.sqm` next to the `.sq` files containing the forward DDL
   (`ALTER TABLE … ADD COLUMN …`, `CREATE TABLE …`, `CREATE INDEX …`). Plain `CREATE` — not
   `IF NOT EXISTS` (the v1 ambiguity is behind us).
3. Run `./gradlew :core:database:generateCommonMainDefernoDatabaseSchema` — because the new `.sqm`
   bumps the version, this writes a **new** `databases/<N+1>.db` and leaves older snapshots untouched.
4. Run `./gradlew :core:database:verifyCommonMainDefernoDatabaseMigration` (or just `check`) — green
   means existing DBs at every prior version migrate cleanly to the new schema.
5. **Never** edit a committed `databases/<N>.db` or `<N>.sqm`. A released version is frozen; correct a
   mistake with the *next* migration.

Real per-version migrations were always the eventual destination once data had to survive a schema
change; pre-release simply reached that point sooner than "real users" because the dev loop itself was
the thing being stranded. Relates to ADR-0001 (offline-first local source of truth) and ADR-0006
(testing strategy — the migration verify rides the JVM-fast `check`).
