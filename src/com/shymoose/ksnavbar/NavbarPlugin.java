// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
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
 * Threads: plugin callbacks and command results run on the KS plugin worker, which owns the bar
 * and takes this object's lock. Taps run on the main thread, which owns the menu and never takes the
 * lock. The worker asks the main thread to close the menu by posting, so every menu change happens
 * on one thread in order. Listeners catch every exception, because one escaping a listener would end
 * the app.
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
    private final AtomicReference<NavViews.Bar> bar = new AtomicReference<>();
    /** Bumped by every tap that navigates and every session change, so stale results are dropped. */
    private final AtomicInteger navigation = new AtomicInteger();
    // Worker-only state.
    private boolean barShown;
    private int readRevision;
    private String lastStatus;
    // Main-thread-only state.
    private int openMenu = -1;
    private View menuView;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        // KS may reuse this object for a new session. Nothing from the old one applies.
        path = null;
        origin = null;
        userHidden = false;
        barShown = false;
        lastStatus = null;
        readRevision++;
        navigation.incrementAndGet();
        bar.set(null);
        main.post(this::forgetMenu);
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
        NavConfig next = NavConfig.parse(settings);
        boolean rebuild = !next.sameLook(config);
        config = next;
        if (host == null) return;
        if (rebuild) requestCloseMenu();
        apply(rebuild);
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
        apply(false);
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
        barShown = false;
        main.post(this::forgetMenu);
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
                    requestCloseMenu();
                }
                apply(false);
                report();
            }
        });
    }

    /** Show, hide or refresh the bar for the current page. Runs on the worker. */
    private void apply(boolean rebuild) {
        PluginHost current = host;
        if (current == null) return;
        NavConfig cfg = config;
        boolean visible = !userHidden && cfg.visibleAt(path);
        try {
            if (!visible) {
                requestCloseMenu();
                if (barShown) {
                    current.hideOverlay(BAR);
                    barShown = false;
                }
                return;
            }
            if (!barShown || rebuild) {
                current.showOverlay(BAR, OverlaySpec.at(cfg.top ? OverlaySpec.TOP : OverlaySpec.BOTTOM).inset(cfg.inset).closeOnBack(false), new BarFactory());
                barShown = true;
                return;
            }
        } catch (UnsupportedOperationException olderHost) {
            current.status("Native overlays need Kiosk Satellite 2026.10.13 or newer.", true);
            return;
        }
        NavViews.Bar view = bar.get();
        if (view != null) {
            int active = cfg.activeIndex(path);
            view.post(() -> view.setActive(active));
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

    /** From the worker: close the menu on the main thread, after any tap already queued there. */
    private void requestCloseMenu() {
        main.post(this::closeMenu);
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
    private void forgetMenu() {
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
            try { current.status("Could not open " + describe(target) + ": " + error.getMessage(), true); } catch (RuntimeException ignored) {}
        }
    }

    private static String describe(NavTarget target) {
        return target.value.isEmpty() ? target.kind.name().toLowerCase(java.util.Locale.ROOT) : target.value;
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /** Taps on one bar instance. Holds that bar's configuration so indexes always line up. */
    private final class Clicks implements NavViews.Listener {
        private final NavConfig cfg;

        Clicks(NavConfig cfg) { this.cfg = cfg; }

        @Override public void onItem(int index, View view) {
            try {
                NavItem item = cfg.items.get(index);
                if (item.type == NavItem.Type.MENU) {
                    if (openMenu == index) closeMenu();
                    else openMenu(index, view);
                    return;
                }
                closeMenu();
                run(item.target);
            } catch (RuntimeException ignored) {
                // The session can end between the tap and the host call.
            }
        }

        @Override public void onChild(int itemIndex, int childIndex) {
            try {
                NavItem child = cfg.items.get(itemIndex).children.get(childIndex);
                closeMenu();
                run(child.target);
            } catch (RuntimeException ignored) {}
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
        }
    }

    private final class BarFactory implements OverlayFactory {
        @Override public View create(Context context, KsTheme theme) {
            NavConfig cfg = config;
            NavViews.Bar view = new NavViews.Bar(context, theme, cfg, cfg.activeIndex(path), new Clicks(cfg));
            bar.set(view);
            // The page may have changed while the view was being built, before the worker could see
            // it. Publishing first and then re-reading means no page update is lost.
            view.setActive(cfg.activeIndex(path));
            return view;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            ((NavViews.Bar) view).restyle(theme);
        }

        @Override public void onDestroy(View view) {
            bar.compareAndSet((NavViews.Bar) view, null);
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
