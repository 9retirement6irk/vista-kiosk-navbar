// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The validated navbar configuration, built from the plugin's KS settings. */
final class NavConfig {
    static final int SLOTS = 7;

    enum Labels { ALWAYS, ACTIVE, NEVER }

    /** Whether the bar can shrink to a tab at its edge, and how it starts. */
    enum Collapse { OFF, START_OPEN, START_COLLAPSED }

    /** How a menu shows its entries. Auto uses a grid once a list would get long. */
    enum MenuLayout { AUTO, LIST, GRID }

    /** Auto switches to a grid above this many entries. */
    static final int AUTO_GRID_ABOVE = 6;

    final boolean top;
    final Labels labels;
    final float scale;
    final int inset;
    /** Background opacity, 0.2 to 1. Icons and labels stay opaque. */
    final float opacity;
    final Collapse collapse;
    /** Seconds without a tap before an open bar collapses again. 0 never does. */
    final int autoCollapse;
    final MenuLayout menuLayout;
    final List<String> showOn;
    final List<NavItem> items;
    /** One line per item that could not be used. */
    final List<String> problems;

    private NavConfig(boolean top, Labels labels, float scale, int inset, float opacity, Collapse collapse, int autoCollapse, MenuLayout menuLayout,
                      List<String> showOn, List<NavItem> items, List<String> problems) {
        this.top = top;
        this.labels = labels;
        this.scale = scale;
        this.inset = inset;
        this.opacity = opacity;
        this.collapse = collapse;
        this.autoCollapse = autoCollapse;
        this.menuLayout = menuLayout;
        this.showOn = Collections.unmodifiableList(showOn);
        this.items = Collections.unmodifiableList(items);
        this.problems = Collections.unmodifiableList(problems);
    }

    static NavConfig parse(Map<String, Object> settings) {
        boolean top = "Top".equals(text(settings, "position"));
        String labelSetting = text(settings, "labels");
        Labels labels = "Never".equals(labelSetting) ? Labels.NEVER : "Active only".equals(labelSetting) ? Labels.ACTIVE : Labels.ALWAYS;
        float scale = 1f;
        String scaleText = text(settings, "scale").replace("%", "").trim();
        if (!scaleText.isEmpty()) {
            try { scale = Math.max(50, Math.min(200, Integer.parseInt(scaleText))) / 100f; } catch (NumberFormatException ignored) {}
        }
        int inset = number(settings, "inset", 0, 200, 12);
        float opacity = number(settings, "opacity", 20, 100, 100) / 100f;
        String collapseSetting = text(settings, "collapse");
        Collapse collapse = "Start open".equals(collapseSetting) ? Collapse.START_OPEN
            : "Start collapsed".equals(collapseSetting) ? Collapse.START_COLLAPSED : Collapse.OFF;
        int autoCollapse = collapse == Collapse.OFF ? 0 : number(settings, "autoCollapse", 0, 3600, 0);
        String layoutSetting = text(settings, "menuLayout");
        MenuLayout menuLayout = "List".equals(layoutSetting) ? MenuLayout.LIST : "Grid".equals(layoutSetting) ? MenuLayout.GRID : MenuLayout.AUTO;
        List<String> showOn = new ArrayList<>();
        for (String prefix : text(settings, "showOn").split(",")) {
            if (!prefix.trim().isEmpty()) showOn.add(Paths.normalize(prefix.trim()));
        }
        List<NavItem> items = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int slot = 1; slot <= SLOTS; slot++) {
            String type = text(settings, "item" + slot + "Type");
            if (type.isEmpty() || "Off".equals(type)) continue;
            String label = text(settings, "item" + slot + "Label");
            String icon = text(settings, "item" + slot + "Icon");
            String selectedIcon = text(settings, "item" + slot + "IconSelected");
            String target = text(settings, "item" + slot + "Target");
            String name = "Item " + slot + (label.isEmpty() ? "" : " (" + label + ")");
            try {
                if (label.isEmpty()) throw new IllegalArgumentException("label is empty");
                NavItem item = "Menu".equals(type.trim()) ? NavItem.menu(label, icon, selectedIcon, target) : NavItem.leaf(label, icon, selectedIcon, target);
                String expected = type.trim().toLowerCase(Locale.ROOT);
                if (item.type == NavItem.Type.PAGE && "action".equals(expected)) throw new IllegalArgumentException("a /path target is a Page, not an Action");
                if (item.type == NavItem.Type.ACTION && "page".equals(expected)) throw new IllegalArgumentException("a Page target must be a /dashboard/view path");
                items.add(item);
            } catch (IllegalArgumentException error) {
                problems.add(name + ": " + error.getMessage());
            }
        }
        return new NavConfig(top, labels, scale, inset, opacity, collapse, autoCollapse, menuLayout, showOn, items, problems);
    }

    /** Whether the bar belongs on {@code path}. Unknown paths show it, so it is never stuck hidden. */
    boolean visibleAt(String path) {
        if (items.isEmpty()) return false;
        if (showOn.isEmpty() || path == null) return true;
        for (String prefix : showOn) if (Paths.within(path, prefix)) return true;
        return false;
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
        text.append(top).append('|').append(labels).append('|').append(scale).append('|').append(inset)
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
