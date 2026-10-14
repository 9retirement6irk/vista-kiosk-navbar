// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import android.content.Context;
import android.graphics.Path;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/** Headless Android layout tests; run with tools/test_views.py on a connected development device. */
public final class NativeViewTest {
    private static int checks;
    private static int iconRequests;

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace(System.err);
            System.exit(1);
        });
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        Context context = new ContextThemeWrapper(system, android.R.style.Theme_Material_Light);
        float density = context.getResources().getDisplayMetrics().density;
        Map<String, Integer> colors = new HashMap<>();
        for (String role : new String[] {"surface", "surfaceContainer", "surfaceContainerHigh", "onSurface",
                "onSurfaceVariant", "primary", "primaryContainer", "secondaryContainer", "onSecondaryContainer",
                "outline", "outlineVariant"}) colors.put(role, 0xff888888);
        KsTheme theme = new KsTheme(false, density, colors, null, name -> {
            iconRequests++;
            Path path = new Path();
            path.addRect(0, 0, 24, 24, Path.Direction.CW);
            return path;
        });
        for (String edge : new String[] {"Bottom", "Top", "Left", "Right"}) {
            for (String scale : new String[] {"80%", "100%", "140%"}) {
                for (String labels : new String[] {"Always", "Active only", "Never"}) {
                    NavConfig config = NavConfig.parse(settings(edge, scale, labels));
                    NavViews.Root root = new NavViews.Root(context, theme, config, "/demo/7", null, true, false, new Listener());
                    measure(root, theme.px(320), theme.px(480));
                    FrameLayout viewport = (FrameLayout) root.getChildAt(0);
                    NavViews.Bar bar = (NavViews.Bar) viewport.getChildAt(0);
                    check(bar.getChildCount() == 9, "eight items plus Close");
                    for (int i = 0; i < bar.getChildCount(); i++) {
                        View cell = bar.getChildAt(i);
                        check(cell.getHeight() >= theme.px(48) && cell.getWidth() >= theme.px(48), "48 dp bar target");
                    }
                    View selected = bar.getChildAt(7);
                    check(config.vertical
                        ? selected.getBottom() - viewport.getScrollY() <= viewport.getHeight()
                        : selected.getRight() - viewport.getScrollX() <= viewport.getWidth(),
                        "selected last item scrolled into view: " + edge + " " + scale + " " + labels);
                    int before = iconRequests;
                    bar.setActive(0);
                    check(iconRequests - before <= 2, "selection touches at most two icons");
                    before = iconRequests;
                    bar.setOpen(1);
                    check(iconRequests == before, "opening menu only retints existing icon");
                    root.setCollapsed(true);
                    measure(root, theme.px(320), theme.px(480));
                    View tab = root.getChildAt(1);
                    int width = tab.getMeasuredWidth(), height = tab.getMeasuredHeight();
                    check(width >= theme.px(48) && height >= theme.px(48), "48 dp tab target");
                    root.setLocation("/unmatched", null);
                    measure(root, theme.px(320), theme.px(480));
                    check(tab.getMeasuredWidth() == width && tab.getMeasuredHeight() == height, "unmatched tab does not resize");
                }
            }
        }
        for (int width : new int[] {240, 320, 480, 800}) {
            for (String scale : new String[] {"80%", "100%", "140%"}) {
                for (int count : new int[] {6, 16}) {
                    Map<String, Object> settings = settings("Bottom", scale, "Always");
                    Map<String, Object> menu = new HashMap<>();
                    menu.put("label", "Menu");
                    menu.put("opens", "Menu");
                    menu.put("entries", pages(count));
                    settings.put("items", java.util.Collections.singletonList(menu));
                    NavConfig cfg = NavConfig.parse(settings);
                    NavViews.MenuPanel panel = new NavViews.MenuPanel(context, theme, cfg, 0, "/demo/0", null, child -> {});
                    measure(panel, theme.px(width), theme.px(240));
                    ViewGroup scroll = (ViewGroup) panel.getChildAt(0);
                    ViewGroup contents = (ViewGroup) scroll.getChildAt(0);
                    check(contents.getMeasuredWidth() <= theme.px(width), "menu width fits");
                    Field field = NavViews.Menu.class.getDeclaredField("entries");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    List<LinearLayout> entries = (List<LinearLayout>) field.get(contents);
                    for (View entry : entries) {
                        check(entry.getHeight() >= theme.px(48), "48 dp menu target");
                        check(entry.getWidth() >= theme.px(48), "48 dp menu width");
                        check(entry.getRight() <= ((View) entry.getParent()).getWidth(), "menu entry not clipped horizontally");
                    }
                    check(entries.size() == count, "all menu entries retained");
                }
            }
        }
        System.out.println("NativeViewTest: " + checks + " checks passed");
        System.exit(0);
    }

    private static void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static List<Map<String, Object>> pages(int count) {
        List<Map<String, Object>> pages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> page = new HashMap<>();
            page.put("label", "Long example label " + i);
            page.put("opens", "Page");
            page.put("page", "demo/" + i);
            page.put("icon", "mdi:home-outline");
            page.put("iconSelected", "mdi:home");
            pages.add(page);
        }
        return pages;
    }

    private static Map<String, Object> settings(String edge, String scale, String labels) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("position", edge);
        settings.put("scale", scale);
        settings.put("labels", labels);
        settings.put("items", pages(8));
        return settings;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static final class Listener implements NavViews.Listener {
        public void onTarget(NavTarget target) {}
        public void onCollapse() {}
        public void onExpand() {}
        public void onInteraction() {}
        public void onLatency(long down, long up) {}
        public void onMenu(int index, int offset, int depth) {}
        public void onDepth(int depth) {}
        public void onCloseCamera() {}
    }
}
