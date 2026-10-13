// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One navbar entry: a page, an action, or a menu of pages and actions. */
final class NavItem {
    enum Type { PAGE, ACTION, MENU }

    /** The Opens choices in settings, in the order they are offered. */
    static final String PAGE = "Page";
    static final String CAMERA = "Camera view";
    static final String MENU = "Menu";
    static final String ACTION = "Kiosk action";
    static final String WEB = "Web address";

    static final String DEFAULT_ICON = "mdi:circle-outline";
    static final String CAMERA_ICON = "mdi:cctv";
    static final int MAX_CHILDREN = 16;

    final Type type;
    final String label;
    final String icon;
    final String iconSelected;
    /** Null for menus. */
    final NavTarget target;
    /** Empty unless this is a menu. */
    final List<NavItem> children;

    NavItem(Type type, String label, String icon, String iconSelected, NavTarget target, List<NavItem> children) {
        this.type = type;
        this.label = label;
        this.icon = icon;
        this.iconSelected = iconSelected;
        this.target = target;
        this.children = Collections.unmodifiableList(new ArrayList<>(children));
    }

    /**
     * An item from one row of the Items list, or an entry from a menu's Entries list. KS fills in
     * every field, including hidden ones, so only the fields {@code opens} selects are read.
     */
    static NavItem fromRow(Map<?, ?> row, boolean allowMenu) {
        String label = text(row, "label");
        if (label.isEmpty()) throw new IllegalArgumentException("needs a label");
        String opens = text(row, "opens");
        if (opens.isEmpty()) opens = PAGE;
        if (MENU.equals(opens)) {
            if (!allowMenu) throw new IllegalArgumentException("a menu entry cannot open another menu");
            return menu(label, text(row, "icon"), text(row, "iconSelected"), rows(row.get("entries")));
        }
        NavTarget target;
        switch (opens) {
            case PAGE: target = NavTarget.page(text(row, "page")); break;
            case CAMERA: target = NavTarget.camera(text(row, "camera")); break;
            case ACTION: target = NavTarget.action(text(row, "action")); break;
            case WEB: target = NavTarget.url(text(row, "url")); break;
            default: throw new IllegalArgumentException("unknown Opens choice '" + opens + "'");
        }
        return leaf(label, text(row, "icon"), text(row, "iconSelected"), target);
    }

    /** A page or action with optional icons. A blank icon is picked from the label or the target. */
    static NavItem leaf(String label, String icon, String selectedIcon, NavTarget target) {
        String fallback = fallbackIcon(label, kindIcon(target.kind));
        String[] icons = icons(icon, selectedIcon, fallback);
        Type type = target.kind == NavTarget.Kind.PAGE ? Type.PAGE : Type.ACTION;
        return new NavItem(type, label, icons[0], icons[1], target, Collections.<NavItem>emptyList());
    }

    /** A menu of entry rows. */
    static NavItem menu(String label, String icon, String selectedIcon, List<Map<?, ?>> entries) {
        String[] icons = icons(icon, selectedIcon, fallbackIcon(label, DEFAULT_ICON));
        List<NavItem> children = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
            String childLabel = text(entry, "label");
            try {
                children.add(fromRow(entry, false));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException((childLabel.isEmpty() ? "an entry" : childLabel) + " " + error.getMessage());
            }
        }
        if (children.isEmpty()) throw new IllegalArgumentException("menu has no entries");
        if (children.size() > MAX_CHILDREN) throw new IllegalArgumentException("menu has more than " + MAX_CHILDREN + " entries");
        return new NavItem(Type.MENU, label, icons[0], icons[1], null, children);
    }

    /** The icon a target's kind suggests when the label suggests none. */
    static String kindIcon(NavTarget.Kind kind) {
        switch (kind) {
            case CAMERA: return CAMERA_ICON;
            case MUSIC: return "mdi:music";
            case NOW_PLAYING: return "mdi:play-circle-outline";
            case SCREENSAVER: return "mdi:monitor-shimmer";
            case APPS: return "mdi:apps";
            case RELOAD: return "mdi:refresh";
            case HOME: return "mdi:home-outline";
            case URL: return "mdi:web";
            default: return DEFAULT_ICON;
        }
    }

    /** An icon picked from the label when a rule matches, otherwise the given default. */
    static String fallbackIcon(String label, String otherwise) {
        String guessed = IconGuess.forLabel(label);
        return guessed != null ? guessed : otherwise;
    }

    /** Whether this item, or one of its menu entries, is the page at {@code path}. */
    boolean matches(String path) {
        if (path == null) return false;
        if (type == Type.MENU) {
            for (NavItem child : children) if (child.matches(path)) return true;
            return false;
        }
        String own = target.matchPath();
        return own != null && own.equals(Paths.normalize(path));
    }

    /** The normal and selected icon. A blank icon uses the fallback, a blank selected icon the normal one. */
    static String[] icons(String icon, String selectedIcon, String fallback) {
        String normal = icon == null || icon.trim().isEmpty() ? fallback : icon.trim();
        String selected = selectedIcon == null || selectedIcon.trim().isEmpty() ? normal : selectedIcon.trim();
        return new String[] {normal, selected};
    }

    /** The rows of a list setting. Anything that is not a list of maps reads as empty. */
    static List<Map<?, ?>> rows(Object value) {
        List<Map<?, ?>> rows = new ArrayList<>();
        if (value instanceof List) {
            for (Object row : (List<?>) value) if (row instanceof Map) rows.add((Map<?, ?>) row);
        }
        return rows;
    }

    static String text(Map<?, ?> row, String key) {
        Object value = row.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}