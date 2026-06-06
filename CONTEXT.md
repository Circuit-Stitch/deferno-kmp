# Deferno Client

The native Deferno client — Android first, with iOS and desktop to follow over a shared
Kotlin Multiplatform core. This glossary defines only the language specific to the *client*.
The core Deferno domain language (Item, Task, Habit, Chore, Event, Occurrence, Org, Plan,
Workspace, Canonical ref, Alias, Subtask/Child, …) is owned by the **Deferno backend's
`CONTEXT.md`** (the canonical source, in the `Kyle-Falconer/Deferno` repo) and is not
duplicated here — this file only adds client-specific terms and disambiguations.

## Language

### Identity & switching

**Account**:
One Deferno identity the person has signed into the app — its own credentials (login name +
password), its own bearer token, and its own [[Org]] memberships. The unit the person adds,
removes, and switches between. Accounts are **fully isolated** from one another: no data, auth,
or visibility ever crosses between them.
_Avoid_: profile, login, user (a "user" is the backend identity an Account signs in *as*), workspace.

**Active Account**:
The Account a window is showing and syncing — conceptually **one per window** (a single window in
v1; multi-window is reserved, not yet built). Changing it is "fast user switching."
_Avoid_: current user, selected profile.

**Account switch**:
Changing which Account is the [[Active Account]]. Distinct from changing [[Org]]: a switch crosses
the isolation boundary into a different identity, whereas an Org change stays within one Account.
_Avoid_: re-login (a switch need not re-authenticate), profile swap.

### Account vs Org vs User (disambiguation)

These three are routinely conflated; in this client they are kept distinct:

- **Account** — a *client* concept: one signed-in Deferno identity on this device. The person may
  hold several (e.g. work, personal, HOA), each with different credentials and [[Org]] memberships.
  The **hard isolation boundary**.
- **Org** *(inherited)* — a *within-Account* ownership boundary. One Account belongs to one or more
  Orgs (a [[Personal org]] plus any shared ones); every Item carries `owner_org_id`. Selecting an Org
  filters what you see *inside* the [[Active Account]]; it never crosses accounts.
- **User** *(inherited, backend)* — the server-side identity (`GET /auth/me`) an Account authenticates
  as. One Account ⇄ one backend User.

### Item state (disambiguating "status")

The API overloads `status` across three unrelated axes (and ships six wire enums with inconsistent
casing). The client names them distinctly and condenses them in the DTO→domain mapper; the wire
DTOs stay faithful, the domain types are clean.

**Working state**:
A [[Task]]'s progress through its own lifecycle — Open, In-progress, In-review, Done, Dropped.
_Avoid_: status, task status.

**Definition state**:
The "light switch" on a recurring definition ([[Habit]]/[[Chore]]/[[Event]]) — whether it is live or
shelved: Active or Archived. (The API also carries `in-review`; retained faithfully but its meaning
is unresolved.) This is the state of the *definition*, never of any single firing.
_Avoid_: status, DefStatus, archived flag.

**Occurrence state**:
How one dated firing (an [[Occurrence]]) of a recurring definition went — Scheduled, In-progress,
Done (with on-time / late punctuality), Skipped, Missed. The client only ever *writes* a coarse
action (start / complete / skip); the finer read states — Scheduled, Missed, and the on-time/late
split — are **server-derived**.
_Avoid_: status, OccurrenceStatus, done_on_time/done_late.

### Credentials

**Personal access token (PAT)**:
The opaque bearer credential an [[Account]] stores in its secure vault and sends as `Authorization:
Bearer`. The **durable native-client credential**: OAuth/PKCE (when built) is only one way to *mint*
one, not a credential the client persists (ADR-0012). Minted/revoked via the web client's User
Settings or `…/auth/tokens`.
_Avoid_: access token, OAuth token, session token, API key. (The wire/spec name is "API token"; the
product term is **PAT**.)
