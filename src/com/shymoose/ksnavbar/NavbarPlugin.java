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
 * One overlay holds the bar, its tab and its menus. Building a KS overlay is slow on low-power
 * panels, so it is only replaced when the configuration changes. Everything else is a change inside
 * the view.
 *
 * Threads: plugin callbacks and command results run on the KS plugin worker, which keeps the
 * configuration and page in volatile fields and takes this object's lock. The main thread owns the
 * overlay: the worker asks it to resync by posting, and taps run there already, so every overlay
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
    /** Kept outside the view so a rebuilt overlay comes back in the same state. */
    private volatile boolean collapsed;
    private final AtomicBoolean rebuild = new AtomicBoolean();
    private final AtomicReference<NavViews.Root> root = new AtomicReference<>();
    /** Bumped by every tap that navigates and every session change, so stale results are dropped. */
    private final AtomicInteger navigation = new AtomicInteger();
    private final Runnable autoCollapse = this::autoCollapse;
    // Worker-only state.
    private int readRevision;
    private String lastStatus;
    // Main-thread-only state.
    private boolean shown;
    private int openMenu = -1;
    /** A tap that reaches the bar this late, in ms, is logged. */
    private static final long SLOW_TAP_MS = 150;
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
        root.set(null);
        main.post(this::forgetOverlay);
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
        if ("ks.browser.state".equals(event)) readDashboard();
    }

    @Override
    public synchronized void stop() {
        // KS revokes the host and removes the overlay before calling stop.
        host = null;
        navigation.incrementAndGet();
        readRevision++;
        root.set(null);
        main.post(this::forgetOverlay);
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
                boolean moved = !Objects.equals(nextPath, path);
                path = nextPath;
                if (moved) {
                    main.post(this::closeMenu);
                    sync();
                    report();
                }
            }
        });
    }

    /** Ask the main thread to bring the overlay in line with the current state. */
    private void sync() {
        main.post(this::syncOverlay);
    }

    /** Main thread. Show, hide, rebuild or update the overlay. */
    private void syncOverlay() {
        PluginHost current = host;
        if (current == null) return;
        NavConfig cfg = config;
        boolean visible = !userHidden && cfg.visibleAt(path);
        try {
            if (!visible) {
                closeMenu();
                main.removeCallbacks(autoCollapse);
                if (shown) {
                    current.hideOverlay(BAR);
                    shown = false;
                }
                return;
            }
            if (!shown || rebuild.getAndSet(false)) {
                closeMenu();
                String anchor = anchor(cfg);
                // Flush with the edge: the view keeps the bar's distance itself, so the tab can touch it.
                current.showOverlay(BAR, OverlaySpec.at(anchor).closeOnBack(false), new RootFactory());
                shown = true;
                scheduleAutoCollapse();
                return;
            }
            NavViews.Root view = root.get();
            if (view != null) view.setPath(path);
        } catch (UnsupportedOperationException olderHost) {
            status(current, "Native overlays need Kiosk Satellite 2026.10.13 or newer.");
        } catch (RuntimeException error) {
            log(current, "Could not update the bar", error);
        }
    }

    /** Main thread. Restarts the countdown that folds an open bar back into its tab. */
    private void scheduleAutoCollapse() {
        main.removeCallbacks(autoCollapse);
        NavConfig cfg = config;
        if (!collapsed && cfg.collapse != NavConfig.Collapse.OFF && cfg.autoCollapse > 0) {
            main.postDelayed(autoCollapse, cfg.autoCollapse * 1000L);
        }
    }

    /** Main thread. */
    private void setCollapsed(boolean value) {
        closeMenu();
        collapsed = value && config.collapse != NavConfig.Collapse.OFF;
        NavViews.Root view = root.get();
        if (view != null) view.setCollapsed(collapsed);
        scheduleAutoCollapse();
    }

    private void autoCollapse() {
        try {
            if (!collapsed && host != null) setCollapsed(true);
        } catch (RuntimeException error) {
            log(host, "Could not collapse the bar", error);
        }
    }

    /** Main thread. The overlay went with the old session, so only the bookkeeping is reset. */
    private void forgetOverlay() {
        main.removeCallbacks(autoCollapse);
        shown = false;
        openMenu = -1;
        menuView = null;
        markMenu(-1);
    }

    /** Main thread. */
    private void closeMenu() {
        PluginHost current = host;
        boolean open = openMenu >= 0 || menuView != null;
        openMenu = -1;
        markMenu(-1);
        if (!open || current == null) return;
        try { current.hideOverlay(MENU); } catch (RuntimeException sessionEnded) {}
    }

    /**
     * Main thread. A menu gets its own overlay so opening it never resizes the bar. It fills the
     * kiosk's width beside the bar, and the menu card inside it sits over its item.
     */
    private void openMenu(int index, float offsetPx, int barDepthDp) {
        PluginHost current = host;
        if (current == null) return;
        NavConfig cfg = config;
        int inset = Math.min(200, barDepthDp + MENU_GAP);
        OverlaySpec spec = OverlaySpec.at(anchor(cfg)).inset(inset);
        spec = cfg.vertical ? spec.size(OverlaySpec.WRAP, OverlaySpec.FILL) : spec.size(OverlaySpec.FILL, OverlaySpec.WRAP);
        // Replacing the overlay at the same key swaps one open menu for another.
        current.showOverlay(MENU, spec, new MenuFactory(cfg, index, offsetPx));
        openMenu = index;
        markMenu(index);
    }

    /** Main thread. Shows which item a menu belongs to while it is open. */
    private void markMenu(int index) {
        NavViews.Root view = root.get();
        if (view != null) view.setOpenMenu(index);
    }

    /** The overlay anchor for the bar's edge. */
    private static String anchor(NavConfig cfg) {
        switch (cfg.edge) {
            case TOP: return OverlaySpec.TOP;
            case LEFT: return OverlaySpec.LEFT;
            case RIGHT: return OverlaySpec.RIGHT;
            default: return OverlaySpec.BOTTOM;
        }
    }

    private void report() {
        PluginHost current = host;
        if (current == null) return;
        NavConfig cfg = config;
        StringBuilder text = new StringBuilder();
        boolean error = !cfg.problems.isEmpty();
        for (String problem : cfg.problems) text.append(problem).append('\n');
        if (cfg.items.isEmpty()) {
            text.append("Add an item to show the bar: give it a Label and a Target.");
        } else if (userHidden) {
            text.append("Hidden by the Hide navbar action. Run Show navbar to bring it back.");
        } else if (!cfg.visibleAt(path)) {
            text.append("Hidden on this page (").append(path).append(") by Hide on.");
        } else {
            int count = cfg.items.size();
            text.append("Showing ").append(count).append(count == 1 ? " item. " : " items. ");
            int active = cfg.activeIndex(path);
            if (path == null) text.append("Waiting for the dashboard to load.");
            else if (active >= 0) text.append(cfg.items.get(active).label).append(" is selected.");
            else text.append("No item matches this page (").append(path).append(").");
        }
        String message = text.length() > 1000 ? text.substring(0, 1000) : text.toString();
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        current.status(message, error);
    }

    /** Main thread. Navigation results only act if no newer tap or session has happened since. */
    private void run(NavTarget target) {
        PluginHost current = host;
        if (current == null) return;
        int revision = navigation.incrementAndGet();
        String base = origin;
        try {
            NavTarget.Command command = target.command(base);
            current.log("Tap: " + command.name + " " + describe(target));
            current.executeCommand(command.name, command.arguments, (ok, data, error) -> {
                if (host != current || navigation.get() != revision) return;
                if (!ok) current.log(command.name + " failed: " + error);
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

    /** Taps from the overlay, on the main thread. */
    private final class Taps implements NavViews.Listener {
        @Override public void onTarget(NavTarget target) {
            try { closeMenu(); run(target); } catch (RuntimeException error) { log(host, "Tap failed", error); }
        }

        @Override public void onCollapse() {
            try { setCollapsed(true); } catch (RuntimeException error) { log(host, "Could not collapse the bar", error); }
        }

        @Override public void onExpand() {
            try { setCollapsed(false); } catch (RuntimeException error) { log(host, "Could not open the bar", error); }
        }

        @Override public void onMenu(int index, float offsetPx, int barHeightDp) {
            try {
                if (openMenu == index) closeMenu();
                else openMenu(index, offsetPx, barHeightDp);
            } catch (RuntimeException error) { log(host, "Could not open the menu", error); }
        }

        @Override public void onInteraction() {
            try { scheduleAutoCollapse(); } catch (RuntimeException ignored) {}
        }

        @Override public void onLatency(long downMs, long upMs, long drawMs) {
            // Only slow deliveries are worth a log line; every tap would flood the KS log.
            long late = Math.max(downMs, upMs);
            if (late < SLOW_TAP_MS) return;
            PluginHost current = host;
            try {
                if (current != null) current.log("Slow tap: Kiosk Satellite delivered it " + late + " ms late");
            } catch (RuntimeException ignored) {}
        }
    }

    private final class RootFactory implements OverlayFactory {
        @Override public View create(Context context, KsTheme theme) {
            NavConfig cfg = config;
            NavViews.Root view = new NavViews.Root(context, theme, cfg, path, collapsed, new Taps());
            root.set(view);
            // The page may have changed while the view was being built, before the worker could see
            // it. Publishing first and then re-reading means no page update is lost.
            view.setPath(path);
            return view;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            ((NavViews.Root) view).restyle(theme);
        }

        @Override public void onDestroy(View view) {
            root.compareAndSet((NavViews.Root) view, null);
        }
    }

    /** Built and destroyed on the main thread, which also owns the menu state. */
    private final class MenuFactory implements OverlayFactory {
        private final NavConfig cfg;
        private final int index;
        private final float offset;

        MenuFactory(NavConfig cfg, int index, float offset) {
            this.cfg = cfg;
            this.index = index;
            this.offset = offset;
        }

        @Override public View create(Context context, KsTheme theme) {
            NavItem item = cfg.items.get(index);
            View view = new NavViews.MenuPanel(context, theme, cfg, index, path, offset, child -> {
                try {
                    scheduleAutoCollapse();
                    closeMenu();
                    NavItem entry = item.children.get(child);
                    NavViews.Root bar = root.get();
                    if (entry.type == NavItem.Type.PAGE && bar != null) bar.preview(index);
                    run(entry.target);
                } catch (RuntimeException error) {
                    log(host, "Tap on a menu entry failed", error);
                }
            }, () -> {
                try { closeMenu(); } catch (RuntimeException ignored) {}
            });
            menuView = view;
            // Also set here, in case KS destroys the previous menu after showOverlay returned.
            openMenu = index;
            return view;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            ((NavViews.MenuPanel) view).restyle(theme);
        }

        @Override public void onDestroy(View view) {
            // Back, a replacement or hideOverlay removed this menu. A newer menu keeps its state.
            if (menuView == view) {
                menuView = null;
                openMenu = -1;
                markMenu(-1);
            }
        }
    }
}