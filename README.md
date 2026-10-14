# Navbar for Kiosk Satellite

**A native navigation bar for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) wall panels.** It highlights the page you're on, opens menus and camera grids, and keeps your Home Assistant dashboard clear of it. Kiosk Satellite draws it with its own colors, type and icons, so it looks like part of the kiosk.

<p align="center"><img src="docs/images/bar.png" alt="The navbar along the bottom of a dashboard" width="720"></p>

| Grid menu | List menu | Hidden |
| :---: | :---: | :---: |
| <img src="docs/images/grid.png" alt="A camera grid menu" width="260"> | <img src="docs/images/list.png" alt="A list menu of kiosk actions" width="260"> | <img src="docs/images/tab.png" alt="The bar hidden behind a tab" width="260"> |
| Long menus, such as cameras, show as tiles | Short menus show as a list | Swipe down to hide the bar behind a tab |

## Why use it

- **Knows where you are.** The item for the page on screen lights up, however you got there.
- **Menus and grids.** An item can open a list, or a grid of tiles for things like cameras. Optionally, a tap opens a page and a long press opens the menu.
- **More than pages.** Items can open camera views, Music Assistant, Now Playing, the screensaver, the app launcher or a web address.
- **Keeps the dashboard clear.** The dashboard ends above the bar, so no card hides under it. No card-mod needed.
- **Works over camera views.** The bar stays on screen while a camera view is open, with a **Close** button, so you can switch cameras or leave without reaching for the camera view's own controls.
- **Any edge.** A bar along the bottom or top, or a rail down the left or right side.
- **Out of the way.** Set how translucent it is, and swipe it away behind a small tab.
- **Set up without typing.** Pages, camera views and icons each have a picker.
- **Light.** Native Android views, no WebView and no Home Assistant token.

## Install

Needs Kiosk Satellite **2026.10.23** or newer.

1. In Kiosk Satellite, open **Settings > Plugin Manager > Add plugin**.
2. Paste `https://github.com/dimatx/kiosk-satellite-navbar`, tap **Preview**, then **Trust and install**.
3. Turn on **Navbar**. It starts with a Home item.
4. Open **Navbar** and add your own items under **Items**.

Settings are on the plugin's page, on the kiosk or in Remote Admin, and take effect as you save them.

## Items

Each item has a **Label**, what it **Opens**, and optional icons.

| Opens | You pick | A tap |
| --- | --- | --- |
| Page | A dashboard view | Opens the view. The item lights up while it's shown |
| Camera view | One of the kiosk's camera views | Opens the camera view |
| Menu | Up to 16 entries | Opens the menu over the item. More than 6 entries show as a grid |
| Kiosk action | Music, Now playing, Screensaver, App launcher, Reload or Start page | Runs it |
| Web address | An `https://` address | Loads it in the kiosk |

| Editing a page item | Editing a menu |
| :---: | :---: |
| <img src="docs/images/editor-page.png" alt="Editing a page item" width="380"> | <img src="docs/images/editor-menu.png" alt="Editing a menu item and its entries" width="380"> |

- **Icons.** Leave an icon empty and one is picked from the label, then filled in for you: *Garage* gets a garage, *Driveway* a road, *Living room* a sofa. **Selected icon** is shown while the item's page is open, often the filled version.
- **Tap opens.** On a menu item, pick a page here and a tap opens that page while a long press opens the menu. Leave it empty and a tap opens the menu.
- **Order.** Drag rows by their handle to reorder items or menu entries. A bar holds up to 8 items.
- **Smaller screens.** If the bar does not fit, scroll along it to reach the remaining items. Menu grids use fewer columns when space is limited, and tall menus scroll.

## Settings

<p align="center"><img src="docs/images/settings.png" alt="The Navbar settings page" width="560"></p>

| Setting | What it does |
| --- | --- |
| Position | Bottom, Top, Left or Right. Left and Right make a vertical rail |
| Labels | Always, Active only or Never |
| Size | 80% to 140% |
| Opacity | How much of the dashboard shows through |
| Swipe to hide | Start visible, Start hidden or Off |
| Hide after | Seconds without a tap before the bar hides. 0 keeps it visible |
| Keep the dashboard clear | The dashboard ends above the bar instead of under it. On by default |
| Show over camera views | Keep the bar on screen while a camera view is open, with a Close button. On by default |
| Hide the bar on | Pages where the bar stays hidden, picked from your dashboards |

<details>
<summary><b>Left and right rails</b></summary>

<p align="center"><img src="docs/images/rail.png" alt="The navbar as a rail on the left" width="560"></p>

On the left or right, the bar becomes a rail. Menus open beside it, a sideways swipe hides it behind a tab on the edge, and the dashboard keeps clear of it in the same way.

</details>

<details>
<summary><b>Hiding the bar</b></summary>

Swipe the bar toward its edge to hide it behind a small tab. Swipe the tab back, or tap it, to show the bar again. The tab shows the current page's icon. While the bar is hidden, the dashboard keeps clear of the tab only.

**Hide after** pauses while a menu is open. The menu closes automatically after 30 seconds, even if untouched; closing it starts a fresh auto-hide countdown for the bar. Choosing an entry or dismissing the menu does the same. Changing **Hide after** takes effect immediately.

</details>

<details>
<summary><b>Gestures and the kiosk drawer</b></summary>

**Show navbar**, **Hide navbar** and **Toggle navbar** can be mapped to a gesture or added to the kiosk drawer, under the plugin's **Actions**.

</details>

<details>
<summary><b>Build from source</b></summary>

Needs a JDK, Python 3 and the Android SDK with `ANDROID_HOME` set.

```sh
python3 tools/test.py
python3 tools/build.py
```

The ZIP lands in `dist/`. Install it with **Plugin Manager > Developer Tools > Install from ZIP**. To release, publish a GitHub release tagged `vX.Y.Z`. CI tests, builds and attaches the files Kiosk Satellite verifies. See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.

`tools/test.py` also runs the controller against a virtual clock and deferred host responses. For native Android layout checks, run `python3 tools/test_views.py --serial <adb-device-serial>` on an adb-connected development device. It measures views without opening a window or installing an app, and removes its temporary test bundle afterward. This checks layout and drawable reuse, not KS's compositor latency.

</details>

## Credits

Built for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) by [@jxlarrea](https://github.com/jxlarrea), whose native plugin SDK, overlays, pickers and icons make a bar like this possible. Started from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world).

Loosely inspired by [lovelace-navbar-card](https://github.com/joseluis9595/lovelace-navbar-card) by [@joseluis9595](https://github.com/joseluis9595). Plugin SDK limits found along the way are tracked in [docs/sdk-gaps.md](docs/sdk-gaps.md).

## License

[Apache-2.0](LICENSE). See [NOTICE](NOTICE) for what comes from the Kiosk Satellite plugin template.
