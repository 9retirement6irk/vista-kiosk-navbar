# Plugin SDK gaps

These are gaps in the Kiosk Satellite plugin SDK (SDK 1, KS 2026.10.21) that came up while building this plugin. Each entry describes what we needed, what we did instead, and a suggested change, so it can go to the maintainer as written. Items are listed roughly by impact.

## 1. Overlays cannot reserve space in the dashboard

**Need:** A bar anchored to an edge should push the dashboard in, like a system bar, so the last cards are not hidden underneath it.

**Today:** Overlays float over the WebView. A plugin cannot change the WebView's insets or padding, and it cannot run JavaScript or CSS in the page. Every user has to add padding to each dashboard by hand, for example with card-mod.

**Suggestion:** An `OverlaySpec.reserveSpace(true)` option, or a host call such as `setContentInsets(left, top, right, bottom)` in dp, that pads the main WebView while the overlay is shown.

## 2. No structured or long settings

**Need:** A list of items, where each menu holds a list of entries.

**Today:** Strings and secrets are capped at 512 characters, and a plugin has at most 50 settings. We flattened the configuration into 7 fixed slots of 4 fields each, and packed menus into one string (`Label|icon|target; …`). With KS camera view IDs at 32 characters each, a 9-camera menu does not fit.

**Suggestion:** Any one of these would work:
- a multiline `text` type with a larger limit, such as 8 KB
- a `json` type, validated by the plugin, with a code editor in Remote Admin
- a `list` type with per-row sub-fields

Each option should keep fleet sync working.

## 3. Camera views cannot be listed

**Need:** Let users pick a camera view by name, or show the view's name in a menu.

**Today:** `showCameraView` needs the view ID, and no read command lists the configured views. Users have to find the 32-character IDs in an exported config.

**Suggestion:** A `getCameraViews` read command that returns `[{id, name}]`, or `showCameraView` accepting the view name.

## 4. Overlay positioning is limited to nine anchors with one inset

**Need:** Open a menu just above the item that opened it, as lovelace-navbar-card does.

**Today:** `OverlaySpec` offers nine anchors and a single inset that applies to every edge. A menu can only be centered over the bar, and the inset limit of 200 dp caps how far from the edge it can sit.

**Suggestion:** Per-edge insets, such as `inset(left, top, right, bottom)` or `offset(x, y)` in dp. Even better would be a position relative to a point the plugin supplies in screen coordinates.

## 5. A small overlay cannot detect a tap outside it

**Need:** Close a popup menu when the user taps elsewhere, which is standard popup behavior.

**Today:** A touchable overlay receives only touches inside its rectangle, and touches outside go to the dashboard unseen. The only workaround is a transparent full-screen overlay, which blocks the dashboard. We close the menu on back, on navigation, or when its item is tapped again.

**Suggestion:** `OverlaySpec.closeOnOutsideTouch(true)`, which removes the overlay and sends `overlay.closed`. The touch could optionally pass through to the dashboard as well.

## 6. Plugin code cannot read bundled assets

**Need:** Load an icon font that ships with the plugin.

**Today:** Files under `assets/` are available only to screensaver renderers. To use the Material Design Icons font in a native view, we deflate it and base64-encode it into Java string constants (about 850 KB of generated source). On first use we write it to the cache directory.

**Suggestion:** `host.assetPath(name)`, `host.openAsset(name)` or `host.assetTypeface(name)` for files under `assets/`.

## 7. No Material Design Icons for native views

**Need:** Render `mdi:` icons the way Home Assistant users write them.

**Today:** Every plugin that wants MDI icons has to bundle its own copy of the font. KS already ships icons for its own UI.

**Suggestion:** Add `KsTheme.icon(name)`, returning a Drawable or a glyph and Typeface pair, using KS's own icon set.

## 8. `haNavigate` rejects query strings

**Need:** Open view paths that carry parameters, such as the Advanced Camera Card's `?advanced-camera-card-action.camera_select=garage`.

**Today:** `haNavigate` accepts only `^/?seg(/seg)*$`. We fall back to `loadUrl` with the origin of the current URL, which does a full page load instead of HA's in-app navigation.

**Suggestion:** Let `haNavigate` accept a query string and fragment, using the same credential and scheme checks as `loadUrl`.

## 9. No list of Home Assistant dashboards and views

**Need:** Offer the user their dashboards and views to choose from, with titles and icons, instead of typing paths.

**Today:** No read command lists them. The plugin has no HA credentials, by design, so it cannot fetch the Lovelace config itself.

**Suggestion:** A `getDashboards` read command that returns `[{urlPath, title, icon, views: [{path, title, icon}]}]` through KS's HA connection. A `dashboard` setting type with a picker, like `entity`, would be the friendlier version.

## 10. Threading contract for `showOverlay` is unspecified

**Need:** Call `showOverlay` and `hideOverlay` from both the plugin worker and the main thread, for example in click listeners, without deadlocking.

**Today:** The docs say KS builds the view on the main thread through the factory. They do not say whether `showOverlay` blocks until the view exists, or whether `create` and `onDestroy` can run inside the call when it is made from the main thread. We had to design defensively for both orderings.

**Suggestion:** Document it. Something like: "showOverlay never blocks. From the main thread, create runs after the call returns."

## 11. Unclear whether fleet sync carries plugin settings

**Need:** Configure the bar once on the fleet leader and have every display follow.

**Today:** It is not documented whether native fleet sync includes plugin installs, enabled state or plugin settings.

**Suggestion:** Document the behavior, and ideally sync plugin settings for plugins installed from the same repository and version.

## 12. `haNavigate` reports failure after a successful move to another dashboard

**Need:** Use the boolean result to decide whether to fall back to `loadUrl`.

**Today:** Measured on KS 2026.10.21: `haNavigate {path: /bedroom-wall-tablet/toggles}` from `/wall-tablet/0` moved the page within 0.4 s, since `browser.state` fired and `getDashboardState` showed the new path. About 4 s later the command resolved `false`. A plugin that trusts the result reloads a page that is already shown. We now compare the reported page to the target before falling back.

**Suggestion:** Resolve `true` once the WebView reports the new path, including for a move to another dashboard, or document what `false` means.

## 13. Overlays have no show, hide or replace transition

**Need:** Slide the bar into its tab and back, and fade a menu in.

**Today:** `showOverlay` and `hideOverlay` swap views instantly. A plugin can animate inside its own view, but not the overlay's appearance or removal, because KS removes the view as soon as `hideOverlay` is called. Changing an overlay's size means replacing it, which also happens instantly.

**Suggestion:** `OverlaySpec.enter(...)` and `exit(...)` with a few stock transitions, such as fade and slide from the anchor edge. Alternatively, call a `factory.onExit(view, done)` hook before removal.

## 14. Template tooling on Windows

- `tools/build.py` only finds `build-tools/*/d8`, but Windows ships `d8.bat`. We added the `.bat` glob.
- `tools/test_plugin_assets.py` creates symlinks and file names containing `?` and `#`. Both fail on Windows without elevated privileges. We skip those two tests on Windows.
