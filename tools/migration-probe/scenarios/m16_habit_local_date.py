"""M16 — habit check-ins re-keyed from the UTC date onto the user's local date.

`Circuit-Stitch/Deferno#658`, `backend/src/migrations/habit_occurrence_local_date_rekey.rs`.

`set_habit_occurrence` used to default an omitted `date` to `Utc::now().date_naive()`, and that
value becomes the Redis hash **field**. Every habit read surface speaks local dates, so west of UTC
an evening check-in landed a day ahead of the day the user actually checked in. M16 moves those rows
onto the local date, detecting the frame from `done_at` — the only evidence of what a row was
written under.

The migration rewrites a storage key, which no unit test against staging data can observe. Hence
this scenario: one fixture per equivalence class M16 distinguishes, captured on both sides.
"""

from __future__ import annotations

import json
from datetime import date, timedelta
from pathlib import Path

from common import (
    Api,
    create_recurring,
    dates,
    edge_only,
    eod_utc,
    local_date,
    occ_dates,
    occurrence_path,
    stored_dates,
    utc_date,
)

NAME = "m16-habit-local-date"
TITLE = "M16 — habit check-ins re-keyed onto the local date"
TARGETS = "Deferno#658 · habit_occurrence_local_date_rekey"
PREFIX = "[M16-PROBE]"

CORPUS_DIR = Path(__file__).resolve().parents[3] / "contracts" / "recurrence-corpus"


# ── preconditions ─────────────────────────────────────────────────────────────────────────────


def preconditions(api: Api, framing: dict):
    checks = [
        (
            framing["tzid"] != "UTC",
            "account tz is not UTC",
            f"{framing['tzid']} — M16 returns early for a UTC user and would prove nothing",
        ),
        (
            framing["frames_diverge"],
            "the two calendar frames disagree right now",
            f"{framing['local_date']} local vs {framing['utc_date']} UTC"
            if framing["frames_diverge"]
            else "they agree — a no-date check-in cannot reproduce the defect; retry after 17:00 local",
        ),
    ]
    return checks


def deployed_side(api: Api, framing: dict):
    """Measure the check-in default with a throwaway habit, then delete it.

    Which side of the deploy we are on is a *behaviour*, and behaviour is cheap to observe. A
    version string would be a second source of truth that can disagree with the running code.
    """
    L = framing["local_date"]
    U = framing["utc_date"]
    hid, err = create_recurring(
        api, "habit", f"{PREFIX} preflight throwaway", {"type": "daily"}, eod_utc(local_date(framing), framing["tzid"])
    )
    if hid is None:
        return "unknown", f"could not create the probe habit: {err}"
    status, data, _ = api.call("POST", f"habits/{hid}/occurrences", body={"done": True})
    landed = data.get("date") if isinstance(data, dict) else None
    api.call("DELETE", f"items/{hid}")
    if landed == U:
        return "pre", f"a no-date check-in landed on the UTC date {landed} — the old handler"
    if landed == L:
        return "post", f"a no-date check-in landed on the local date {landed} — #658 is already out"
    return "unknown", f"a no-date check-in landed on {landed}, which is neither frame [{status}]"


# ── seeding ───────────────────────────────────────────────────────────────────────────────────


