# Theming FUTO Keyboard (and TapSwipe)

Researched 2026-11-14 from the code (no external format documentation
exists; the only link is the theme store, https://keyboard.futo.tech/themes).
Paths are under `java/src/org/futo/inputmethod/latin/uix/`.

## Two ways to make a theme

1. **In the app** (Settings, Theme, Add new theme, "from a picture"):
   pick and crop an image, set transparency, blur, one color (hue,
   chroma, tone), dark mode and key borders. The app builds a full
   Material color scheme from that one color. Simple themes only.
2. **A ZIP file** you write and import (Settings, Theme, Add new theme,
   Load ZIP theme, or open the ZIP from a file manager). Every advanced
   feature is here.

The app has no export. Keep your source ZIP to share or change a theme.
Importing the same `id` again replaces the theme.

## The ZIP

```
mytheme.zip
  theme.txt     TOML, required (a legacy config.json is not imported)
  bg.jpg        images and fonts, referenced by path in theme.txt
  key.png
  font.ttf
```

A path may also be a `data:image/...;base64,` URI.

## theme.txt

Reference: `theme/serialization/Toml.kt` (schema), `theme/decodeUtils.kt`
(selectors).

- Top level: `name`, `author`, `id` (required, 3+ characters, not ending
  in `_`), `version`, `description`. A comment `# Format version: 1`
  marks the format version.
- `[options]`: `auto_borders` (true), `center_hints` (false),
  `roundedness` (1.0), `scale_text`, `scale_hints` (1.0), `weight_text`
  (400), `weight_hints` (500).
- `[options.font]`: `font = "file.ttf"`.
- `[options.background]`: `image`, `opacity` (0.5, how much the image
  shows through `keyboard_surface`), `action_bar_opacity` (0.5),
  `cropping = [l, t, r, b]` as fractions.
- `[colors]`: all 44 colors are required. 36 Material 3 roles (`primary`,
  `on_primary`, containers, secondary, tertiary, background, surface and
  its variants, inverse, error, outline, scrim, `surface_container_*`)
  and 8 keyboard colors:
  - `keyboard_surface` (background), `keyboard_surface_dim` (action bar
    without borders), `keyboard_container` (key fill),
    `keyboard_container_variant` (function keys),
    `on_keyboard_container` (labels), `keyboard_press`,
    `keyboard_container_pressed`, `on_keyboard_container_pressed`.
- Color values: `#RGB`, `#RRGGBB`, `#RRGGBBAA` (alpha last); another
  color's name (`secondary = "primary"`, the named one must be hex); or a
  Material You wallpaper color, `system_<palette><tone>` (palettes
  `primary`, `secondary`, `tertiary`, `neutral`, `neutralVariant`,
  `error`; tones 0 to 100), for example `system_primary40`.
- The import rejects a theme whose 8 main background/foreground pairs
  are too close in brightness (luminance difference below 0.08). Unknown
  keys are reported but do not stop the import.

## Key styling by rules

```toml
[[asset.border]]           # a key background image (nine-patch)
name = "glow.png"
target_density = 640
slicing = [0.3, 0.3, 0.7, 0.7]   # the part that stretches
padding = [0, 0, 0, 0]
gap = [1, 1, 1, 1]               # 0 fills the gap between keys
background_tint = "#ffffff"      # multiply tint
foreground_tint = "#777777"      # label color on these keys
outline_tint = "#000000"         # label outline
outline_width = 3

[[matchrules.border]]
selector = "spacebar"
asset = "glow.png"

[[asset.icon]]
name = "myshift.png"

[[matchrules.icon]]
selector = "icon shift"
asset = "myshift.png"
```

A selector is words separated by spaces; a key must match all of them;
the first matching rule wins:

- Style: `normal`, `functional`, `action`, `spacebar`, `stickyon`,
  `stickyoff`, `morekey`, `nobackground`.
- Content: `label X`, `icon X`, `code N`, `outputtext X`.
- Layout: `layout NAME`.
- Position: `row N`, `col N` (negative counts from the end),
  `rowmod a n`, `colmod a n` (stripes, checkerboards).
- Shape: `ratio Tall|Squarish|Wide|...`.
- Other: `pressed`, `popup` (key preview), `morekeysbox` (long-press
  panel), `layer N` (extra drawables stacked on one key).

## Limits

- One color scheme per file: no light and dark pair.
- No gradients or shaders in TOML (built-in Kotlin presets have them).
- No layout, key size or animation changes.

## TapSwipe features and themes

| Feature | Takes its color from | Styleable by a theme |
|---|---|---|
| Word trails, swipe trail | `primary` | Only through `primary` |
| Auto space countdown bar | `primary` | Only through `primary` |
| Master Mode dots | key label color (`on_keyboard_container`, or a border rule's `foreground_tint`), theme font and text weight | Partly; no selector for dots |
| Peck mode borders | `keyboard_container`, `keyboard_container_variant`, `on_keyboard_container` | Shown only when the theme has borders off; a matching border rule hides them |

Master Mode and peck mode are not selector words. Styling them
separately would need code changes (for example a new `KeyQualifier`).
