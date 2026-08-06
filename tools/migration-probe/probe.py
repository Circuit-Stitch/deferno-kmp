#!/usr/bin/env python3
"""End-to-end probe for a Deferno backend migration, from the KMP client's point of view.

A data migration rewrites storage. Nothing about that is observable from a unit test against a real
account, and Deferno's migrations are **lazy and per-user** — they run on the first authenticated
request after a deploy — so the only way to know what one did is to record the account's state on
both sides of the deploy and diff it.

    probe.py preflight  →  probe.py seed  →  probe.py capture pre
                                                     │
                                          merge + deploy the backend
                                                     │
                                 probe.py capture post  →  probe.py verify
                                                        →  the Kotlin parity check

The phases either side of the deploy may be days apart, so this is stdlib-only and keeps everything
it knows on disk under `runs/<scenario>/`.

`--scenario` selects what is being pinned; see `scenarios/__init__.py` for the contract. The engine
owns the parts that are the same for every migration — capturing the client-visible surfaces,
diffing them, classifying stored vs derived rows, the blast-radius scan, the change-stream and
wire-shape checks, cleanup. A scenario owns its preconditions, fixtures and expectations.
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import scenarios  # noqa: E402
from common import (  # noqa: E402
    POLL_SECONDS,
    Api,
    Report,
    dates,
    edge_only,
    framing,
    load_token,
    local_date,
    occ_dates,
    occurrence_path,
    read_json,
    stored_dates,
    write_json,
)

TOOL_DIR = Path(__file__).resolve().parent
DEFAULT_BASE = "https://app2.defernowork.com/api"
DEFAULT_PAT_FILE = Path.home() / "staging_pat.txt"


# ── preflight ─────────────────────────────────────────────────────────────────────────────────


def cmd_preflight(api: Api, scenario, run_dir: Path, args) -> int:
    f = framing(api)
    print(f"scenario     : {scenario.NAME} — {scenario.TITLE}")
    print(f"targets      : {scenario.TARGETS}")
    print(f"account      : {f['username']} ({f['user_id']})")
    print(f"timezone     : {f['tzid']}")
    print(f"now          : {f['now_local']}  /  {f['now_utc']}")
    print()

    rep = Report()
    for ok, name, detail in scenario.preconditions(api, f):
        rep.check(ok, name, detail)

    print()
    print("measuring which side of the deploy the backend is on…")
    side, detail = scenario.deployed_side(api, f)
    # "post" is not a malfunction, just the wrong moment: the migration has already shipped and
    # seeding now cannot produce the before-state. It still blocks `seed`, but it is not a FAIL.
    rep.check(side == "pre", f"deployed build measures as: {side.upper()}", detail,
              severity="NOTE" if side == "post" else "FAIL")

    ok = rep.fails == 0 and side == "pre"
    write_json(run_dir / "preflight.json", {"scenario": scenario.NAME, "framing": f, "deployed": side, "ok": ok})
    print()
    print("preflight:", "OK — safe to seed" if ok else "NOT OK — see above")
    return 0 if ok else 1


# ── seeding ───────────────────────────────────────────────────────────────────────────────────


class SeedContext:
    """What a scenario's `seed` is handed: the API, the clock framing, and a fixture recorder.

    Fixtures go into the manifest as they are created rather than being returned in a batch, so a
    run that dies halfway still leaves `cleanup` able to find everything it made.
    """

    def __init__(self, api: Api, framing_dict: dict, scenario, manifest: dict, corpus_limit):
        self.api = api
        self.framing = framing_dict
        self.scenario = scenario
        self.manifest = manifest
        self.corpus_limit = corpus_limit

    def log(self, msg: str):
        print(msg)
        self.manifest["seed_log"].append(msg)

    def heading(self, title: str):
        print()
        print(f"── {title} ──")

    def fixture(self, key: str, kind: str, note: str, expect: dict, create, title_suffix: str = "") -> str | None:
        """Create one fixture and record it. `create(title)` returns `(id_or_None, error_or_None)`."""
        title = f"{self.scenario.PREFIX} {key}" + (f" {title_suffix}" if title_suffix else "")
        item_id, err = create(title)
        self.manifest["fixtures"].append(
            {"key": key, "kind": kind, "id": item_id, "title": title, "note": note,
             "expect": expect, "create_error": err}
        )
        if item_id is None:
            message = (err or {}).get("error", {})
            self.log(f"  !! {key}: create REJECTED — {message.get('message', err) if isinstance(message, dict) else err}")
        return item_id


def cmd_seed(api: Api, scenario, run_dir: Path, args) -> int:
    f = framing(api)
    failed = [(name, detail) for ok, name, detail in scenario.preconditions(api, f) if not ok]
    if failed and not args.force:
        lines = "\n".join(f"  - {name}: {detail}" for name, detail in failed)
        raise SystemExit(f"preconditions not met:\n{lines}\n\nFix them, or pass --force to seed anyway.")

    manifest = {
        "scenario": scenario.NAME,
        "targets": scenario.TARGETS,
        "prefix": scenario.PREFIX,
        "framing": f,
        "stamp": datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S"),
        "fixtures": [],
        "corpus": [],
        "seed_log": [],
    }
    print(f"seeding {scenario.NAME} against {api.base}")
    print(f"  local {f['local_date']} · UTC {f['utc_date']} · tz {f['tzid']}")

    scenario.seed(SeedContext(api, f, scenario, manifest, args.corpus_limit))

    created = sum(1 for fx in manifest["fixtures"] if fx.get("id"))
    path = write_json(run_dir / "manifest.json", manifest)
    print()
    print(f"seeded {created} of {len(manifest['fixtures'])} fixtures -> {path}")
    print("next: probe.py capture pre")
    return 0


# ── capture ───────────────────────────────────────────────────────────────────────────────────


def wait_for_migration(probe, seconds: int) -> dict:
    """Block until the scenario's readiness probe says the migration landed, or give up and say so.

    The runner is `tokio::spawn`ed fire-and-forget (`auth.rs::spawn_lazy_migrations`), so the first
    authenticated request after a deploy **returns before the migration has touched anything**.
    Worse, the "already at the current version" short-circuit is a *per-process* cache, so behind
    more than one replica the request that triggers the run and the one that reads the result need
    not be the same process. Capturing immediately would read a half-migrated account.

    Each poll is an authenticated request, so waiting also nudges.

    A failed read and "not ready yet" are kept distinct. Conflating them is how the first version of
    this loop failed silently: it called the occurrence endpoint without the required `from`/`to`,
    every poll 400'd, a 400 read as "not ready", and with no pause between attempts it burned its
    whole budget re-sending the same bad request.

    On timeout this returns rather than raising — a migration that never ran is a finding for
    `verify` to report against a real capture, not a reason to have no capture at all.
    """
    deadline = datetime.now(timezone.utc) + timedelta(seconds=seconds)
    polls, last = 0, None
    while datetime.now(timezone.utc) < deadline:
        polls += 1
        ready, detail = probe()
        last = detail
        if ready:
            time.sleep(POLL_SECONDS)  # confirm it settled rather than catching a pass mid-flight
            again, detail2 = probe()
            if again:
                print(f"  migration landed after {polls} poll(s): {detail2}")
                return {"landed": True, "polls": polls, "detail": detail2}
        time.sleep(POLL_SECONDS)
    print(f"  !! the migration has NOT landed after {polls} poll(s) over {seconds}s ({last}).")
    print("     Capturing anyway — `verify` will report it against the real state.")
    return {"landed": False, "polls": polls, "detail": last}


def cmd_capture(api: Api, scenario, run_dir: Path, args) -> int:
    manifest = read_json(run_dir / "manifest.json")
    tzid = manifest["framing"]["tzid"]
    L = local_date(manifest["framing"])
    win_from, win_to = L - timedelta(days=45), L + timedelta(days=60)

    cap: dict = {
        "scenario": scenario.NAME,
        "label": args.label,
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "framing_at_capture": framing(api),
        "window": {"from": win_from.isoformat(), "to": win_to.isoformat(), "tz": tzid},
    }

    print(f"capturing '{args.label}' for {scenario.NAME} from {api.base}…")
    if args.label == "post" and args.wait_seconds > 0:
        if probe := scenario.readiness(api, manifest):
            cap["migration_wait"] = wait_for_migration(probe, args.wait_seconds)

    # The client's cold snapshot (KtorItemSnapshotSource -> GET /items, ADR-0049).
    items = api.must_get("items")
    cap["items"] = {i["id"]: i for i in items if isinstance(i, dict) and "id" in i}
    print(f"  /items                : {len(cap['items'])} items")

    # The client's agenda feed (KtorCalendarRemoteSource -> GET /tasks/calendar, ADR-0011). Kept
    # whole: the diff needs the instants, not just the dates, to see a complete_by shift.
    status, cal, _ = api.get("tasks/calendar", start=win_from.isoformat(), end=win_to.isoformat(), tz=tzid)
    cap["calendar"] = {"status": status, "rows": cal if status == 200 else None}
    print(f"  /tasks/calendar       : {len(cal) if status == 200 else 'FAILED'} rows")

    # The daily plan around the local-day boundary, where date defects surface as haunting.
    cap["plan"] = {}
    for d in (L - timedelta(days=1), L, L + timedelta(days=1)):
        status, plan, _ = api.get("items/plan", date=d.isoformat(), tz=tzid)
        cap["plan"][d.isoformat()] = {"status": status, "data": plan}
    print(f"  /items/plan           : {len(cap['plan'])} days")

    # Per-definition occurrence rows and item detail — for the seeded fixtures AND every
    # pre-existing recurring item. The account's own definitions are migration candidates too, and
    # unlike a fixture their history is not reconstructible, so they are the ones that matter.
    recurring = [
        (i["id"], i.get("type") or i.get("kind"), i.get("title"))
        for i in items
        if isinstance(i, dict) and (i.get("type") or i.get("kind")) in ("habit", "chore", "event")
    ]
    cap["occurrences"], cap["details"] = {}, {}
    for item_id, kind, title in recurring:
        status, rows, _ = api.get(
            occurrence_path(kind, item_id), **{"from": win_from.isoformat(), "to": win_to.isoformat()}
        )
        cap["occurrences"][item_id] = {"kind": kind, "title": title, "status": status, "rows": rows}
        dstatus, detail, _ = api.get(f"items/{item_id}")
        cap["details"][item_id] = {"status": dstatus, "data": detail}
    print(f"  per-item occurrences  : {len(cap['occurrences'])} recurring definitions")

    # The Activity ledger (ActivityRemoteSource, #364) — the client's `?since=` reconcile. A
    # migration that repairs storage without bumping `rev` emits nothing here, by design.
    #
    # Staging answers 503 "activity ledger is not configured" as of 2026-08-05. That is recorded
    # rather than swallowed: `verify` must not report a check it never got to make.
    status, adata, env = api.get("activity", limit=1)
    cap["activity_available"] = status == 200
    cap["activity_status"] = {"status": status, "body": adata if status != 200 else None}
    cap["activity_watermark"] = (env or {}).get("next_since")
    pre_path = run_dir / "capture-pre.json"
    if args.label != "pre" and pre_path.exists():
        if pre_wm := read_json(pre_path).get("activity_watermark"):
            status, rows, _ = api.get("activity", since=pre_wm, limit=500)
            cap["activity_since_pre"] = {"status": status, "rows": rows}
            print(f"  /activity?since=pre   : {len(rows) if status == 200 else 'FAILED'} entries")
    print(f"  activity ledger       : {'available' if cap['activity_available'] else 'unavailable'}")

    cap["grid_cases"] = _grid_cases(api, manifest, cap, tzid, args.grid_days, args.label)
    vacuous = sum(1 for c in cap["grid_cases"] if not c["server_starts_utc"])
    print(f"  grid parity cases     : {len(cap['grid_cases'])} ({vacuous} with no server firings — vacuous)")

    path = write_json(run_dir / f"capture-{args.label}.json", cap)
    write_json(run_dir / f"grid-cases-{args.label}.json", cap["grid_cases"])
    print(f"-> {path}")
    print("\nnext: " + ("deploy the backend, then `probe.py capture post`" if args.label == "pre" else "probe.py verify"))
    return 0


def _grid_cases(api: Api, manifest: dict, cap: dict, tzid: str, grid_days: int, label: str) -> list[dict]:
    """Corpus-shaped expansion cases: the recurrence, the server's own `series` block, and the
    instants the server's grid actually produced. The Kotlin half replays these through
    `expandOccurrenceGrid` — see `core/model/src/jvmTest/…/StagingGridParityTest.kt`.

    The window is derived PER CASE from that definition's own anchor rather than reusing the global
    one. A monthly or yearly rule anchored today fires nowhere inside a ±2-month window, and an
    empty-vs-empty comparison reads as agreement while proving nothing. Windows repeat across cases
    that share an anchor, so the feed is fetched once per distinct window.
    """
    feed_cache: dict[tuple, list] = {}

    def feed(start: date, end: date, tz: str):
        key = (start.isoformat(), end.isoformat(), tz)
        if key not in feed_cache:
            st, rows, _ = api.get("tasks/calendar", start=key[0], end=key[1], tz=tz)
            feed_cache[key] = rows if st == 200 and isinstance(rows, list) else []
        return feed_cache[key]

    cases = []
    for entry in manifest.get("corpus", []):
        if not entry.get("id"):
            continue
        detail = cap["details"].get(entry["id"], {}).get("data")
        if not isinstance(detail, dict) or not (series := detail.get("series")):
            continue
        anchor_day = date.fromisoformat(series["dtstart_local"][:10])
        c_from, c_to = anchor_day, anchor_day + timedelta(days=grid_days)
        rows = feed(c_from, c_to, series.get("tzid") or tzid)
        cases.append(
            {
                "name": entry["key"],
                "note": f"staging {label}: {', '.join(entry.get('cases', [])[:3])}",
                "recurrence": detail.get("recurrence"),
                "series": series,
                "window": {"from": c_from.isoformat(), "to": c_to.isoformat()},
                "server_starts_utc": sorted(r["start"] for r in rows if r.get("task_id") == entry["id"]),
            }
        )
    return cases


# ── verify ────────────────────────────────────────────────────────────────────────────────────


def cmd_verify(api: Api, scenario, run_dir: Path, args) -> int:
    manifest = read_json(run_dir / "manifest.json")
    pre = read_json(run_dir / "capture-pre.json")
    post = read_json(run_dir / "capture-post.json")
    rep = Report()

    print(f"{scenario.TITLE}")
    print(f"  {scenario.TARGETS}")
    print(f"  seeded {manifest['stamp']} · local {manifest['framing']['local_date']} / UTC {manifest['framing']['utc_date']}")
    print(f"  pre captured {pre['captured_at']}  ->  post captured {post['captured_at']}")
    if (w := post.get("migration_wait")) and not w.get("landed"):
        rep.section("readiness")
        rep.check(False, "the migration landed before the post capture", w.get("detail", ""))

    scenario.verify(manifest, pre, post, rep)

    _verify_client_cache(manifest, pre, post, rep)
    _verify_blast_radius(manifest, pre, post, rep)
    _verify_change_stream(pre, post, rep)
    _verify_wire_shape(pre, post, rep)
    _verify_grid(pre, post, rep, run_dir)

    print()
    print(f"{'FAILED' if rep.fails else 'OK'} — {rep.fails} failing assertion(s)")
    return 1 if rep.fails else 0


def _verify_client_cache(manifest, pre, post, rep):
    rep.section(
        "the client's cached occurrence facts",
        "OccurrenceFactLocalStore keys facts by (kind, definitionId, date), so a re-keyed row is a",
        "DELETE at the old date plus an INSERT at the new one. Only a range re-sync covering BOTH",
        "dates converges a device that cached the pre state — ADR-0053 decision 4.",
    )
    quiet = True
    for fx in manifest["fixtures"]:
        if not fx.get("id"):
            continue
        before, after = set(occ_dates(pre, fx["id"])), set(occ_dates(post, fx["id"]))
        gone, learned = sorted(before - after), sorted(after - before)
        if gone or learned:
            quiet = False
            rep.note(fx["key"], f"forget {dates(gone)} · learn {dates(learned)}")
    if quiet:
        rep.note("no seeded definition changed shape", "the client's cache needs no reconciliation")


def _verify_blast_radius(manifest, pre, post, rep):
    rep.section(
        "blast radius: pre-existing data the probe did not seed",
        "The account's own definitions are migration candidates too, and unlike a fixture their",
        "history is not reconstructible. Every line here is a day a real entry was filed under.",
    )
    seeded = {fx["id"] for fx in manifest["fixtures"] if fx.get("id")}
    stored_moved, grid_only = 0, 0
    for item_id, entry in pre.get("occurrences", {}).items():
        if item_id in seeded:
            continue
        label = f"{entry.get('kind')} {entry.get('title')!r}"
        s_before, s_after = stored_dates(pre, item_id), stored_dates(post, item_id)
        if s_before != s_after:
            stored_moved += 1
            rep.note(
                label,
                f"STORED lost {dates(sorted(set(s_before) - set(s_after)))} "
                f"gained {dates(sorted(set(s_after) - set(s_before)))}  ({item_id})",
            )
            continue
        if moved := set(occ_dates(pre, item_id)) ^ set(occ_dates(post, item_id)):
            grid_only += 1
            rep.note(
                label,
                f"derived only: {dates(sorted(moved))} "
                + (
                    "— window-edge bound correction"
                    if edge_only(moved, pre.get("window") or {})
                    else "— NOT at a window edge; the grid itself moved"
                ),
            )
    total = len(pre.get("occurrences", {})) - len(seeded)
    rep.note("pre-existing definitions with STORED rows moved", f"{stored_moved} of {total}")
    rep.note("pre-existing definitions with derived rows only", f"{grid_only} of {total} — no data was written")


def _verify_change_stream(pre, post, rep):
    rep.section(
        "the change stream the client syncs on",
        "A migration that repairs a storage key without bumping `rev` is INVISIBLE to anything that",
        "syncs on change — a device that cached the pre state is never told. This measures how far",
        "that reaches; it is a property of the migration's design, not automatically a bug in it.",
    )
    if post.get("activity_available"):
        rows = (post.get("activity_since_pre") or {}).get("rows")
        rep.note("activity entries since the pre capture", str(len(rows) if isinstance(rows, list) else 0))
    else:
        rep.note(
            "activity ledger",
            f"unavailable on this backend ({(post.get('activity_status') or {}).get('body')}) "
            "— not checked; `rev` below is the observable that stands in for it",
        )

    # Only STORED rows count. A derived row moving is a read-side recomputation with no write behind
    # it, so "no rev bump" is the correct answer for it, and folding the two together would inflate
    # this into a number that no longer measures what it claims to.
    silent, announced = [], []
    for item_id, entry in pre.get("occurrences", {}).items():
        if stored_dates(pre, item_id) == stored_dates(post, item_id):
            continue
        pre_rev = (pre.get("items", {}).get(item_id) or {}).get("rev")
        post_rev = (post.get("items", {}).get(item_id) or {}).get("rev")
        label = f"{entry.get('kind')} {entry.get('title')!r} (rev {pre_rev})"
        (announced if pre_rev != post_rev else silent).append(label)
    rep.note("definitions whose STORED rows moved AND rev bumped", str(len(announced)))
    rep.note(
        "definitions whose STORED rows moved, rev UNCHANGED",
        f"{len(silent)} — a delta-sync client never learns about these"
        + (f": {dates(silent, cap=4)}" if silent else ""),
    )
    changed = [
        i for i in pre.get("items", {})
        if i in post.get("items", {}) and pre["items"][i].get("rev") != post["items"][i].get("rev")
    ]
    rep.note("items whose rev changed at all", str(len(changed)))


def _verify_wire_shape(pre, post, rep):
    rep.section(
        "wire shape the client decodes",
        "The tolerant reader (ADR-0005) absorbs added fields; a REMOVED one is what breaks a shipped",
        "client, and `series` going missing would strand the offline expander entirely.",
    )
    missing_series = [
        i for i, d in post.get("details", {}).items()
        if isinstance(d.get("data"), dict)
        and isinstance(pre.get("details", {}).get(i, {}).get("data"), dict)
        and pre["details"][i]["data"].get("series")
        and not d["data"].get("series")
    ]
    rep.check(not missing_series, "series block still present on every item that had one", str(missing_series or ""))

    def keys(cap):
        return {k for d in cap.get("details", {}).values() if isinstance(d.get("data"), dict) for k in d["data"]}

    pre_keys, post_keys = keys(pre), keys(post)
    rep.check(
        not (pre_keys - post_keys),
        "no item field disappeared from the wire",
        f"dropped: {sorted(pre_keys - post_keys)}" if pre_keys - post_keys else "",
    )
    if post_keys - pre_keys:
        rep.note("new item fields (the tolerant reader absorbs these)", f"{sorted(post_keys - pre_keys)}")

    for day, entry in pre.get("plan", {}).items():
        now = post.get("plan", {}).get(day, {}).get("status")
        rep.check(now == entry.get("status"), f"/items/plan {day} still {entry.get('status')}", f"now {now}")


def _verify_grid(pre, post, rep, run_dir):
    rep.section(
        "the offline expander (client-side grid)",
        "Server-side grid movement, summarised. The real comparison is the Kotlin half, which",
        "replays these through `expandOccurrenceGrid`:",
        f"  DEFERNO_PROBE_DIR={run_dir} \\",
        "    ./gradlew :core:model:jvmTest --tests '*StagingGridParityTest*' --rerun-tasks",
    )
    pre_cases = {c["name"]: c for c in pre.get("grid_cases", [])}
    moved = 0
    for c in post.get("grid_cases", []):
        if not (b := pre_cases.get(c["name"])):
            continue
        if b["server_starts_utc"] != c["server_starts_utc"]:
            moved += 1
            rep.note(
                f"grid moved: {c['name']}",
                f"{len(b['server_starts_utc'])} -> {len(c['server_starts_utc'])} firings",
            )
        if b.get("series") != c.get("series"):
            rep.check(False, f"series inputs REWRITTEN: {c['name']}", f"{b.get('series')} -> {c.get('series')}")
    rep.note("definitions whose server grid moved", f"{moved} of {len(pre_cases)}")


# ── cleanup ───────────────────────────────────────────────────────────────────────────────────


def cmd_cleanup(api: Api, scenario, run_dir: Path, args) -> int:
    manifest = read_json(run_dir / "manifest.json")
    ids = [(fx["key"], fx["id"]) for fx in manifest["fixtures"] if fx.get("id")]
    print(f"deleting {len(ids)} items seeded by {manifest['scenario']} from {api.base}")
    if not args.yes:
        for key, item_id in ids:
            print(f"  would delete {key:<16} {item_id}")
        print("\nre-run with --yes to actually delete")
        return 0
    failed = 0
    for key, item_id in ids:
        status, data, _ = api.call("DELETE", f"items/{item_id}")
        if status not in (200, 204):
            failed += 1
            print(f"  !! {key} {item_id}: [{status}] {data}")
    print(f"deleted {len(ids) - failed} of {len(ids)}")
    return 1 if failed else 0


# ── entry point ───────────────────────────────────────────────────────────────────────────────


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--scenario", default=scenarios.ALL[0].NAME,
                   help=f"which migration to pin (default {scenarios.ALL[0].NAME})")
    p.add_argument("--base", default=os.environ.get("DEFERNO_API", DEFAULT_BASE))
    p.add_argument("--pat-file", type=Path, default=Path(os.environ.get("DEFERNO_PAT_FILE", DEFAULT_PAT_FILE)))
    p.add_argument("--dir", type=Path, default=None,
                   help="run directory (default runs/<scenario>/, or $DEFERNO_PROBE_DIR)")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("preflight", help="check the account, the preconditions and which build is deployed")

    s = sub.add_parser("seed", help="create the fixtures (run BEFORE the deploy)")
    s.add_argument("--corpus-limit", type=int, default=None, help="cap the corpus-derived definitions")
    s.add_argument("--force", action="store_true", help="seed even with preconditions unmet")

    s = sub.add_parser("capture", help="snapshot every client-visible surface")
    s.add_argument("label", choices=["pre", "post"])
    s.add_argument("--grid-days", type=int, default=400,
                   help="how far past each definition's own anchor to compare its grid (default 400)")
    s.add_argument("--wait-seconds", type=int, default=180,
                   help="on `capture post`, how long to wait for the lazy migration (0 = do not wait)")

    sub.add_parser("verify", help="diff post against pre and the scenario's expectations")

    s = sub.add_parser("cleanup", help="delete the seeded items")
    s.add_argument("--yes", action="store_true")

    args = p.parse_args()
    scenario = scenarios.by_name(args.scenario)
    run_dir = args.dir or Path(os.environ.get("DEFERNO_PROBE_DIR") or TOOL_DIR / "runs" / scenario.NAME)
    run_dir.mkdir(parents=True, exist_ok=True)
    api = Api(args.base, load_token(args.pat_file))
    return {
        "preflight": cmd_preflight,
        "seed": cmd_seed,
        "capture": cmd_capture,
        "verify": cmd_verify,
        "cleanup": cmd_cleanup,
    }[args.cmd](api, scenario, run_dir, args)


if __name__ == "__main__":
    sys.exit(main())
