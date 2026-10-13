# Plugin SDK gaps

Gaps in the Kiosk Satellite plugin SDK that came up while building this plugin. Each open entry describes what we needed, what we do instead and a suggested change, so it can go to the maintainer as written. Numbers stay fixed, so a closed gap keeps its number.

Status as of **Kiosk Satellite 2026.10.23**, which closed most of the list. Open entries were retested on that version.

## Resolved

| # | Gap | Resolved by | How the navbar uses it |
| --- | --- | --- | --- |
| 1 | Overlays could not reserve space in the dashboard | `setContentInsets` | **Keep the dashboard clear** reserves the bar's depth, or the tab's while the bar is hidden. No card-mod padding |
| 2 | No structured or long settings | `list` settings with nested lists | Items are a list, and a menu's entries a nested list, edited in a form instead of a 512-character string |
| 3 | Camera views could not be listed | `cameraView` picker and `getCameraViews` | Camera items and entries pick a view by name |
| 4 | Overlay positioning was limited to one inset | Per-edge insets and `offset` | A menu opens over its item, clear of the bar, and KS keeps it on screen |
| 5 | A small overlay could not detect a tap outside it | `closeOnOutsideTouch` | A tap anywhere else closes a menu without pressing what is under it |
| 6 | Plugin code could not read bundled assets | `assetPath` and `openAsset` | Not needed any more, see 7 |
| 7 | No Material Design Icons for native views | `KsTheme.icon` and the `icon` picker | Icons come from KS. The 1.3 MB bundled font is gone and the package shrank from 668 KB to 37 KB |
| 8 | `haNavigate` rejected query strings | Query strings and fragments accepted | Pages with a query, such as `cameras/0?camera=garage`, stay in the page instead of reloading it |
| 9 | No list of Home Assistant dashboards and views | `dashboard` picker and `getDashboards` | Page items, **Tap opens** and **Hide the bar on** pick views from a list |
| 10 | Threading contract for `showOverlay` was unspecified | Documented: never blocks, safe from any thread, `create` runs after the call | No change needed |
| 12 | `haNavigate` reported failure after a successful move | Returns true once the page is on the view | The `loadUrl` fallback and its page check are gone |
| 13 | Overlays had no show, hide or replace transition | `enter`, `exit` and `transitionDuration` | The bar fades in and out, and menus scale in and fade out |
| 14 | `/api/screenshot` did not show native overlays reliably | Fixed by 2026.10.23 (retested: an open menu shows in the API screenshot as it does in `adb screencap`) | No more need for adb to check the bar |
| 18 | Settings could not depend on each other | `visibleWhen` | **Hide after** shows only while **Swipe to hide** is on, and each item shows only the fields its **Opens** choice needs |

## Open

## 11. Fleet sync does not carry plugins or their settings

**Need:** Configure the bar once on the fleet leader and have every display follow.

**Today:** KS's docs/fleet.md answers this. Plugin Manager stays entirely local, and fleet sync copies no installed plugins, plugin settings, per-plugin enabled states or Enable Plugins master switch. Each display has to be installed and configured on its own, which we script through the remote API.

**Suggestion:** Offer an opt-in sync of installed plugins and their settings from the leader, for followers on the same KS version.

## 15. Creating an overlay is very slow on low-power panels

**Need:** Collapse a bar into a tab, expand it and open menus, each as a quick response to a tap.

**Today:** Measured on a ThinkSmart View (Snapdragon 624, Android 8.1, KS 2026.10.21) with `adb screencap`: a new `showOverlay`, or a replacement at an existing key, rendered nothing for 8 s or more. One later showed as an undrawn grey box before it finished. The same panel reports 95% janky frames from the dashboard alone. Changes inside an existing overlay, such as moving the highlight, were immediate. Each overlay is a new Flutter platform view, and that seems to be the cost.

**Our workaround:** The bar is one long-lived overlay, and collapsing or expanding only changes what is visible inside it. Menus need their own overlay (see gap 16), but it is small and holds only the menu card, so it appears in about a second on the same panel.

**Suggestion:** Document the cost, so plugin authors reuse overlays rather than replace them. Consider reusing the platform view when an overlay is replaced at the same key.

**Still open in 2026.10.23.**

## 16. A resized `WRAP` overlay shows a stale, stretched frame

**Need:** A wrapped overlay that changes size, such as a bar collapsing to a tab or a menu opening, should repaint at its new size.

**Today:** Measured with `adb screencap` on a ThinkSmart View (Android 8.1, KS 2026.10.21). When the plugin view's measured size changes, KS resizes the platform view, but the content is not redrawn into the resized surface, so the previous frame is scaled to the new size. This also explains a new wrapped overlay that first appears as a blank box in its background color: its first frame was drawn at the 1 px placeholder size. The overlay stays like that until something else invalidates the view.

Two more details, both measured with logging and on-device `screencap`. First, the host `OverlayFrame` reaches the new size before the surface behind it does. A redraw at the moment the host's size matches still lands in the old surface and shows stretched, and nothing redraws after that. Second, a change to `View.setAlpha` alone never reached the screen: content hidden with alpha 0 stayed invisible after alpha went back to 1, while ordinary redraws did arrive.

**Our workaround:** Redrawing after the resize (listening to the `OverlayFrame` parent) only shortens the glitch, because the stretched frame is still composited first. We now skip drawing (an empty `dispatchDraw`, not alpha) from the moment the size will change until the host frame has the measured size. We stay hidden another 150 ms so the surface catches up, then draw, and redraw again at 300 ms and 800 ms. A 900 ms timeout reveals the view anyway. Menus are their own overlay, so opening one never resizes the bar.

