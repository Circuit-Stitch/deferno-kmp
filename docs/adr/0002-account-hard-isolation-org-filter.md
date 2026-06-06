# Account is the hard isolation boundary; Org is an in-account filter

**Context.** A person holds several **fully separate** Deferno identities (e.g. work, personal,
HOA) with different credentials, bearer tokens, and [[Org]] memberships, and switches between them
("fast user switching"). Privacy and security are a top priority.

**Decision.** Model **Account** as the hard partition: each Account has its own bearer token in
platform secure storage (Keystore / Keychain) and its own **encrypted** local database; no data,
auth, or visibility ever crosses between Accounts. **Org** (`owner_org_id`) is a *soft filter
within* the [[Active Account]]. Fast user switching re-points the shared core at the Active
Account's database + token; each Account syncs independently and can be pre-warmed.

**Consequences.** Per-account encryption keys and database files; background sync iterates Accounts;
this model subsumes the single-account and single-org cases as special cases, so we never retrofit.
See `CONTEXT.md` for the Account / Org / User glossary.
