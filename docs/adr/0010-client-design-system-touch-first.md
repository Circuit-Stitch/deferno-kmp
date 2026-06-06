# Client design system: mobile/touch-first, independent of the webui pattern library

**Context.** The Deferno webui has a "pattern library" (its single source of visual truth), but it's
still **highly fluctuating** and is web-/pointer-centric. The native clients need a touch- and
mobile-optimized experience, not a port of web tokens.

**Decision.** The clients **own their own design system** — mobile/touch-first design tokens (color,
type scale, spacing, contrast pairs, motion, ≥44–48dp/pt touch targets, gesture affordances) defined
for the native platforms and **not** inherited from or coupled to the webui pattern library. We align
to Deferno's brand identity where it's stable, but take **no build-time dependency on a moving
target**. Accessibility criteria (WCAG AA+ contrast, dynamic type, reduced motion, screen-reader
semantics — see `docs/design-principles.md`) are independent of the token source and still apply.

**Consequences.** Web and mobile design can diverge intentionally; mobile is properly touch-optimized;
no coupling to fluctuating web tokens. If the web design later stabilizes, a *shared* cross-platform
token source can be revisited then.

**Rejected.** Inheriting the webui pattern-library tokens as the client source of truth (fluctuating;
web/pointer-centric).
