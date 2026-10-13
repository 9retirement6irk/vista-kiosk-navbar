# Navbar for Kiosk Satellite

A native navigation bar for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite). It floats over the dashboard as a KS overlay, styled with KS's own colors, shapes and Rubik typeface, and follows the light and dark theme.

- **Follows the page.** The item for the dashboard view on screen is highlighted. This includes views you reach some other way, such as a link, a gesture or an automation.
- **Native navigation.** Items open dashboard views through KS, and can also run kiosk actions: camera views, Music Assistant, Now Playing, the screensaver and the app launcher.
- **Menus.** An item can open a panel of entries, like the popups in lovelace-navbar-card.
- **Out of the way.** The background can be translucent, and the bar can fold into a small tab at the screen edge. It can start open or collapsed, and can collapse itself again after a while.
- **Self-contained.** It needs no Home Assistant token and makes no network requests. All 7,400+ [Material Design Icons](https://pictogrammers.com/library/mdi/) are bundled, so any `mdi:` name works offline.
- **Light.** It is drawn with Android views, not a WebView, so it adds almost nothing to a low-power tablet.

Loosely inspired by [lovelace-navbar-card](https://github.com/joseluis9595/lovelace-navbar-card). Built from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world).

## Install

Requires a Kiosk Satellite build with native plugin overlays.

1. In Kiosk Satellite, open **Settings > Plugin Manager > Add plugin**.
2. Paste `https://github.com/dimatx/kiosk-satellite-navbar`, tap **Preview**, review it and tap **Trust and install**.
3. Enable **Navbar**, then open its page to configure it.

## Configure

Settings save as you edit them. The bar redraws immediately.

### Bar

| Setting | Meaning |
| --- | --- |
| Position | Bottom or Top |
| Labels | Always, Active only or Never |
| Show on | Comma-separated dashboard paths where the bar appears, such as `/wall-tablet,/bedroom-wall-tablet`. Every view below them is included. Empty shows it on every page |
| Size | Scale of the bar and its menus |
| Edge distance | Distance from the screen edge, in dp |
| Opacity | Background opacity in percent. Lower values let the dashboard show through, while icons and labels stay solid |
| Collapsing | **Start open** (default), **Start collapsed** or **Off**. See below |
| Collapse after | Seconds without a tap before an open bar folds back into its tab. 0 keeps it open |

### Items 1 to 7

| Setting | Meaning |
| --- | --- |
| Type | **Off**, **Page**, **Action** or **Menu** |
| Label | Text under the icon, also read by accessibility services |
| Icon | An MDI name such as `mdi:home-outline`. Add a second one for the selected state: `mdi:home-outline\|mdi:home` |
| Target | See below |

**Page** targets are dashboard paths, such as `/wall-tablet/kitchen`. The item is highlighted while that view is shown. A dashboard's first view matches both `/dash/0` and `/dash`. A path with a query string, such as `/dash/cameras?camera=garage`, still works. It loads as a full URL on the kiosk's current server, and the highlight ignores the query.

**Action** targets:

| Target | Does |
| --- | --- |
| `camera:<view id>` | Opens a KS camera view |
| `music` | Opens Music Assistant |
| `nowplaying` | Shows Now Playing |
| `screensaver` | Starts the screensaver |
| `apps` | Opens the app launcher |
| `reload` | Reloads the dashboard |
| `home` | Loads the kiosk's Start URL |
| `https://…` | Loads a URL in the dashboard |

**Menu** targets list entries as `Label|icon|target`, separated by semicolons or new lines. Each entry's target is a Page or Action target. The icon can be empty. A menu holds up to 12 entries and opens next to its item. The item is highlighted while any of its pages is shown. Tap the item again, or the space beside the menu, to close it.

```text
Weather|mdi:weather-partly-cloudy|/dash/weather; Air|mdi:home-heart|/dash/air; Garage|mdi:garage|camera:garage
```

Problems, such as an unknown target, are listed on the plugin page, and the item is left out until it is fixed.

### Collapsing

By default the bar starts open with a chevron at its end. Tapping the chevron folds the bar into a small tab flush with the screen edge, and it stays open until someone does. The tab shows the current page's icon, and tapping it opens the bar again. **Start collapsed** suits wall panels where the dashboard should have the whole screen until someone reaches for the bar. Pair it with **Collapse after** so the bar tucks itself away again once it has been used.

### Actions

**Show navbar**, **Hide navbar** and **Toggle navbar** can be mapped to a gesture or added to the kiosk drawer. Hide navbar keeps the bar hidden on every page until Show navbar or Toggle navbar runs.

## Leave room for the bar

The bar floats over the dashboard and cannot shrink the page. Give your dashboard some bottom padding so its last cards can scroll clear of the bar. For example, with [card-mod](https://github.com/thomasloven/lovelace-card-mod) in a theme:

```yaml
card-mod-root-yaml: |
  hui-view$: |
    #view { padding-bottom: 96px !important; }
```

## Build

You need a JDK, Python 3 and the Android SDK, with a platform and build tools installed and `ANDROID_HOME` set.

```sh
python3 tools/test.py
python3 tools/build.py
```

The ZIP lands in `dist/`. Install it on a kiosk with **Plugin Manager > Developer Tools > Install from ZIP**.

`tools/gen_mdi.py` packs the vendored font in `third_party/mdi/` into `build/generated/`. Plugin code cannot read the package's `assets/` folder, so the font travels in the DEX and is written to the app's cache directory on first use.

To release, publish a GitHub release with a `vX.Y.Z` tag. The workflow tests, builds and attaches the plugin files that KS verifies.

## SDK gaps

[docs/sdk-gaps.md](docs/sdk-gaps.md) tracks plugin SDK limitations found while building this plugin, with suggested changes for the maintainer.

## License

Apache-2.0. Material Design Icons: see [third_party/mdi/LICENSE](third_party/mdi/LICENSE).
