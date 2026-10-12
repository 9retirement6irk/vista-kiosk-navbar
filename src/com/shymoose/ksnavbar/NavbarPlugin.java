// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * A native navigation bar for the kiosk. It follows the page the dashboard shows, highlights the
 * matching item and navigates through KS itself, so it needs no Home Assistant token.
 *
 * Threads: plugin callbacks and command results run on the KS plugin worker, which keeps the
 * configuration and page in volatile fields and takes this object's lock. The main thread owns both
 * overlays: the worker asks it to resync by posting, and taps run there already, so every overlay
 * change happens on one thread in order. Main-thread code never takes the lock, and catches every
 * exception, because one escaping a listener would end the app.
 */
public final class NavbarPlugin implements KioskPlugin {
    static final String BAR = "bar";
    static final String MENU = "menu";
    /** Space between the bar and an open menu, in dp. */
    private static final int MENU_GAP = 8;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile PluginHost host;
    private volatile NavConfig config = NavConfig.parse(Collections.<String, Object>emptyMap());
    private volatile String path;
    private volatile String origin;
    private volatile boolean userHidden;
    private volatile boolean collapsed;
    private final AtomicBoolean rebuild = new AtomicBoolean();
    private final AtomicReference<NavViews.Bar> bar = new AtomicReference<>();
    /** Bumped by every tap that navigates and every session change, so stale results are dropped. */
    private final AtomicInteger navigation = new AtomicInteger();
    private final Runnable autoCollapse = this::autoCollapse;
    // Worker-only state.
    private int readRevision;
    private String lastStatus;
    // Main-thread-only state.
    private boolean barShown;
    private int openMenu = -1;
    private View menuView;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        // KS may reuse this object for a new session. Nothing from the old one applies.
        path = null;
        origin = null;
        userHidden = false;
        lastStatus = null;
        readRevision++;
        navigation.incrementAndGet();
        bar.set(null);
        main.post(this::forgetOverlays);
        config = NavConfig.parse(Collections.<String, Object>emptyMap());
        this.host = host;
        configure(settings);
        try {
            host.subscribe("browser.state");
        } catch (RuntimeException unsupported) {
            host.status("This Kiosk Satellite cannot report page changes, so the active item will not follow navigation.", true);
        }
        readDashboard();
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        NavConfig previous = config;
        NavConfig next = NavConfig.parse(settings);
        config = next;
        // A new collapse mode, including the first configuration, starts in its chosen state.
        if (next.collapse != previous.collapse || previous.items.isEmpty()) {
            collapsed = next.collapse == NavConfig.Collapse.START_COLLAPSED;
        }
        if (host == null) return;
        if (!next.sameLook(previous)) rebuild.set(true);
        sync();
        report();
    }

    @Override
    public synchronized void execute(String command, Map<String, Object> arguments) {
        switch (command) {
            case "show": userHidden = false; break;
            case "hide": userHidden = true; break;
            case "toggle": userHidden = !userHidden; break;
            default: throw new IllegalArgumentException("Unknown command: " + command);
        }
        sync();
        report();
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        // Menu closes, including by back, are tracked through the menu factory's onDestroy.
        if ("ks.browser.state".equals(event)) readDashboard();
    }

    @Override
    public synchronized void stop() {
        // KS revokes the host and removes the overlays before calling stop.
        host = null;
        navigation.incrementAndGet();
        readRevision++;
        bar.set(null);
        main.post(this::forgetOverlays);
    }

    /** Ask KS for the current page. Responses can race, so only the latest request is applied. */
    private void readDashboard() {
        PluginHost current = host;
        if (current == null) return;
        final int revision = ++readRevision;
        current.executeCommand("getDashboardState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (NavbarPlugin.this) {
                if (revision != readRevision || host != current) return;
                if (!ok || !(data instanceof Map)) {
                    current.status("Could not read the dashboard page: " + error, true);
                    return;
                }
                Map<?, ?> state = (Map<?, ?>) data;
                String nextPath = text(state.get("currentPath"));
                String nextOrigin = Paths.origin(text(state.get("currentUrl")));
                origin = nextOrigin != null ? nextOrigin : Paths.origin(text(state.get("homeAssistantUrl")));
                if (!Objects.equals(nextPath, path)) {
                    path = nextPath;
                    main.post(this::closeMenu);
                }
                sync();
                report();
            }
        });
    }

    /** Ask the main thread to bring the overlays in line with the current state. */
    private void sync() {
        main.post(this::syncOverlays);
    }

    /** Main thread. Show, hide, rebuild or refresh the bar. */
    private void syncOverlays() {
        PluginHost current = host;
        if (current == null) return;
        NavConfig cfg = config;
        boolean visible = !userHidden && cfg.visibleAt(path);
        try {
            if (!visible) {
                closeMenu();
                main.removeCallbacks(autoCollapse);
                if (barShown) {
                    current.hideOverlay(BAR);
                    barShown = false;
                }
                return;
            }
            if (!barShown || rebuild.getAndSet(false)) {
                showBar(current, cfg);
                return;
            }
            NavViews.Bar view = bar.get();
            if (view != null) view.setActive(cfg.activeIndex(path));
        } catch (UnsupportedOperationException olderHost) {
            status(current, "Native overlays need Kiosk Satellite 2026.10.13 or newer.");
        } catch (RuntimeException error) {
            log(current, "Could not update the bar", error);
        }
    }

    /** Main thread. The factory reads the collapsed state, so the newest call always wins. */
    private void showBar(PluginHost current, NavConfig cfg) {
        String anchor = cfg.top ? OverlaySpec.TOP : OverlaySpec.BOTTOM;
        OverlaySpec spec = collapsed ? OverlaySpec.at(anchor) : OverlaySpec.at(anchor).inset(cfg.inset);
        current.showOverlay(BAR, spec.closeOnBack(false), new BarFactory());
        barShown = true;
        scheduleAutoCollapse(cfg);
    }

    /** Main thread. */
    private void setCollapsed(boolean value) {
        PluginHost current = host;
        if (current == null) return;
        closeMenu();
        collapsed = value;
        if (barShown) showBar(current, config);
    }

    /** Main thread. Restarts the countdown that folds an open bar back into its tab. */
    private void scheduleAutoCollapse(NavConfig cfg) {
        main.removeCallbacks(autoCollapse);
        if (!collapsed && cfg.collapse != NavConfig.Collapse.OFF && cfg.autoCollapse > 0) {
            main.postDelayed(autoCollapse, cfg.autoCollapse * 1000L);
        }
    }

    private void autoCollapse() {
        try {
            if (!collapsed && host != null && config.collapse != NavConfig.Collapse.OFF) setCollapsed(true);
        } catch (RuntimeException error) {
            log(host, "Could not collapse the bar", error);
        }
    }

    private void report() {
        PluginHost current = host;
        if (current == null) return;
        NavConfig cfg = config;
        StringBuilder text = new StringBuilder();
        boolean error = !cfg.problems.isEmpty();
        for (String problem : cfg.problems) text.append(problem).append('\n');
        if (cfg.items.isEmpty()) text.append("No items are configured. Set an item's Type to Page, Action or Menu.");
        else {
            int active = cfg.activeIndex(path);
            text.append(cfg.items.size()).append(cfg.items.size() == 1 ? " item. " : " items. ")
                .append("Page: ").append(path == null ? "unknown" : path)
                .append(active >= 0 ? " (" + cfg.items.get(active).label + ")" : "");
            if (userHidden) text.append(". Hidden by the Hide navbar action.");
            else if (!cfg.visibleAt(path)) text.append(". Hidden here by Show on.");
        }
        String message = text.length() > 1000 ? text.substring(0, 1000) : text.toString();
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        current.status(message, error);
    }

    /** Main thread. */
    private void closeMenu() {
        PluginHost current = host;
        boolean open = openMenu >= 0 || menuView != null;
        openMenu = -1;
        if (!open || current == null) return;
        try { current.hideOverlay(MENU); } catch (RuntimeException sessionEnded) {}
    }

    /** Main thread. The overlays went with the old session, so only the bookkeeping is reset. */
    private void forgetOverlays() {
        main.removeCallbacks(autoCollapse);
        barShown = false;
        openMenu = -1;
        menuView = null;
    }

    /** Main thread. Navigation results only act if no newer tap or session has happened since. */
    private void run(NavTarget target) {
        PluginHost current = host;
        if (current == null) return;
        int revision = navigation.incrementAndGet();
        String base = origin;
        try {
            NavTarget.Command command = target.command(base);
            current.executeCommand(command.name, command.arguments, (ok, data, error) -> {
                if (host != current || navigation.get() != revision) return;
                if (ok && !Boolean.FALSE.equals(data)) return;
                // haNavigate can report false after it has already moved the page, for example to
                // another dashboard. Reloading then would only flash the page, so check first.
                String targetPath = target.matchPath();
                if (targetPath != null && targetPath.equals(Paths.normalize(path))) return;
                NavTarget.Command fallback = "haNavigate".equals(command.name) ? target.fallback(base) : null;
                if (fallback == null) {
                    current.status("Could not open " + describe(target) + (error == null ? "" : ": " + error), true);
                    return;
                }
                current.executeCommand(fallback.name, fallback.arguments, (ok2, data2, error2) -> {
                    if (!ok2 && host == current && navigation.get() == revision) {
                        current.status("Could not open " + describe(target) + ": " + error2, true);
                    }
                });
            });
        } catch (RuntimeException error) {
            status(current, "Could not open " + describe(target) + ": " + error.getMessage());
        }
    }

    private static String describe(NavTarget target) {
        return target.value.isEmpty() ? target.kind.name().toLowerCase(java.util.Locale.ROOT) : target.value;
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static void status(PluginHost current, String message) {
        try { if (current != null) current.status(message, true); } catch (RuntimeException ignored) {}
    }

    private static void log(PluginHost current, String what, RuntimeException error) {
        try { if (current != null) current.log(what + ": " + error); } catch (RuntimeException ignored) {}
    }

    /** Taps on one bar instance. Holds that bar's configuration so indexes always line up. */
    private final class Clicks implements NavViews.Listener {
        private final NavConfig cfg;

        Clicks(NavConfig cfg) { this.cfg = cfg; }

        @Override public void onItem(int index, View view) {
            try {
                scheduleAutoCollapse(cfg);
                NavItem item = cfg.items.get(index);
                if (item.type == NavItem.Type.MENU) {
                    if (openMenu == index) closeMenu();
                    else openMenu(index, view);
                    return;
                }
                closeMenu();
                run(item.target);
            } catch (RuntimeException error) {
                log(host, "Tap on item " + (index + 1) + " failed", error);
            }
        }

        @Override public void onChild(int itemIndex, int childIndex) {
            try {
                scheduleAutoCollapse(cfg);
                NavItem child = cfg.items.get(itemIndex).children.get(childIndex);
                closeMenu();
                run(child.target);
            } catch (RuntimeException error) {
                log(host, "Tap on a menu entry failed", error);
            }
        }

        @Override public void onCollapse() {
            try { setCollapsed(true); } catch (RuntimeException error) { log(host, "Could not collapse the bar", error); }
        }

        @Override public void onExpand() {
            try { setCollapsed(false); } catch (RuntimeException error) { log(host, "Could not open the bar", error); }
        }

        private void openMenu(int index, View anchor) {
            PluginHost current = host;
            if (current == null) return;
            View barView = (View) anchor.getParent();
            float density = anchor.getResources().getDisplayMetrics().density;
            int barHeight = Math.round(barView.getHeight() / density);
            int inset = Math.min(200, cfg.inset + barHeight + MENU_GAP);
            // Replacing the overlay at the same key swaps one open menu for another.
            current.showOverlay(MENU, OverlaySpec.at(cfg.top ? OverlaySpec.TOP : OverlaySpec.BOTTOM).inset(inset), new MenuFactory(cfg, index));
            openMenu = index;
            current.log("Opened menu " + (index + 1) + " at " + inset + " dp");
        }
    }

    /** Builds the open bar or, while collapsed, its tab. */
    private final class BarFactory implements OverlayFactory {
        @Override public View create(Context context, KsTheme theme) {
            NavConfig cfg = config;
            if (collapsed && cfg.collapse != NavConfig.Collapse.OFF) {
                bar.set(null);
                return new NavViews.Tab(context, theme, cfg, cfg.activeIndex(path), new Clicks(cfg));
            }
            NavViews.Bar view = new NavViews.Bar(context, theme, cfg, cfg.activeIndex(path), new Clicks(cfg));
            bar.set(view);
            // The page may have changed while the view was being built, before the worker could see
            // it. Publishing first and then re-reading means no page update is lost.
            view.setActive(cfg.activeIndex(path));
            return view;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            if (view instanceof NavViews.Bar) ((NavViews.Bar) view).restyle(theme);
            else if (view instanceof NavViews.Tab) ((NavViews.Tab) view).restyle(theme);
        }

        @Override public void onDestroy(View view) {
            if (view instanceof NavViews.Bar) bar.compareAndSet((NavViews.Bar) view, null);
        }
    }

    /** Built and destroyed on the main thread, which also owns the menu state. */
    private final class MenuFactory implements OverlayFactory {
        private final NavConfig cfg;
        private final int index;

        MenuFactory(NavConfig cfg, int index) {
            this.cfg = cfg;
            this.index = index;
        }

        @Override public View create(Context context, KsTheme theme) {
            View view = new NavViews.Menu(context, theme, cfg, index, path, new Clicks(cfg));
            menuView = view;
            // Also set here, in case KS destroys the previous menu after showOverlay returned.
            openMenu = index;
            return view;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            ((NavViews.Menu) view).restyle(theme);
        }

        @Override public void onDestroy(View view) {
            // Back, a replacement or hideOverlay removed this menu. A newer menu keeps its state.
            if (menuView == view) {
                menuView = null;
                openMenu = -1;
            }
        }
    }
}
