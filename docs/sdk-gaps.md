# Plugin SDK gaps

These are gaps in the Kiosk Satellite plugin SDK (SDK 1, KS 2026.10.21) that came up while building this plugin. Each entry describes what we needed, what we did instead, and a suggested change, so it can go to the maintainer as written. Items are listed roughly by impact.

## 1. Overlays cannot reserve space in the dashboard

**Need:** A bar anchored to an edge should push the dashboard in, like a system bar, so the last cards are not hidden underneath it.

**Today:** Overlays float over the WebView. A plugin cannot change the WebView's insets or padding, and it cannot run JavaScript or CSS in the page. Every user has to add padding to each dashboard by hand, for example with card-mod.

**Suggestion:** An `OverlaySpec.reserveSpace(true)` option, or a host call such as `setContentInsets(left, top, right, bottom)` in dp, that pads the main WebView while the overlay is shown.

## 2. No structured or long settings

**Need:** A list of items, where each menu holds a list of entries, that is comfortable to edit on a wall panel.

**Today:**
- A plugin has at most 50 settings, and strings are capped at 512 characters.
- Every string setting is a single line: a `TextFormField` without `maxLines` on the kiosk (`plugin_settings.dart`) and an `<input type=text>` in Remote Admin (`plugins.js`). Line breaks cannot be typed.
- So a menu is packed into one long line (`Label = target; Label = target`). With 32-character camera view IDs, at most about 12 cameras fit, and editing that line on a tablet is hard.
- One setting per menu entry does not fit either. Each entry needs a label and a target, and the bar already needs 35 settings, which leaves room for about 7 entries across all menus.

**Suggestion:** Any one of these would work:
- a multiline `text` type with a larger limit, such as 8 KB, edited in a text area
- a `list` type with per-row sub-fields, for example label, icon and target
- a `json` type, validated by the plugin, with a code editor in Remote Admin

Each option should keep fleet sync working.
## 3. Camera views cannot be listed

**Need:** Let users pick a camera view by name, or show the view's name in a menu.

**Today:** `showCameraView` needs the view ID, and no read command lists the configured views. Users have to find the 32-character IDs in an exported config.

**Suggestion:** KS already has `cameraGetConfig`, but it is not on the plugin allowlist. Expose a sanitized read, `getCameraViews`, that returns `[{id, name, cameraCount}]` with no stream URLs or credentials. With it, a plugin can build a camera menu or grid of every view with no setup. Separately or as well, let `showCameraView` accept the view name. `cameraPutView` already rejects duplicate names (case-insensitive), so a name is unambiguous and short enough to fit in a setting.

## 4. Overlay positioning is limited to nine anchors with one inset

**Need:** Open a menu just above the item that opened it, as lovelace-navbar-card does.

**Today:** `OverlaySpec` offers nine anchors and a single inset that applies to every edge. A menu can only be centered over the bar, and the inset limit of 200 dp caps how far from the edge it can sit.

**Suggestion:** Per-edge insets, such as `inset(left, top, right, bottom)` or `offset(x, y)` in dp. Even better would be a position relative to a point the plugin supplies in screen coordinates.

**Our workaround:** We put the menu inside the bar's own overlay, which grows to fit it, and position the menu within the view. This works partly because of gap 15, below.

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

**Today:** The docs say KS builds the view on the main thread through the factory. They do not say whether `showOverlay` blocks until the view exists, or whether `create` and `onDestroy` can run inside the call when it is made from the main thread. We had to design defensively for both orderings. In the end, all our overlay calls run on the main thread.

**Suggestion:** Document it. Something like: "showOverlay never blocks. From the main thread, create runs after the call returns."

## 11. Fleet sync does not carry plugins or their settings

**Need:** Configure the bar once on the fleet leader and have every display follow.

**Today:** KS's docs/fleet.md answers this. Plugin Manager stays entirely local, and fleet sync copies no installed plugins, plugin settings, per-plugin enabled states or Enable Plugins master switch. Each display has to be installed and configured on its own, which we script through the remote API.

**Suggestion:** Offer an opt-in sync of installed plugins and their settings from the leader, for followers on the same KS version.

## 12. `haNavigate` reports failure after a successful move to another dashboard

**Need:** Use the boolean result to decide whether to fall back to `loadUrl`.

