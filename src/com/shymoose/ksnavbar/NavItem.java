// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One navbar entry: a page, an action, or a menu of pages and actions. */
final class NavItem {
    enum Type { PAGE, ACTION, MENU }

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

    /** A page or action from a target string, with an optional icon. The type follows the target. */
    static NavItem leaf(String label, String icon, String target) {
        return leaf(label, icon, "", target);
    }

    /** A page or action with an optional icon for its selected state, which defaults to the normal one. */
    static NavItem leaf(String label, String icon, String selectedIcon, String target) {
        NavTarget parsed = NavTarget.parse(target);
        String fallback = fallbackIcon(label, kindIcon(parsed.kind));
        String[] icons = icons(icon, selectedIcon, fallback);
        Type type = parsed.kind == NavTarget.Kind.PAGE ? Type.PAGE : Type.ACTION;
        return new NavItem(type, label, icons[0], icons[1], parsed, Collections.<NavItem>emptyList());
    }

    /**
     * A menu from {@code Label = target; Label = target = mdi:icon; ...}. Entries may also be
     * separated by new lines.
     */
    static NavItem menu(String label, String icon, String entries) {
        return menu(label, icon, "", entries);
    }

    /** A menu with an optional icon for when one of its pages is shown. */
    static NavItem menu(String label, String icon, String selectedIcon, String entries) {
        String[] icons = icons(icon, selectedIcon, fallbackIcon(label, DEFAULT_ICON));
        List<NavItem> children = new ArrayList<>();
        for (String text : (entries == null ? "" : entries).split("[;\\n]")) {
            if (text.trim().isEmpty()) continue;
            String[] fields = entry(text);
            String childLabel = fields[0].trim();
            if (childLabel.isEmpty()) throw new IllegalArgumentException("menu entry '" + text.trim() + "' has no label");
            try {
                children.add(leaf(childLabel, fields[1], fields[2]));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(childLabel + ": " + error.getMessage());
            }
        }
        if (children.isEmpty()) throw new IllegalArgumentException("menu has no entries");
        if (children.size() > MAX_CHILDREN) throw new IllegalArgumentException("menu has more than " + MAX_CHILDREN + " entries");
        return new NavItem(Type.MENU, label, icons[0], icons[1], null, children);
    }

    private static final java.util.regex.Pattern TRAILING_ICON =
        java.util.regex.Pattern.compile("^(.*?)\\s*=\\s*(mdi:[A-Za-z0-9-]+)\\s*$");

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

    /**
     * Label, icon and target of one menu entry: {@code Label = target}, optionally followed by
     * {@code = mdi:icon}. Only the first {@code =} ends the label, so a page may keep {@code =} in
     * its query.
     */
    static String[] entry(String text) {
        String entry = text.trim();
        int eq = entry.indexOf('=');
        if (eq < 0) throw new IllegalArgumentException("menu entry '" + entry + "' needs Label = target");
        String label = entry.substring(0, eq);
        String rest = entry.substring(eq + 1).trim();
        java.util.regex.Matcher icon = TRAILING_ICON.matcher(rest);
        if (icon.matches()) return new String[] {label, icon.group(2), icon.group(1)};
        return new String[] {label, "", rest};
    }
    /**
     * The icon for an item with no Icon set: one picked from its label when a rule matches, then the
     * given default, which is a camera icon for camera entries.
     */
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
    }}
