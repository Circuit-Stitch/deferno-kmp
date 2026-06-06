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
