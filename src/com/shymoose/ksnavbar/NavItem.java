// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One navbar entry: a page, an action, or a menu of pages and actions. */
final class NavItem {
    enum Type { PAGE, ACTION, MENU }

    static final String DEFAULT_ICON = "mdi:circle-outline";
    static final int MAX_CHILDREN = 12;

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

    /** A page or action from a target string. The type follows the target. */
    static NavItem leaf(String label, String iconSpec, String target) {
        NavTarget parsed = NavTarget.parse(target);
        String[] icons = icons(iconSpec);
        Type type = parsed.kind == NavTarget.Kind.PAGE ? Type.PAGE : Type.ACTION;
        return new NavItem(type, label, icons[0], icons[1], parsed, Collections.<NavItem>emptyList());
    }

    /**
     * A menu from {@code Label|mdi:icon|target; Label|mdi:icon|target; ...}. Entries may also be
     * separated by new lines. The icon may be empty.
     */
    static NavItem menu(String label, String iconSpec, String entries) {
        String[] icons = icons(iconSpec);
        List<NavItem> children = new ArrayList<>();
        for (String entry : (entries == null ? "" : entries).split("[;\\n]")) {
            if (entry.trim().isEmpty()) continue;
            String[] fields = entry.split("\\|", -1);
            if (fields.length != 3) throw new IllegalArgumentException("menu entry '" + entry.trim() + "' needs Label|icon|target");
            String childLabel = fields[0].trim();
            if (childLabel.isEmpty()) throw new IllegalArgumentException("menu entry '" + entry.trim() + "' has no label");
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

    /** {@code mdi:a} or {@code mdi:a|mdi:b} for the unselected and selected icon. */
    static String[] icons(String spec) {
        String text = spec == null ? "" : spec.trim();
        String[] parts = text.split("\\|", -1);
        String normal = parts[0].trim().isEmpty() ? DEFAULT_ICON : parts[0].trim();
        String selected = parts.length > 1 && !parts[1].trim().isEmpty() ? parts[1].trim() : normal;
        return new String[] {normal, selected};
    }
}
