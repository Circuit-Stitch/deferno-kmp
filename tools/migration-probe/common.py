"""Shared machinery for the migration probe — HTTP, clock framing, row classification, reporting.

Imported by both `probe.py` (the engine) and every module under `scenarios/`. It lives in its own
module rather than in `probe.py` precisely so a scenario can import it: `probe.py` runs as
`__main__`, so a scenario importing *it* would re-execute the CLI.

Stdlib only, on purpose. The two halves of a probe run may be days apart, and nothing about the
environment should need re-creating in between.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

# Seconds between readiness polls. A migration takes well under a second once it starts; the wait
# exists for the spawn + replica-cache delay, not for the work. Polling faster buys nothing.
POLL_SECONDS = 3


# ── HTTP ──────────────────────────────────────────────────────────────────────────────────────


class Api:
    """The Deferno REST surface, envelope-unwrapped.

    Every response is `{"version": ..., "data": ...}` or `{"version": ..., "error": ...}`.
    `call` returns `(status, data_or_error, raw_envelope)` and **never raises on a non-2xx**, so a
    probe step can record a failure as a finding rather than aborting a run that may have taken a
    deploy window to set up.
    """

    #: Cloudflare in front of staging answers `1010 browser_signature_banned` to the default
    #: `Python-urllib/*` User-Agent. Any other token gets through.
    USER_AGENT = "deferno-migration-probe/1.0"

    def __init__(self, base: str, token: str):
        self.base = base.rstrip("/")
        self.token = token

    def call(self, method: str, path: str, *, params=None, body=None):
        url = f"{self.base}/{path.lstrip('/')}"
        if params:
            url += "?" + urllib.parse.urlencode(params)
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Accept", "application/json")
        req.add_header("User-Agent", self.USER_AGENT)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                status, raw = resp.status, resp.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        except urllib.error.URLError as e:
            return 0, {"code": "transport", "message": str(e)}, None
        try:
            env = json.loads(raw)
        except json.JSONDecodeError:
            return status, {"code": "unparseable", "message": raw[:400].decode(errors="replace")}, None
        if isinstance(env, dict) and "data" in env:
            return status, env["data"], env
        if isinstance(env, dict) and "error" in env:
            return status, env["error"], env
        return status, env, env

    def get(self, path, **params):
        return self.call("GET", path, params=params or None)

    def must_get(self, path, **params):
        status, data, _ = self.get(path, **params)
        if status != 200:
            raise SystemExit(f"GET {path} failed [{status}]: {data}")
        return data


def load_token(path: Path) -> str:
    if not path.exists():
        raise SystemExit(f"no PAT file at {path} (pass --pat-file)")
    return path.read_text().strip()


def read_json(path: Path):
    if not path.exists():
        raise SystemExit(f"missing {path} — run the earlier phase first")
    return json.loads(path.read_text())


def write_json(path: Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2, sort_keys=True) + "\n")
    return path


# ── clock framing ─────────────────────────────────────────────────────────────────────────────


def framing(api: Api) -> dict:
    """The account's two calendar frames, resolved from the **account's** zone, not the device's.

    Most Deferno date defects live in the gap between these two, so nearly every scenario needs
    them: `local_date` is what the read surfaces speak, `utc_date` is what a handler defaulting to
    `Utc::now().date_naive()` writes. West of UTC they disagree for the last hours of every local
    day, and a scenario that reproduces such a defect can only be seeded while they do.
    """
    settings = api.must_get("auth/me/settings")
    me = api.must_get("auth/me")
    tzid = settings.get("time_zone") or "UTC"
    now_utc = datetime.now(timezone.utc)
    now_local = now_utc.astimezone(ZoneInfo(tzid))
    return {
        "user_id": me["id"],
        "username": me.get("username"),
        "tzid": tzid,
        "now_utc": now_utc.isoformat(),
        "now_local": now_local.isoformat(),
        "utc_date": now_utc.date().isoformat(),
        "local_date": now_local.date().isoformat(),
        "frames_diverge": now_utc.date() != now_local.date(),
    }


def local_date(framing_dict: dict) -> date:
    return date.fromisoformat(framing_dict["local_date"])


def utc_date(framing_dict: dict) -> date:
    return date.fromisoformat(framing_dict["utc_date"])


# ── writing fixtures ──────────────────────────────────────────────────────────────────────────


def eod_utc(d: date, tzid: str) -> str:
    """The inclusive end-of-day sentinel for a local date, as the API's `complete_by`."""
    local = datetime(d.year, d.month, d.day, 23, 59, 59, tzinfo=ZoneInfo(tzid))
    return local.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def create_recurring(api: Api, kind: str, title: str, recurrence: dict, complete_by: str):
    """Create a habit / chore / event. Returns `(id_or_None, error_or_None)`.

    **`complete_by` at create time IS the series anchor** — the server stores it as
    `series.dtstart_local` in the account's zone (verified against staging 2026-08-05). It is not
    merely a deadline, so it decides where the definition's whole grid starts: pick it far in the
    future and the grid fires nowhere near any window worth capturing.
    """
    body = {"title": title, "recurrence": recurrence, "complete_by": complete_by}
    status, data, _ = api.call("POST", f"{kind}s", body=body)
    if status not in (200, 201) or not isinstance(data, dict) or "id" not in data:
        return None, {"status": status, "error": data}
    return data["id"], None


