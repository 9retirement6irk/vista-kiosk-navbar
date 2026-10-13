// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The validated navbar configuration, built from the plugin's KS settings. */
final class NavConfig {
    static final int SLOTS = 7;
    static final int DEFAULT_INSET = 12;

    /** The screen edge the bar sits against. */
    enum Edge { BOTTOM, TOP, LEFT, RIGHT }

    enum Labels { ALWAYS, ACTIVE, NEVER }

    /** Whether the bar can shrink to a tab at its edge, and how it starts. */
    enum Collapse { OFF, START_OPEN, START_COLLAPSED }

    /** How a menu shows its entries. Auto uses a grid once a list would get long. */
    enum MenuLayout { AUTO, LIST, GRID }

    /** Auto switches to a grid above this many entries. */
    static final int AUTO_GRID_ABOVE = 6;

    final Edge edge;
    /** Whether the bar is against the top edge. */
    final boolean top;
    /** Whether the bar is a vertical rail against the left or right edge. */
    final boolean vertical;
    final Labels labels;
    final float scale;
    final int inset;
    /** Background opacity, 0.2 to 1. Icons and labels stay opaque. */
    final float opacity;
    final Collapse collapse;
    /** Seconds without a tap before an open bar collapses again. 0 never does. */
    final int autoCollapse;
    final MenuLayout menuLayout;
    final List<String> hideOn;
    final List<NavItem> items;
    /** One line per item that could not be used. */
    final List<String> problems;

    private NavConfig(Edge edge, Labels labels, float scale, int inset, float opacity, Collapse collapse, int autoCollapse, MenuLayout menuLayout,
                      List<String> hideOn, List<NavItem> items, List<String> problems) {
        this.edge = edge;
        this.top = edge == Edge.TOP;
        this.vertical = edge == Edge.LEFT || edge == Edge.RIGHT;
        this.labels = labels;
        this.scale = scale;
        this.inset = inset;
        this.opacity = opacity;
        this.collapse = collapse;
        this.autoCollapse = autoCollapse;
        this.menuLayout = menuLayout;
        this.hideOn = Collections.unmodifiableList(hideOn);
        this.items = Collections.unmodifiableList(items);
        this.problems = Collections.unmodifiableList(problems);
    }

    static NavConfig parse(Map<String, Object> settings) {
        String position = text(settings, "position");
        Edge edge = "Top".equals(position) ? Edge.TOP : "Left".equals(position) ? Edge.LEFT
            : "Right".equals(position) ? Edge.RIGHT : Edge.BOTTOM;
        String labelSetting = text(settings, "labels");
        Labels labels = "Never".equals(labelSetting) ? Labels.NEVER : "Active only".equals(labelSetting) ? Labels.ACTIVE : Labels.ALWAYS;
        float scale = 1f;
        String scaleText = text(settings, "scale").replace("%", "").trim();
        if (!scaleText.isEmpty()) {
            try { scale = Math.max(50, Math.min(200, Integer.parseInt(scaleText))) / 100f; } catch (NumberFormatException ignored) {}
        }
        // Fixed: the bar sits a comfortable distance from the edge, one setting fewer.
        int inset = DEFAULT_INSET;
        float opacity = number(settings, "opacity", 20, 100, 100) / 100f;
        String collapseSetting = text(settings, "collapse");
        Collapse collapse = "Off".equals(collapseSetting) ? Collapse.OFF
            : "Start tucked away".equals(collapseSetting) ? Collapse.START_COLLAPSED : Collapse.START_OPEN;
        int autoCollapse = collapse == Collapse.OFF ? 0 : number(settings, "autoCollapse", 0, 3600, 0);
        // Menus pick their own layout: a list, or a grid once a list would get long.
        MenuLayout menuLayout = MenuLayout.AUTO;
        List<String> hideOn = new ArrayList<>();
        for (String prefix : text(settings, "hideOn").split(",")) {
            if (!prefix.trim().isEmpty()) hideOn.add(Paths.normalize(prefix.trim()));
        }
        List<NavItem> items = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int slot = 1; slot <= SLOTS; slot++) {
            // An item is on when it has a label. Its kind follows its target, so there is no Type.
            String label = text(settings, "item" + slot + "Label");
            if (label.isEmpty()) continue;
            String icon = text(settings, "item" + slot + "Icon");
            String selectedIcon = text(settings, "item" + slot + "IconSelected");
            String target = text(settings, "item" + slot + "Target");
            String name = "Item " + slot + " (" + label + ")";
            try {
                NavItem item = isMenu(target) ? NavItem.menu(label, icon, selectedIcon, target) : NavItem.leaf(label, icon, selectedIcon, target);
                items.add(item);
            } catch (IllegalArgumentException error) {
                problems.add(name + ": " + error.getMessage());
            }
        }
        return new NavConfig(edge, labels, scale, inset, opacity, collapse, autoCollapse, menuLayout, hideOn, items, problems);
    }

    /**
     * A target listing entries makes a menu: {@code Label = target; Label = target}. A page or URL
     * may hold {@code =} in its query, so only text that starts as a label counts.
     */
    static boolean isMenu(String target) {
        if (target == null) return false;
        String text = target.trim();
        if (text.contains(";") || text.contains("\n")) return true;
        if (text.indexOf('=') <= 0) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return !text.startsWith("/") && !lower.startsWith("http://") && !lower.startsWith("https://");
    }

    /** Whether the bar belongs on {@code path}. It shows everywhere except the Hide on paths. */
    boolean visibleAt(String path) {
        if (items.isEmpty()) return false;
        if (path == null) return true;
        for (String prefix : hideOn) if (Paths.within(path, prefix)) return false;
        return true;
    }

    /** Whether a menu shows its entries as a grid of tiles rather than a list. */
    boolean grid(NavItem menu) {
        if (menuLayout == MenuLayout.GRID) return true;
        if (menuLayout == MenuLayout.LIST) return false;
        return menu.children.size() > AUTO_GRID_ABOVE;
    }

    /** The first item that is the page at {@code path}, or -1. */
    int activeIndex(String path) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).matches(path)) return i;
        return -1;
    }

    /** Whether both configurations draw the same bar. The Show on paths are ignored. */
    boolean sameLook(NavConfig other) {
        return other != null && describe().equals(other.describe());
    }

    String describe() {
        StringBuilder text = new StringBuilder();
        text.append(edge).append('|').append(labels).append('|').append(scale).append('|').append(inset)
            .append('|').append(opacity).append('|').append(collapse).append('|').append(menuLayout);
        for (NavItem item : items) appendItem(text, item);
        return text.toString();
    }

    private static void appendItem(StringBuilder text, NavItem item) {
        text.append('[').append(item.type).append(',').append(item.label).append(',').append(item.icon).append(',').append(item.iconSelected);
        if (item.target != null) text.append(',').append(item.target.kind).append(':').append(item.target.value);
        for (NavItem child : item.children) appendItem(text, child);
        text.append(']');
    }

    private static int number(Map<String, Object> settings, String key, int min, int max, int fallback) {
        Object value = settings.get(key);
        return value instanceof Number ? Math.max(min, Math.min(max, ((Number) value).intValue())) : fallback;
    }

    private static String text(Map<String, Object> settings, String key) {
        Object value = settings.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}
