// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.Collections;
import java.util.List;
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
 * One overlay holds the bar and its tab, and an open menu gets a second one that KS places over its
 * item. Building a KS overlay is slow on low-power panels, so the bar's is only replaced when the
 * configuration changes, and hiding it behind its tab is a change inside the view. The plugin reserves the
 * bar's depth with setContentInsets, so the dashboard ends above it.
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
    /** Space a menu keeps from the other screen edges, in dp. */
    private static final int MENU_MARGIN = 12;
    private static final int MENU_TRANSITION_MS = 160;
    private static final int BAR_TRANSITION_MS = 180;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile PluginHost host;
    private volatile NavConfig config = NavConfig.parse(Collections.<String, Object>emptyMap());
    private volatile String path;
    /** The open KS camera view, or null. */
    private volatile String camera;
    private volatile boolean screensaver;
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
    /** Whether the shown bar was built to sit over camera views. */
    private boolean builtOver;
    private int openMenu = -1;
    /** A tap that reaches the bar this late, in ms, is logged. */
    private static final long SLOW_TAP_MS = 150;
    /** An open menu closes after this long, so a wall panel is not left with a menu up. */
    private static final long MENU_TIMEOUT_MS = 30_000;
    private final Runnable menuTimeout = () -> {
        try { closeMenu(); } catch (RuntimeException ignored) {}
    };
    private View menuView;
    /** The space currently reserved for the bar, in dp, and on which edge. */
    private int reserved;
    private NavConfig.Edge reservedEdge;
    /** The bar's last reported depth, in dp. */
    private int depth;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        // KS may reuse this object for a new session. Nothing from the old one applies.
        path = null;
        camera = null;
        screensaver = false;
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
        try {
            host.subscribe("camera.view");
            host.subscribe("screensaver.state");
        } catch (RuntimeException unsupported) {
            // The bar then stays under camera views, as an ordinary overlay does.
        }
        readDashboard();
        readCamera();
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        fillIcons(settings);
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

    /**
     * Saves the icon the bar picked for any item left without one, so the settings page shows it.
     * KS does not call configure for the save, and the bar already draws the same picks, so nothing
     * else changes. The next configure finds nothing left to fill.
     */
    private void fillIcons(Map<String, Object> settings) {
        PluginHost current = host;
        if (current == null || settings == null) return;
        List<Map<String, Object>> filled = NavItem.withIcons(settings.get("items"));
        if (filled == null) return;
        Map<String, Object> values = new java.util.LinkedHashMap<>(settings);
        values.put("items", filled);
        try {
            current.saveSettings(values);
        } catch (RuntimeException error) {
            // The bar still draws the picked icons. Only the settings page keeps showing them empty.
            current.log("Could not save the picked icons: " + error.getMessage());
        }
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
        else if ("ks.camera.view".equals(event)) setCamera(payload);
        else if ("ks.screensaver.state".equals(event)) {
            screensaver = payload != null && Boolean.TRUE.equals(payload.get("active"));
            sync();
        }
        // A tap outside the menu closed it. Its overlay is gone; only the bookkeeping is left.
        else if ("overlay.closed".equals(event) && payload != null && MENU.equals(payload.get("key"))) main.post(this::menuClosed);
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

    /** A camera view opened, switched or closed. The camera view's own state reads the same way. */
    private void setCamera(Map<?, ?> state) {
        String next = state != null && Boolean.TRUE.equals(state.get("active")) ? text(state.get("viewId")) : null;
        if (Objects.equals(next, camera)) return;
        camera = next;
        main.post(this::closeMenu);
        sync();
    }

    /** Reads whether a camera view is already open when the plugin starts. */
    private void readCamera() {
        PluginHost current = host;
        if (current == null) return;
        try {
            current.executeCommand("getCameraViewState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                synchronized (NavbarPlugin.this) {
                    if (ok && host == current && data instanceof Map) setCamera((Map<?, ?>) data);
                }
            });
        } catch (RuntimeException unsupported) {
            // Without it the bar learns about a camera view when one next opens.
        }
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
                reserve(0);
                return;
            }
            // Over an open camera view the bar moves to KS's top layer, which needs a new overlay.
            // Not over the screensaver, which also sits below that layer.
            boolean over = cfg.overCameras && camera != null && !screensaver;
            if (!shown || rebuild.getAndSet(false) || over != builtOver) {
                closeMenu();
                String anchor = anchor(cfg);
                builtOver = over;
                // Flush with the edge: the view keeps the bar's distance itself, so the tab can touch it.
                current.showOverlay(BAR, OverlaySpec.at(anchor).closeOnBack(false).onTop(over)
                    .enter(OverlaySpec.FADE).exit(OverlaySpec.FADE).transitionDuration(BAR_TRANSITION_MS), new RootFactory());
                if (depth > 0) reserve(depth);
                shown = true;
                scheduleAutoCollapse();
                return;
            }
            NavViews.Root view = root.get();
            if (view != null) view.setLocation(path, camera);
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
        main.removeCallbacks(menuTimeout);
        shown = false;
        builtOver = false;
        openMenu = -1;
        menuView = null;
        reserved = 0;
        reservedEdge = null;
        markMenu(-1);
    }

    /** Main thread. */
    private void closeMenu() {
        PluginHost current = host;
        boolean open = openMenu >= 0 || menuView != null;
        openMenu = -1;
        markMenu(-1);
        main.removeCallbacks(menuTimeout);
        if (!open || current == null) return;
        try { current.hideOverlay(MENU); } catch (RuntimeException sessionEnded) {}
    }

    /**
     * Main thread. A menu gets its own overlay, so opening it never resizes the bar. KS places it
     * beside the bar over its item, keeps it on screen, and closes it on a tap anywhere else.
     */
    private void openMenu(int index, int offsetDp, int barDepthDp) {
        PluginHost current = host;
        if (current == null) return;
        NavConfig cfg = config;
        int clear = Math.min(4096, barDepthDp + MENU_GAP);
        int left = MENU_MARGIN, top = MENU_MARGIN, right = MENU_MARGIN, bottom = MENU_MARGIN;
        switch (cfg.edge) {
            case TOP: top = clear; break;
            case LEFT: left = clear; break;
            case RIGHT: right = clear; break;
            default: bottom = clear; break;
        }
        OverlaySpec spec = OverlaySpec.at(anchor(cfg))
            .onTop(builtOver)
            .inset(left, top, right, bottom)
            .offset(cfg.vertical ? 0 : offsetDp, cfg.vertical ? offsetDp : 0)
            .closeOnOutsideTouch(true)
            .enter(OverlaySpec.SCALE).exit(OverlaySpec.FADE).transitionDuration(MENU_TRANSITION_MS);
        // Replacing the overlay at the same key swaps one open menu for another.
        current.showOverlay(MENU, spec, new MenuFactory(cfg, index));
        openMenu = index;
        markMenu(index);
        main.removeCallbacks(menuTimeout);
        main.postDelayed(menuTimeout, MENU_TIMEOUT_MS);
    }

    /**
     * Main thread. Keeps the dashboard clear of the bar: Home Assistant pads its views by this much,
     * so the last cards scroll out from under it. Zero gives the space back.
     */
    private void reserve(int depthDp) {
        PluginHost current = host;
        if (current == null) return;
        NavConfig cfg = config;
        int depth = cfg.makeRoom ? Math.max(0, depthDp) : 0;
        if (depth == reserved && cfg.edge == reservedEdge) return;
        int left = 0, top = 0, right = 0, bottom = 0;
        switch (cfg.edge) {
            case TOP: top = depth; break;
            case LEFT: left = depth; break;
            case RIGHT: right = depth; break;
            default: bottom = depth; break;
        }
        try {
            current.setContentInsets(left, top, right, bottom);
            reserved = depth;
            reservedEdge = cfg.edge;
        } catch (UnsupportedOperationException | LinkageError olderHost) {
            // A Kiosk Satellite without content insets: the bar floats over the dashboard.
        } catch (RuntimeException error) {
            log(current, "Could not make room for the bar", error);
        }
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

    /**
     * The status line on the plugin page. It is empty while the bar works as set up, and explains
     * only what needs attention: an item that cannot be used, or why the bar is not showing.
     */
    private void report() {
        PluginHost current = host;
        if (current == null) return;
        NavConfig cfg = config;
        StringBuilder text = new StringBuilder();
        boolean error = !cfg.problems.isEmpty();
        for (String problem : cfg.problems) text.append(problem).append('\n');
        if (cfg.items.isEmpty()) {
            text.append("Add an item to show the bar.");
        } else if (userHidden) {
            text.append("Hidden by the Hide navbar action. Run Show navbar to bring it back.");
        } else if (!cfg.visibleAt(path)) {
            text.append("Hidden on this page, which is listed under Hide the bar on.");
        }
        String message = text.toString().trim();
        if (message.length() > 1000) message = message.substring(0, 1000);
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        current.status(message, error);
    }
    /** Main thread. A failure is only reported if no newer tap or session has happened since. */
    private void run(NavTarget target) {
        PluginHost current = host;
        if (current == null) return;
        int revision = navigation.incrementAndGet();
        try {
            NavTarget.Command command = target.command();
            current.executeCommand(command.name, command.arguments, (ok, data, error) -> {
                if (host != current || navigation.get() != revision) return;
                if (ok && !Boolean.FALSE.equals(data)) return;
                current.status("Could not open " + describe(target) + (error == null ? "" : ": " + error), true);
            });
        } catch (RuntimeException error) {
            status(current, "Could not open " + describe(target) + ": " + error.getMessage());
        }
    }

    /** Main thread. */
    private void menuClosed() {
        main.removeCallbacks(menuTimeout);
        openMenu = -1;
        menuView = null;
        markMenu(-1);
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

        @Override public void onMenu(int index, int offsetDp, int barDepthDp) {
            try {
                if (openMenu == index) closeMenu();
                else openMenu(index, offsetDp, barDepthDp);
            } catch (RuntimeException error) { log(host, "Could not open the menu", error); }
        }

        @Override public void onCloseCamera() {
            PluginHost current = host;
            try {
                closeMenu();
                if (current != null) current.executeCommand("hideCameraView", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                    if (!ok && host == current) current.status("Could not close the camera view: " + error, true);
                });
            } catch (RuntimeException error) { log(current, "Could not close the camera view", error); }
        }

        @Override public void onDepth(int depthDp) {
            try {
                depth = depthDp;
                if (shown) reserve(depthDp);
            } catch (RuntimeException error) { log(host, "Could not make room for the bar", error); }
        }

        @Override public void onInteraction() {
            try { scheduleAutoCollapse(); } catch (RuntimeException ignored) {}
        }

        @Override public void onLatency(long downMs, long upMs) {
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
            NavViews.Root view = new NavViews.Root(context, theme, cfg, path, camera, builtOver, collapsed, new Taps());
            root.set(view);
            // The page may have changed while the view was being built, before the worker could see
            // it. Publishing first and then re-reading means no page update is lost.
            view.setLocation(path, camera);
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

        MenuFactory(NavConfig cfg, int index) {
            this.cfg = cfg;
            this.index = index;
        }

        @Override public View create(Context context, KsTheme theme) {
            NavItem item = cfg.items.get(index);
            View view = new NavViews.MenuPanel(context, theme, cfg, index, path, camera, child -> {
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