def occurrence_path(kind: str, item_id: str) -> str:
    """`GET` here **requires** `from` and `to` — omitting them is a 400, not an unbounded read."""
    return f"{kind}s/{item_id}/occurrences"


# ── reading rows back ─────────────────────────────────────────────────────────────────────────


def occ_rows(cap: dict, item_id: str) -> list[dict]:
    rows = (cap.get("occurrences", {}).get(item_id) or {}).get("rows")
    return [r for r in rows if isinstance(r, dict)] if isinstance(rows, list) else []


def occ_dates(cap: dict, item_id: str) -> list[str]:
    """Every dated row a definition answers with — stored *and* derived."""
    out = [r.get("date") or r.get("scheduled_date") for r in occ_rows(cap, item_id)]
    return sorted(d for d in out if d)


def stored_dates(cap: dict, item_id: str) -> list[str]:
    """Only the rows that record something a USER did.

    The occurrence endpoints answer with two kinds of row, and telling them apart decides how
    alarmed to be about a diff. A **stored** row carries evidence — `done_at` / `completed_at`, or
    a status the grid cannot derive on its own — and is the thing a data migration rewrites. A
    **derived** row is the rule's grid projected into the requested window (`scheduled`, `missed`,
    nothing recorded); it is recomputed on every read, so it moving is a read-side change with no
    write behind it.

    Merging the two would let a window-boundary shift masquerade as lost history — and, far worse,
    would let real lost history hide inside one.
    """
    out = []
    for r in occ_rows(cap, item_id):
        d = r.get("date") or r.get("scheduled_date")
        if not d:
            continue
        evidence = r.get("done_at") or r.get("completed_at")
        derived = r.get("status") in (None, "scheduled", "missed", "open")
        if evidence or not derived:
            out.append(d)
    return sorted(out)


def edge_only(changed: set[str], window: dict) -> bool:
    """Is every changed date at a boundary of the requested window?

    A read bounded in UTC instants but answering in local dates clips the window's last local day
    and leaks the day before its first, west of UTC. So a corrected read gains `to` and loses
    `from - 1` — and nothing in between. Deferno#658 fixed exactly this in `series_scheduled_dates`,
    and any future change to windowing will show the same signature.
    """
    if not changed or not window:
        return False
    first = date.fromisoformat(window["from"])
    return changed <= {(first - timedelta(days=1)).isoformat(), window["to"]}


def dates(seq, cap: int = 6) -> str:
    """A list short enough to read. A derived grid runs to dozens of rows, and a diff nobody reads
    is a diff nobody checks."""
    seq = list(seq)
    if len(seq) <= cap:
        return str(seq)
    head = ", ".join(repr(d) for d in seq[: cap - 2])
    tail = ", ".join(repr(d) for d in seq[-2:])
    return f"[{head}, … +{len(seq) - cap} more … , {tail}]"


# ── reporting ─────────────────────────────────────────────────────────────────────────────────


class Report:
    """Streams results under the section headings as they are decided.

    Deliberately not batched to the end: a reader tracing "why did this row move" needs the verdict
    under the heading that framed it, not in one undifferentiated block afterwards.
    """

    NAME_WIDTH = 52

    def __init__(self):
        self.fails = 0

    def section(self, title: str, *blurb: str):
        print()
        print(f"── {title} ──")
        for line in blurb:
            print(f"   {line}")

    def _row(self, status: str, name: str, detail: str):
        if status == "FAIL":
            self.fails += 1
        if len(name) > self.NAME_WIDTH:
            name = name[: self.NAME_WIDTH - 1] + "…"
        print(f"  {status:<4}  {name:<{self.NAME_WIDTH}}  {detail}")

    def check(self, ok: bool, name: str, detail: str = "", severity: str = "FAIL"):
        self._row("PASS" if ok else severity, name, detail)
        return ok

    def note(self, name: str, detail: str = ""):
        self._row("NOTE", name, detail)
