# Families the wire cannot carry persist device-locally and are wiped at cutover

**Status.** Accepted (#416).

**Date.** 2026-08-11

**Issue.** #416

**Context.** ADR-0056 clamps what the client sends to what the four-kind wire round-trips. Five
[[Family]] members fall outside it: the bound that says whether a thing has an endpoint, the verdict
on whether a goal obtained, the purpose edges an item points at, deontic obligation, and persistence
policy. Those are the parts of the model that carry its argument, so withholding all five until the
backend lands would leave the migration unable to demonstrate the point of itself.

Sending them is not available. Storing them on the device is, and the client already runs a
per-Account database, so the mechanism costs one table rather than an architecture.

What follows is the question of what happens to that data when the wire grows the fields. A
capability gate on the outbox would drain it correctly and costs real work to build and to test. The
app ships to nobody, so the data in question is the developer's own dogfood data, and the pre-launch
posture has been consistent about not building carry-forward for that.

**Decision.** The five families persist in one device-local [[Shadow store]], and no promotion path is
built.

- **One table in `core/database`**, keyed by item id, holding the plugin values as encoded rows
  alongside the wire-backed cache rather than inside it.
- **The shadow store never reaches the outbox.** No mutation is enqueued for it, and a refresh from
  the server never clears it.
- **A shadowed value is marked as not synced** wherever a person can act on it, so the limitation is
  visible at the point of use rather than recorded only here.
- **At cutover the table is dropped**, along with the recipes and the four kinds. Recovering the
  values is a one-off script if it is wanted at all, written at that point rather than designed for
  now.

**Considered & rejected.**

- **A capability-gated outbox that drains when the server grows each field.** Correct, and it is the
  right answer once the app has users. Today it builds carry-forward machinery for data that only the
  developer holds, which ADR-0034 and the wider pre-launch posture have consistently declined.
- **Refuse the five families until the backend lands.** The safest option, and it postpones every
  reading that makes the re-cut worth doing. Aspect, drive and lapse would all read as unknown, so the
  target recipes could not be exercised at all before the cutover.
- **Accept the writes and drop whatever the wire cannot carry on the next refresh.** This silently
  destroys what a person recorded, which is rejected outright.

**Consequences.** The full model becomes reachable on one device immediately, which is what makes the
target recipes testable before the backend moves. Anything recorded there is single-device and is
expected to be lost. That is a real cost, and it is the reason the limitation is surfaced in the
interface rather than left implicit.

A second consequence is that two stores now answer for one item. That is tolerable only because the
split runs along family lines and the boundary is mechanical: a family is wire-backed or it is not,
and nothing lives in both. A family that later gains a wire field moves wholesale, and the round-trip
gate in ADR-0056 is what proves the move landed.