def seed(ctx) -> None:
    api, f = ctx.api, ctx.framing
    tzid = f["tzid"]
    L, U = local_date(f), utc_date(f)
    horizon = eod_utc(L + timedelta(days=30), tzid)

    def habit(key, note, expect):
        return ctx.fixture(key, "habit", note, expect, lambda title: create_recurring(api, "habit", title, {"type": "daily"}, horizon))

    def checkin(hid, when: date | None, key):
        body = {"done": True} if when is None else {"done": True, "date": when.isoformat()}
        status, data, _ = api.call("POST", f"habits/{hid}/occurrences", body=body)
        landed = data.get("date") if isinstance(data, dict) else None
        ctx.log(f"  {key}: check-in {'(no date)' if when is None else when} -> {landed} [{status}]")

    ctx.heading("M16 equivalence classes (habits)")

    # 1. The defect itself. No `date` ⇒ the pre-#658 handler files it under the UTC date while
    #    `done_at` records the instant; M16 must move the field onto the local date.
    if h := habit(
        "mover",
        "no-date check-in inside the divergence window — the reported defect",
        {"type": "moves", "from": U.isoformat(), "to": L.isoformat()},
    ):
        checkin(h, None, "mover")

    # 2. Two real check-ins, one on each frame. `rekey_habit_occurrence` uses HSETNX, so the move
    #    must refuse rather than collapse them — losing one would be silent data loss.
    if h := habit(
        "collider",
        "check-ins on BOTH the local and the UTC date — the move must refuse",
        {"type": "unchanged", "dates": sorted({L.isoformat(), U.isoformat()})},
    ):
        checkin(h, L, "collider")
        checkin(h, None, "collider")

    # 3. A deliberate back-date: `done_at` is today but the field is days old, so the row carries
    #    no evidence of the UTC-default frame and must be left alone.
    back = L - timedelta(days=5)
    if h := habit(
        "backdated",
        "explicitly back-dated check-in — no UTC-frame evidence, must not move",
        {"type": "unchanged", "dates": [back.isoformat()]},
    ):
        checkin(h, back, "backdated")

    # 4. Already in the local frame, so the migration's `local == stored` guard skips it.
    if h := habit(
        "already-local",
        "check-in explicitly on the local date — already correct",
        {"type": "unchanged", "dates": [L.isoformat()]},
    ):
        checkin(h, L, "already-local")

    # 5. A realistic history strip: several correct days plus one row in the bad frame. Exactly one
    #    row may move; the neighbours are the blast-radius control at close range.
    if h := habit(
        "history",
        "three back-dated days plus one no-date row — only the last may move",
        {"type": "set", "dates": sorted([(L - timedelta(days=n)).isoformat() for n in (3, 2, 1)] + [L.isoformat()])},
    ):
        for n in (3, 2, 1):
            checkin(h, L - timedelta(days=n), "history")
        checkin(h, None, "history")

    # 6. A rescheduled future firing. Whatever row shape this produces carries no `done_at` from
    #    the UTC-default path, so M16 must not touch it.
    if h := habit("rescheduled", "a future firing moved by reschedule — untouched by M16", {"type": "unchanged-from-capture"}):
        src, dst = (L + timedelta(days=2)).isoformat(), (L + timedelta(days=4)).isoformat()
        status, _, _ = api.call("POST", f"habits/{h}/occurrences/{src}/reschedule", body={"new_date": dst})
        ctx.log(f"  rescheduled: {src} -> {dst} [{status}]")

    ctx.heading("controls: kinds M16 must never touch")
    # M16 scans habits only. A chore and an event marked on the same UTC date prove the scan did
    # not widen — a migration that reached them would be a far larger blast radius than claimed.
    if c := ctx.fixture(
        "chore-control", "chore", "chore marked on the UTC date", {"type": "unchanged-from-capture"},
        lambda title: create_recurring(api, "chore", title, {"type": "daily"}, horizon),
    ):
        status, _, _ = api.call("PUT", f"chores/{c}/occurrences/{U.isoformat()}", body={"status": "done"})
        ctx.log(f"  chore-control: mark {U} [{status}]")

    if e := ctx.fixture(
        "event-control", "event", "event marked on the UTC date", {"type": "unchanged-from-capture"},
        lambda title: create_recurring(api, "event", title, {"type": "daily"}, horizon),
    ):
        # NB: events take `{"action": ...}` (SetOccurrencePayload), chores take `{"status": ...}`.
        status, _, _ = api.call("POST", f"events/{e}/occurrences/{U.isoformat()}", body={"action": "done"})
        ctx.log(f"  event-control: mark {U} [{status}]")

    ctx.heading("corpus-derived recurrence shapes (grid controls)")
    seed_corpus_shapes(ctx, L, tzid)


