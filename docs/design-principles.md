# Deferno Client — Design Principles

Deferno is task management **tailored for neurodivergent people**. These principles are not
decoration — every feature, screen, and issue is checked against them. They are deliberately
opinionated; when a principle and a convenience conflict, the principle wins.

## Neurodivergent-first UX

1. **Low-overwhelm by default.** Open into today's **Plan**, not the whole backlog. Progressive
   disclosure over dense dashboards. The default view should never induce paralysis.
2. **Decompose to defeat paralysis.** Make hierarchical breakdown (the split / fold / merge model)
   effortless and always within reach. The interface should constantly answer "what's the next small
   step?"
3. **Mood, non-judgmentally.** Mood tracking is supportive context, never a guilt mechanic, streak
   pressure, or performance score.
4. **Gentle, non-punitive language.** No shaming ("overdue!", "you failed"). Lapses are normal and
   forgiven; copy is kind and plain.
5. **Predictable and stable.** Consistent placement, minimal surprise, honor reduced-motion. Novelty
   is a cost, not a feature.
6. **Restraint with nudges.** Notifications and reminders are quiet and opt-in by default — the app
   does not nag.
7. **Optional focus mode.** A single-task, distraction-reduced surface for when everything else is
   too much.
8. **Forgiving interactions.** Easy undo, no destructive surprises; actions are reversible wherever
   possible.

## Accessibility (v1 acceptance criteria — tested, not a later pass)

- **WCAG AA+** color contrast across all states.
- **Dynamic type / font scaling** respected; layouts reflow, never clip.
- **Screen-reader semantics** (TalkBack / VoiceOver) on every interactive element; meaningful labels
  and order.
- **Correct focus order** and **full keyboard operability** (also required for Chromebook/desktop).
- **Honor reduced-motion**; provide non-motion alternatives to animated feedback.
- **Large touch targets** (≥44–48dp/pt) and comfortable spacing.
- **Automated a11y checks in CI** (Compose accessibility checks / Roborazzi a11y; iOS Accessibility
  Audit) so regressions fail the build.

## Design system

- **Mobile/touch-first**, client-owned tokens — **not** coupled to the webui pattern library
  (see ADR-0010). Touch-optimized targets, gesture affordances, thumb-reachable primary actions.
- Aligned to Deferno's identity where stable; accessible **by construction** (contrast pairs and a
  type scale baked into the tokens).

---

_This is a living document. Add principles you hold that aren't captured here — they carry the same
weight as the ones above._