**Today:** Measured on KS 2026.10.21: `haNavigate {path: /bedroom-wall-tablet/toggles}` from `/wall-tablet/0` moved the page within 0.4 s, since `browser.state` fired and `getDashboardState` showed the new path. About 4 s later the command resolved `false`. A plugin that trusts the result reloads a page that is already shown. We now compare the reported page to the target before falling back.

**Suggestion:** Resolve `true` once the WebView reports the new path, including for a move to another dashboard, or document what `false` means.

## 13. Overlays have no show, hide or replace transition

**Need:** Slide the bar into its tab and back, and fade a menu in.

**Today:** `showOverlay` and `hideOverlay` swap views instantly. A plugin can animate inside its own view, but not the overlay's appearance or removal, because KS removes the view as soon as `hideOverlay` is called. Changing an overlay's size means replacing it, which also happens instantly.

**Suggestion:** `OverlaySpec.enter(...)` and `exit(...)` with a few stock transitions, such as fade and slide from the anchor edge. Alternatively, call a `factory.onExit(view, done)` hook before removal.

## 14. `/api/screenshot` does not show native overlays reliably

**Need:** Check a plugin overlay remotely, the way we check the dashboard.

**Today:** On KS 2026.10.21 on a ThinkSmart View, the Remote Admin screenshot showed the first bar correctly. It never showed a menu overlay that was on the panel, as confirmed with `adb exec-out screencap`, and it showed a replaced overlay in its old state. This cost us a debugging detour, because the menu looked broken when it was not.

**Suggestion:** Capture the composited screen, including platform-view textures, or document that native overlays may be missing from the screenshot.

## 15. Creating an overlay is very slow on low-power panels

**Need:** Collapse a bar into a tab, expand it and open menus, each as a quick response to a tap.

**Today:** Measured on a ThinkSmart View (Snapdragon 624, Android 8.1, KS 2026.10.21) with `adb screencap`: a new `showOverlay`, or a replacement at an existing key, rendered nothing for 8 s or more. One later showed as an undrawn grey box before it finished. The same panel reports 95% janky frames from the dashboard alone. Changes inside an existing overlay, such as moving the highlight, were immediate. Each overlay is a new Flutter platform view, and that seems to be the cost.

**Our workaround:** One long-lived overlay. Collapse, expand and menus only change visibility inside it, and the `WRAP` overlay resizes.

**Suggestion:** Document the cost, so plugin authors reuse overlays rather than replace them. Consider reusing the platform view when an overlay is replaced at the same key.

## 16. A resized `WRAP` overlay shows a stale, stretched frame

**Need:** A wrapped overlay that changes size, such as a bar collapsing to a tab or a menu opening, should repaint at its new size.

**Today:** Measured with `adb screencap` on a ThinkSmart View (Android 8.1, KS 2026.10.21). When the plugin view's measured size changes, KS resizes the platform view, but the content is not redrawn into the resized surface, so the previous frame is scaled to the new size. This also explains a new wrapped overlay that first appears as a blank box in its background color: its first frame was drawn at the 1 px placeholder size. The overlay stays like that until something else invalidates the view.

Two more details, both measured with logging and on-device `screencap`. First, the host `OverlayFrame` reaches the new size before the surface behind it does. A redraw at the moment the host's size matches still lands in the old surface and shows stretched, and nothing redraws after that. Second, a change to `View.setAlpha` alone never reached the screen: content hidden with alpha 0 stayed invisible after alpha went back to 1, while ordinary redraws did arrive.

**Our workaround:** Redrawing after the resize (listening to the `OverlayFrame` parent) only shortens the glitch, because the stretched frame is still composited first. We now skip drawing (an empty `dispatchDraw`, not alpha) from the moment the size will change until the host frame has the measured size. We stay hidden another 150 ms so the surface catches up, then draw, and redraw again at 300 ms and 800 ms. A 900 ms timeout reveals the view anyway. We also moved menus into their own fixed-size overlay so the bar never resizes.

**Suggestion:** Don't composite a resized texture until the view has drawn into it at the new size. For example, keep showing the old size until the first frame at the new size arrives, then swap. Every plugin with a wrapped overlay that changes size hits this, so it belongs in KS rather than in each plugin.

## 17. Template tooling on Windows

- `tools/build.py` only finds `build-tools/*/d8`, but Windows ships `d8.bat`. We added the `.bat` glob.
- `tools/test_plugin_assets.py` creates symlinks and file names containing `?` and `#`. Both fail on Windows without elevated privileges. We skip those two tests on Windows.
