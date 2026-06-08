# Contributing to Deferno

Thanks for your interest in the Deferno client. Contributions are welcome — bug
reports, fixes, and features.

This project is open source under the **Apache License, Version 2.0** (see
[`LICENSE`](LICENSE)). Deferno is the open **client**; the backend service is
separate and proprietary.

## Licensing of contributions: inbound = outbound

Every contribution you submit is licensed to the project under the **same Apache
License, Version 2.0** that covers the rest of the client. This is the
"inbound = outbound" model: what comes in is under the same license that goes
out. By contributing, you also extend the patent grant in Section 3 of the
Apache License for your contribution.

**There is no CLA** (Contributor License Agreement) to sign — we keep
contribution frictionless. Instead, we use the **Developer Certificate of Origin
(DCO)**: a lightweight, per-commit attestation that you have the right to submit
the code under this license.

> Note on branding: the Apache License grants no trademark rights (Section 6).
> The **"Deferno"** name and the flame logo/branding are reserved — see
> [`NOTICE`](NOTICE). Contributing does not grant you rights to that branding,
> and forks must rebrand. This does not restrict your code contributions in any
> way.

## Sign off your commits (DCO)

Each commit must be **signed off** to certify the Developer Certificate of Origin
(full text below). Signing off is a single line at the end of the commit message:

```
Signed-off-by: Jane Developer <jane@example.com>
```

Git adds this line for you automatically with the `-s` / `--signoff` flag:

```sh
git commit -s -m "Fix the thing"
```

The name and email in the sign-off must match the commit author and be a real
identity (a pseudonym you go by is fine; an anonymous contribution is not). To
sign off every commit by default, set up a commit template or alias, or amend a
commit you forgot to sign with `git commit --amend -s`. To sign off a whole
branch retroactively: `git rebase --signoff main`.

A DCO check runs on pull requests; PRs whose commits are not all signed off will
be flagged until they are.

### Developer Certificate of Origin 1.1

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

## How to contribute

1. **Open or find an issue.** Issues live in GitHub at
   [`Circuit-Stitch/deferno-kmp`](https://github.com/Circuit-Stitch/deferno-kmp/issues).
   For anything non-trivial, please open (or comment on) an issue first so the
   approach can be agreed before you invest time.
2. **Fork and branch.** Create a topic branch off `main`.
3. **Make your change**, following the existing code style and architecture. The
   project's conventions, module layout, and architectural decisions are
   documented in [`CLAUDE.md`](CLAUDE.md), [`CONTEXT.md`](CONTEXT.md), and
   [`docs/adr/`](docs/adr/). New dependencies go through the version catalog
   (`gradle/libs.versions.toml`); build config lives in `build-logic`.
4. **Add tests.** This project tests on the JVM-fast path (ADR-0006). Keep the
   shared-core coverage gate green.
5. **Build and test locally:**
   ```sh
   ./gradlew check          # unit tests (commonTest on JVM + Android host)
   ./gradlew :koverVerify   # the merged shared-core coverage gate CI enforces
   ./gradlew build          # full build (Android/JVM; iOS klibs cross-compile)
   ```
   > Running iOS tests and linking the iOS framework require macOS; on
   > Linux/Windows those tasks self-disable (ADR-0006).
6. **Sign off every commit** (`git commit -s`, see above).
7. **Open a pull request** against `main`. Describe what changed and why, and
   link the issue it addresses. Keep PRs focused and reviewable.

## Reporting bugs and requesting features

Open a GitHub issue with enough detail to reproduce or understand the request:
what you expected, what happened, and the platform/version. Security-sensitive
reports should not be filed as public issues — see below.

## Third-party code

If your change adds or updates a bundled dependency, update
[`THIRD-PARTY-LICENSES`](THIRD-PARTY-LICENSES) (and [`NOTICE`](NOTICE) if the
component requires attribution there). Only add dependencies with a license
compatible with Apache-2.0 redistribution; avoid copyleft licenses that would
reach a shipped artifact (test-scope dependencies are evaluated separately).

## Code of conduct

Be respectful and constructive. Harassment or abusive behavior is not welcome.
