# Brand assets

Source-of-truth brand artwork for the Deferno client. **Circuit Stitch** is the company; **Deferno**
is the product (the flame mark).

| File | What it is | Used by |
| --- | --- | --- |
| `flame.svg` | The Deferno flame mark (red/white) | App launcher icon (already integrated, see below); product logo on the login & settings screens |
| `deferno-logo.svg` | Deferno logo (flame + lockup) | Login / settings (product identity) |
| `deferno-wordmark.svg` | "Deferno" wordmark | Login / settings headers |
| `circuit-stitch.svg` | Circuit Stitch company logo | Login & settings ("a Circuit Stitch product") |
| `circuit-stitch-bare.svg` | Circuit Stitch mark without wordmark | Compact / footer placements |

## Status

- **App icon** — the flame is already wired as the Android adaptive launcher icon: raster foreground
  + monochrome (themed-icon) layers under `app/androidApp/src/main/res/mipmap-*`, on the warm-dark
  `--paper-2` background. The 512 Play Store icon is `app/androidApp/src/main/ic_launcher-playstore.png`.
- **Login & settings logos** — these screens don't exist yet (feature/auth is a scaffold; this work
  covered #25/#27 = Tasks + Plan). The SVGs are staged here so they're in the repo and ready.

## When the login/settings screens land

These are raw SVGs; the Compose design system can't consume SVG directly across platforms. Convert
each to an `androidx.compose.ui.graphics.vector.ImageVector` (e.g. an `ImageVector` builder, or an
Android vector-drawable imported via Android Studio's *Vector Asset* and exposed through the design
system) and surface them as `@Composable` logo accessors here in `core/designsystem`, so login and
settings render them through the theme. Keep these source files as the single source of truth.
