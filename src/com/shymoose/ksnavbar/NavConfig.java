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

    final boolean top;
    final Labels labels;
    final float scale;
    final int inset;
    final List<String> showOn;
    final List<NavItem> items;
    /** One line per item that could not be used. */
    final List<String> problems;

    private NavConfig(boolean top, Labels labels, float scale, int inset, List<String> showOn, List<NavItem> items, List<String> problems) {
        this.top = top;
        this.labels = labels;
        this.scale = scale;
        this.inset = inset;
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
        Object insetValue = settings.get("inset");
        int inset = insetValue instanceof Number ? Math.max(0, Math.min(200, ((Number) insetValue).intValue())) : 12;
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
            String target = text(settings, "item" + slot + "Target");
            String name = "Item " + slot + (label.isEmpty() ? "" : " (" + label + ")");
            try {
                if (label.isEmpty()) throw new IllegalArgumentException("label is empty");
                NavItem item = "Menu".equals(type.trim()) ? NavItem.menu(label, icon, target) : NavItem.leaf(label, icon, target);
                String expected = type.trim().toLowerCase(Locale.ROOT);
                if (item.type == NavItem.Type.PAGE && "action".equals(expected)) throw new IllegalArgumentException("a /path target is a Page, not an Action");
                if (item.type == NavItem.Type.ACTION && "page".equals(expected)) throw new IllegalArgumentException("a Page target must be a /dashboard/view path");
                items.add(item);
            } catch (IllegalArgumentException error) {
                problems.add(name + ": " + error.getMessage());
            }
        }
        return new NavConfig(top, labels, scale, inset, showOn, items, problems);
    }

    /** Whether the bar belongs on {@code path}. Unknown paths show it, so it is never stuck hidden. */
    boolean visibleAt(String path) {
        if (items.isEmpty()) return false;
        if (showOn.isEmpty() || path == null) return true;
        for (String prefix : showOn) if (Paths.within(path, prefix)) return true;
        return false;
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
        text.append(top).append('|').append(labels).append('|').append(scale).append('|').append(inset);
        for (NavItem item : items) appendItem(text, item);
        return text.toString();
    }

    private static void appendItem(StringBuilder text, NavItem item) {
        text.append('[').append(item.type).append(',').append(item.label).append(',').append(item.icon).append(',').append(item.iconSelected);
        if (item.target != null) text.append(',').append(item.target.kind).append(':').append(item.target.value);
        for (NavItem child : item.children) appendItem(text, child);
        text.append(']');
    }

    private static String text(Map<String, Object> settings, String key) {
        Object value = settings.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}