**Still open in 2026.10.23:** the release notes do not mention it, and the plugin keeps the workaround. The new `enter` transitions animate a view that may still be drawing at the wrong size, so the workaround matters as much as before.

**Suggestion:** Don't composite a resized texture until the view has drawn into it at the new size. For example, keep showing the old size until the first frame at the new size arrives, then swap. Every plugin with a wrapped overlay that changes size hits this, so it belongs in KS rather than in each plugin.

## 17. Template tooling on Windows

- `tools/build.py` only finds `build-tools/*/d8`, but Windows ships `d8.bat`. We added the `.bat` glob.
- `tools/test_plugin_assets.py` creates symlinks and file names containing `?` and `#`. Both fail on Windows without elevated privileges. We skip those two tests on Windows.

## 19. A local ZIP cannot replace a repository install

**Need:** Test a development build on a kiosk that runs the released version, then go back.

**Today:** **Install from ZIP** (and `installPlugin`) refuses a plugin that was installed from a GitHub repository with *This plugin ID belongs to another repository. Uninstall it before changing sources.* (retested on 2026.10.23). A ZIP can replace another ZIP and keeps its settings, but not a repository install. The plugin has to be removed first, so we save its settings over the remote API and restore them after each swap.

**Suggestion:** Let a trusted local ZIP replace a repository install of the same plugin ID while keeping its settings, perhaps marking it as a development build until the next repository update.

## 20. A rejected manifest does not say which rule failed

**Need:** Find out why Kiosk Satellite rejects a manifest during development.

**Today:** Installing a ZIP whose list `row.subtitle` named five keys failed with only `Invalid list setting`. The validator in `PluginManifest.kt` raises that same message for about ten different rules, such as a subtitle over four keys, a `required` field of the wrong type, a missing `fields` array or a `maxItems` out of range. Finding the cause meant reading the KS source. The template's `tools/plugin_manifest.py` does not check the new setting types at all, so the build passes and the problem shows up only at install time.

The same happens with saved values: `configurePlugin` with a menu entry that carried a field only items have answered `Unknown plugin setting`, without naming the key or the row.

**Suggestion:** Name the setting and the rule in the error, such as `items.row.subtitle: at most 4 keys` or `items[1].entries[0].iconSelected: unknown field`. Teaching the template's manifest checker the same rules would catch them at build time.

## 21. A list row cannot summarize a nested list

**Need:** Show something useful under a menu item's name in the Items list, such as "13 entries".

**Today:** A row's `subtitle` can only name fields that are not lists. A menu item has no page, camera or address, so its row shows the label and icon with no subtitle, and the only way to see what the menu holds is to open it.

**Suggestion:** Let `subtitle` name a nested list and show its row count, such as "13 entries", or a short join of its rows' titles.

## 22. A list row's subtitle shows raw picker values

**Need:** Show what a row opens in words, the way its picker does.

**Today:** The pickers show names: a page reads *Navbar demo / Lights* and a camera view its name. A row's `subtitle` shows the stored value instead, so a page row reads `navbar-demo/lights` and a camera row a 32-character view ID. We left camera views out of the subtitles for that reason, so a camera item's row shows no subtitle. The same applies to a list whose row title is a dashboard field: **Hide the bar on** lists its pages as `navbar-demo/settings` rather than *Navbar demo / Settings*.

**Suggestion:** Render `dashboard` and `cameraView` fields in a row subtitle the way their pickers do, with the dashboard and view titles and the camera view's name.

## 23. An icon field cannot show a fallback

**Need:** Leave an item's icon empty and have the settings page show the icon the bar picks for it from the label.

**Today:** An empty `icon` field reads *Choose an icon*, even though the bar draws an icon picked from the label. Users read that as "no icon". We now save the picked icon back with `saveSettings`, which works but turns a choice the plugin made into one the user appears to have made: renaming *Garage* to *Driveway* later keeps the garage icon.

**Suggestion:** Let a plugin supply a placeholder for an empty `icon` field, such as a `placeholderIcon` the plugin can update at runtime, shown greyed in the picker until the user chooses one.

## 24. A list setting's description shows under the add row

**Need:** Explain a list setting above its rows, the way other settings put their description under the title.

**Today:** A top-level `list` setting in its own group renders its rows first and its description in small text below the **Add** row. With **Hide the bar on** empty, the page reads *Add page* followed by *The bar stays hidden on these pages.*, so the explanation comes after the button it explains.

**Suggestion:** Render a list's title and description above its rows, or let the group carry a description.

## 25. A bar cannot follow a camera view without being rebuilt

**Need:** Keep the bar above a camera view while it is open, and back under KS's own menus and the screensaver once it closes.

**Today:** `onTop` is fixed when the overlay is shown. To move the bar above a camera view, the plugin listens for `camera.view`, then replaces its own overlay with `onTop(true)`, and replaces it again when the view closes. Each replacement is a new overlay, which is slow on a low-power panel (gap 15). An `onTop` overlay also sits over the screensaver, so the plugin has to watch `screensaver.state` and drop back down for it.

**Suggestion:** A layer that sits above camera views but below the screensaver and KS's menu, such as `OverlaySpec.layer(ABOVE_CAMERA)`, or a way to change an existing overlay's layer without replacing it.
