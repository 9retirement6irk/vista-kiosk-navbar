# Navbar for Kiosk Satellite

A native navigation bar for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) wall panels. KS draws it itself, in its own colors and type, so it feels like part of the kiosk rather than part of the dashboard.

<p align="center"><img src="docs/images/bar.png" alt="The navbar over a dashboard" width="720"></p>

- **Knows where you are.** The current page's item lights up, however you got there.
- **Menus and grids.** An item can open a list, or a grid of tiles for things like cameras.
- **Kiosk actions.** Open camera views, Music Assistant, Now Playing, the screensaver or the app launcher.
- **Any edge.** A bar along the bottom or top, or a rail down the left or right side.
- **Gets out of the way.** Translucent, and a swipe tucks it into a small tab.
- **Light and offline.** Native Android views, no WebView, no Home Assistant token. All 7,400+ [Material Design Icons](https://pictogrammers.com/library/mdi/) are built in.

| Grid menu | List menu | Tucked away |
| :---: | :---: | :---: |
| <img src="docs/images/grid.png" alt="A camera grid menu" width="280"> | <img src="docs/images/list.png" alt="A list menu of kiosk actions" width="280"> | <img src="docs/images/tab.png" alt="The bar tucked into a tab" width="280"> |
| Long menus, such as cameras, show as tiles | Short menus show as a list | Swipe down to tuck the bar into a tab |

## Install

1. In Kiosk Satellite, open **Settings > Plugin Manager > Add plugin**.
2. Paste `https://github.com/dimatx/kiosk-satellite-navbar` and tap **Preview**, then **Trust and install**.
3. Enable **Navbar** and give a few items a **Label** and a **Target**.

## Items

Each of the 7 items has a **Label** (empty turns the item off), a **Target**, and an optional **Icon** and **Selected icon**:

| Target | Example | Does |
| --- | --- | --- |
| A page | `/lovelace/kitchen` | Opens the dashboard view, and lights up while it's shown |
| A camera view ID | `a1b2c3d4e5f60718293a4b5c6d7e8f90` | Opens a KS camera view. Copy the ID from KS camera settings |
| An action | `music` | `music`, `nowplaying`, `screensaver`, `apps`, `reload` or `home` |
| A web address | `https://example.com` | Loads it in the kiosk |
| A menu | `Weather = /lovelace/weather; Air = /lovelace/air` | Opens a menu of pages and actions |

Menu entries are `Label = target`, separated by semicolons. Add `= mdi:icon` to pick an entry's icon. Up to 16 entries fit, and menus over 6 entries show as a grid.

```text
Front door = a1b2c3d4e5f60718293a4b5c6d7e8f90; Garage = fedcba9876543210fedcba9876543210 = mdi:garage
```

> [!TIP]
> Leave icons empty and the navbar picks one from the label: *Garage* gets a garage, *Driveway* a road, *Living room* a sofa.

<details>
<summary><b>Look and behavior settings</b></summary>

| Setting | Options |
| --- | --- |
| Position | Bottom, Top, Left or Right |
| Labels | Always, Active only or Never |
| Size | 80% to 140% |
| Opacity | How much of the dashboard shows through |
| Swipe to tuck away | Start open, Start tucked away or Off |
| Tuck away after | Seconds without a tap before the bar tucks away. 0 keeps it open |
| Hide on | Pages without the bar, such as `/profile`. Includes the pages below them |

</details>

<details>
<summary><b>Tucking the bar away</b></summary>

Swipe the bar toward its edge to tuck it into a small tab. Swipe the tab back, or tap it, to bring the bar back. The tab shows the current page's icon.

</details>

<details>
<summary><b>Gestures and the kiosk drawer</b></summary>

**Show navbar**, **Hide navbar** and **Toggle navbar** can be mapped to a gesture or added to the kiosk drawer.

</details>

<details>
<summary><b>Leave room for the bar</b></summary>

The bar floats over the dashboard. Add bottom padding so the last cards can scroll clear of it, for example with [card-mod](https://github.com/thomasloven/lovelace-card-mod) in a theme:

```yaml
card-mod-root-yaml: |
  hui-view$: |
    #view { padding-bottom: 96px !important; }
```

</details>

<details>
<summary><b>Build from source</b></summary>

Needs a JDK, Python 3 and the Android SDK with `ANDROID_HOME` set.

```sh
python3 tools/test.py
python3 tools/build.py
```

The ZIP lands in `dist/`. Install it with **Plugin Manager > Developer Tools > Install from ZIP**. To release, publish a GitHub release tagged `vX.Y.Z`. CI tests, builds and attaches the files KS verifies.

</details>

## Credits

Loosely inspired by [lovelace-navbar-card](https://github.com/joseluis9595/lovelace-navbar-card) and built from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world). Plugin SDK limits found along the way are tracked in [docs/sdk-gaps.md](docs/sdk-gaps.md).

Apache-2.0. Material Design Icons: see [third_party/mdi/LICENSE](third_party/mdi/LICENSE).