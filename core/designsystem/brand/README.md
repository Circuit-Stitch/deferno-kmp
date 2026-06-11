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

- **App icon** — the flame is the Android adaptive launcher icon, now as **vector drawables**
  (`app/androidApp/src/main/res/drawable/ic_launcher_{foreground,monochrome}.xml`) over the warm-dark
  `--paper-2` `ic_launcher_background.xml`. Both layers are generated from `flame.svg`: its uniform
  scale+translate transforms map 1:1 onto VectorDrawable `<group>`s (no path baking), and a wrapping
  `<group>` fits the flame to ~72% of the 108dp tile, centered inside the launcher safe zone.
  - **Monochrome (themed icon, Android 13+).** Android discards the layer's RGB and flat-tints it one
    system color (`SRC_IN`), so tone can only live in **alpha**: white regions → 100%, the red flame →
    ~42% (the *perceptual* L\* of `#b83232`, not its linear luminance ~13%, so the flame doesn't wash
    out). The card's checkboxes/lines are built-in holes in the card path, so the 4 red on-card paths
    are omitted there and read as negative-space cut-outs. A perceptual-greyscale *colour* monochrome
    is impossible on Android for this reason — it would render as a flat single-colour blob.
  - The 512 Play Store icon `app/androidApp/src/main/ic_launcher-playstore.png` stays raster — Play
    requires a PNG. To regenerate the vectors after editing `flame.svg`, re-derive the two drawables
    with the same group transforms (the fit `<group>` is `scale 0.57852`, `translate 14.814`).
- **Login & settings logos** — these screens don't exist yet (feature/auth is a scaffold; this work
  covered #25/#27 = Tasks + Plan). The SVGs are staged here so they're in the repo and ready.

## When the login/settings screens land

These are raw SVGs; the Compose design system can't consume SVG directly across platforms. Convert
each to an `androidx.compose.ui.graphics.vector.ImageVector` (e.g. an `ImageVector` builder, or an
Android vector-drawable imported via Android Studio's *Vector Asset* and exposed through the design
system) and surface them as `@Composable` logo accessors here in `core/designsystem`, so login and
settings render them through the theme. Keep these source files as the single source of truth.
