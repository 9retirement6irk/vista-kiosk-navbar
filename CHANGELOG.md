# Changelog

## 0.10.0

- **Left and right.** Position can be Left or Right for a vertical rail. Menus open beside it, and a sideways swipe tucks it into a tab on the edge.
- **Tidier settings.** Look and Behavior groups, and each item lists Label and Target before its icons. Collapsing is now **Swipe to tuck away**, with Start open, Start tucked away or Off, and **Tuck away after**. Shorter descriptions throughout.
- Haptic feedback on taps, where the device supports it.

**Upgrading:** set Swipe to tuck away again if it was Start collapsed.

## 0.9.1

- Actions with no icon get one that fits: screensaver, apps, now playing, web addresses.
- A new README with screenshots.

## 0.9.0

- **Readable menus.** Menu entries are written `Label = target`, with an optional `= mdi:icon` at the end, instead of `Label|icon|target`.
- **Camera view IDs on their own.** A target that is a 32-character KS camera view ID opens that camera, with no `camera:` prefix. Twelve cameras fit in one menu.
- **Short setting descriptions.** The settings page gives a one-line hint, and the README has the details.
- The older pipe syntax and the `camera:` and `url:` prefixes are gone.

## 0.8.0

- **Simpler settings.** The bar shows on every page. An optional **Hide on** replaces Show on. Items have no **Type**: an item is on when it has a label, and what it does follows its target. **Edge distance** and **Menu layout** are gone. The edge distance is fixed, and menus switch to a grid on their own above 6 entries. 43 settings down to 35.

**Upgrading:** set Hide on if the bar should stay off some pages, and clear the Label of any item that was Off.

## 0.7.1

- Fixed: in 0.7.0 the bar could stay invisible, and expanding could leave a stretched frame on screen. The bar now skips drawing while its size changes instead of using transparency, and reveals itself 150 ms after KS reports the new size.

## 0.7.0

- **No more stretched frames.** The bar, its tab and menus stay hidden while their overlay is being resized, and appear once KS has applied the new size. Before, the previous frame showed stretched to the new size for a moment.
- Menus open in their own overlay, so opening one no longer resizes or redraws the bar. A menu card still sits over its item, and tapping the space beside it closes it.

## 0.6.0

- **Icons from labels.** An empty icon is picked from the label, such as Driveway → road, Garage → garage, Living → sofa, Exterior → house with an arrow out. Camera menus no longer show the same icon on every entry.
- Plainer plugin page. A short description, a status line in plain words, and units on the sliders (dp, %, s).

## 0.5.1

- Menus stay at least 95% opaque so their entries stay readable over a busy dashboard. The bar keeps its own Opacity.
- Every tap logs the command it runs, and logs it if the command fails.

## 0.5.0

- **Grid menus.** **Menu layout** can be Auto, List or Grid. Grid shows tiles with an icon over a label, and Auto uses a grid above 6 entries.
- Menus hold up to 16 entries.
- Camera entries without an icon get a camera icon, which also keeps long camera menus short enough to fit in the setting.

## 0.4.0

- **Swipe to collapse.** Swipe the bar toward its edge to fold it into its tab, and swipe the tab back or tap it to open the bar. The collapse chevron is gone.
- **Selected icon** is its own optional setting. A pipe in Icon still works for existing settings.
- Faster feedback. A press shows at once with no ripple animation, a tapped page is highlighted before Home Assistant confirms it, and moving the highlight no longer re-lays out the bar.
- Each tap logs how late KS delivered the touch and when the bar redrew.

## 0.3.2

- **Collapsing** now defaults to **Start open**. The bar starts expanded with a collapse chevron, and stays open until someone taps it. **Collapse after** still defaults to 0, which never collapses the bar on its own.

## 0.3.1

- The bar repaints when KS resizes its overlay. On a ThinkSmart View it could show as a blank box, or as the tab stretched to the size of the bar.

## 0.3.0

- The bar, its tab and its menus now live in one overlay. Collapsing, expanding and opening a menu are now instant. Before, each change created a new KS overlay, which could take several seconds to appear on a ThinkSmart View.
- Menus open next to their item instead of in the middle of the bar.
- Tapping the space beside an open menu closes it.

## 0.2.0

- **Opacity**: a translucent bar background, with solid icons and labels.
- **Collapsing**: the bar can fold into a tab at its edge, starting open or collapsed. **Collapse after** folds it away again after a set time without taps.
- Tapping a page on another dashboard no longer reloads it. `haNavigate` can report failure after it has already moved the page.
- The bar and menus are now managed on one thread, so a tap and a page change can no longer race.
- Failed taps are written to the KS log.

## 0.1.1

- The active item is highlighted when the bar first appears. A page change that arrived while the bar was being built was lost.

## 0.1.0

- First release: a native bar of up to 7 items that highlights the item for the page on screen.
- Page, Action and Menu items. Actions open camera views, Music Assistant, Now Playing, the screensaver, the app launcher, the Start URL or a URL, or reload the dashboard.
- Bundled Material Design Icons 7.4.47.
- Show, Hide and Toggle navbar actions.
