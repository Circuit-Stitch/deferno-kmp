"""Scenarios: one module per migration under test.

A scenario module supplies six things. Everything else — capturing the client-visible surfaces,
diffing them, classifying stored vs derived rows, the blast-radius scan, the change-stream and
wire-shape checks, cleanup — is the engine's job and is shared.

```python
NAME    = "m16-habit-local-date"     # the run-directory name and the --scenario argument
TITLE   = "M16 …"                    # one line, printed at the top of every phase
TARGETS = "Deferno#658 · …"          # which PR / migration this pins
PREFIX  = "[M16-PROBE]"              # goes in every seeded item's title, so cleanup is exact

def preconditions(api, framing) -> list[tuple[bool, str, str]]:
    '''(ok, name, detail) — is NOW a valid moment to seed? Any False blocks `seed`.'''

def deployed_side(api, framing) -> tuple[str, str]:
    '''"pre" | "post" | "unknown", plus a human detail. MEASURE it — create a throwaway item and
    observe the behaviour — rather than trusting a version string.'''

def seed(ctx) -> None:
    '''Write fixtures via ctx.habit(...) / ctx.fixture(...) / ctx.log(...).'''

def readiness(api, manifest):
    '''Return `() -> (ready: bool, detail: str)` that answers "has the migration landed?", or None
    to skip waiting. The runner is fire-and-forget, so `capture post` MUST NOT assume it has.'''

def verify(manifest, pre, post, rep) -> None:
    '''The scenario-specific section: its equivalence classes and their expectations.'''
```

Register the module in `ALL` below.
"""

from . import m16_habit_local_date

ALL = [m16_habit_local_date]


def by_name(name: str):
    for module in ALL:
        if module.NAME == name:
            return module
    raise SystemExit(f"unknown scenario {name!r}; known: {', '.join(m.NAME for m in ALL)}")
