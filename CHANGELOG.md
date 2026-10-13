# Changelog

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