def seed_corpus_shapes(ctx, anchor_day: date, tzid: str) -> None:
    """One definition per distinct recurrence shape in the golden corpus.

    The corpus pins `expandOccurrenceGrid` to a *snapshot* of the Rust (ADR-0053 decision 5).
    Seeding live definitions from the same shapes is what lets the Kotlin half ask a question the
    corpus cannot: does the grid **staging actually serves** still match what the client expands
    offline? A backend change that moves the grid and regenerates nothing leaves the corpus test
    green while every client on the network quietly disagrees with the server.
    """
    by_shape: dict[str, dict] = {}
    for path in sorted(CORPUS_DIR.glob("*.json")):
        case = json.loads(path.read_text())
        if (rec := case.get("recurrence")) is None:
            continue
        entry = by_shape.setdefault(
            json.dumps(rec, sort_keys=True),
            {"recurrence": rec, "cases": [], "outcome": case.get("outcome")},
        )
        entry["cases"].append(path.stem)

    for i, shape in enumerate(list(by_shape.values())[: ctx.corpus_limit]):
        key = f"corpus-{i:02d}"
        rec = shape["recurrence"]
        complete_by = _corpus_complete_by(rec, anchor_day, tzid)
        hid = ctx.fixture(
            key, "habit", f"corpus shape: {', '.join(shape['cases'][:3])}",
            {"type": "unchanged-from-capture"},
            lambda title, r=rec, cb=complete_by: create_recurring(ctx.api, "habit", title, r, cb),
            title_suffix=shape["cases"][0],
        )
        ctx.manifest["corpus"].append(
            {
                "key": key, "kind": "habit", "id": hid, "recurrence": rec,
                "cases": shape["cases"], "corpus_outcome": shape["outcome"],
                "created": hid is not None, "complete_by": complete_by,
            }
        )


def _corpus_complete_by(recurrence: dict, anchor_day: date, tzid: str) -> str:
    """A `complete_by` the create endpoint will accept for this shape — and that anchors it usefully.

    Two constraints pull against each other. `complete_by` becomes the series anchor, so it must be
    near the window we intend to compare or the grid fires nowhere and an empty-vs-empty comparison
    reads as agreement while proving nothing. But the server rejects a rule whose `UNTIL` lands
    before its start ("recurrence end date X is before the start Y"), so an `on_date`-bounded rule
    has to anchor before its own bound and be compared over a window derived from that instead.
    """
    end = recurrence.get("end") or {}
    if end.get("type") == "on_date" and end.get("date"):
        return eod_utc(date.fromisoformat(end["date"]) - timedelta(days=1), tzid)
    return eod_utc(anchor_day, tzid)


# ── readiness ─────────────────────────────────────────────────────────────────────────────────


def readiness(api: Api, manifest: dict):
    """The `mover` fixture's row leaving the UTC date — the migration's own definition of done."""
    mover = next((fx for fx in manifest["fixtures"] if fx["key"] == "mover"), None)
    if mover is None:
        return None
    U = manifest["framing"]["utc_date"]
    L = local_date(manifest["framing"])
    window = {"from": (L - timedelta(days=7)).isoformat(), "to": (L + timedelta(days=7)).isoformat()}

    def probe():
        status, rows, _ = api.get(occurrence_path("habit", mover["id"]), **window)
        if status != 200 or not isinstance(rows, list):
            return None, f"read failed [{status}] {str(rows)[:100]}"
        seen = sorted(r["date"] for r in rows)
        return U not in seen, f"mover is {seen}"

    return probe


# ── verification ──────────────────────────────────────────────────────────────────────────────


def verify(manifest: dict, pre: dict, post: dict, rep) -> None:
    rep.section(
        "M16 equivalence classes",
        "One habit per class the migration distinguishes. `mover` is the defect; every other row",
        "is a way the migration could overreach, and must come through untouched.",
    )
    for fx in manifest["fixtures"]:
        key, item_id, expect = fx["key"], fx["id"], fx.get("expect") or {}
        if not item_id:
            continue
        before, after = occ_dates(pre, item_id), occ_dates(post, item_id)
        kind = expect.get("type")

        if kind == "moves":
            want = sorted(set(before) - {expect["from"]} | {expect["to"]})
        elif kind in ("unchanged", "set"):
            want = sorted(expect["dates"])
        elif kind == "unchanged-from-capture":
            if key.startswith("corpus-"):
                continue  # covered by the grid section
            # Only STORED rows are held to "unchanged": a derived row is recomputed on every read.
            # Grid movement is reported alongside, classified rather than folded in.
            s_before, s_after = stored_dates(pre, item_id), stored_dates(post, item_id)
            rep.check(s_after == s_before, key, f"stored {dates(s_before)} -> {dates(s_after)}")
            if moved := set(before) ^ set(after):
                rep.note(
                    f"{key}: derived grid",
                    f"{dates(sorted(moved))} "
                    + (
                        "— at the window edges: the local-day bound correction"
                        if edge_only(moved, pre.get("window") or {})
                        else "— NOT at a window edge; the grid itself moved"
                    ),
                )
            continue
        else:
            continue

        rep.check(
            after == want,
            key,
            f"{dates(before)} -> {dates(after)}" + ("" if after == want else f"   (expected {dates(want)})"),
        )
