// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * The navbar, drawn natively in the KS design language. One overlay holds everything: the bar, the
 * tab it collapses into and the open menu. Creating a KS overlay is slow on low-power panels, so
 * collapsing, expanding and opening menus only change visibility inside this one view, and the
 * wrapped overlay resizes to fit.
 */
final class NavViews {
    private NavViews() {}

    /** What the plugin hears from the bar. All calls arrive on the main thread. */
    interface Listener {
        void onTarget(NavTarget target);
        void onCollapse();
        void onExpand();
        /** Any tap, to restart the auto-collapse countdown. */
        void onInteraction();
    }

    /** A Material 3 role with a fallback for KS versions that do not provide it. */
    static int color(KsTheme theme, String role, String fallback) {
        try {
            return theme.color(role);
        } catch (IllegalArgumentException missing) {
            return theme.color(fallback);
        }
    }

    /** The KS card background at the configured opacity. Content drawn on it stays opaque. */
    static GradientDrawable surface(KsTheme theme, NavConfig config) {
        GradientDrawable card = theme.card();
        card.setAlpha(Math.round(255 * config.opacity));
        return card;
    }

    /** A touch ripple in onSurface over {@code content}, clipped to a rounded rectangle. */
    static Drawable ripple(KsTheme theme, Drawable content, float radiusPx) {
        int ink = theme.color("onSurface");
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(radiusPx);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(31, Color.red(ink), Color.green(ink), Color.blue(ink))), content, mask);
    }

    /** Up for a bottom bar opening, down for a top bar opening, and the reverse toward the edge. */
    static String chevron(NavConfig config, boolean towardEdge) {
        boolean up = config.top == towardEdge;
        return MdiFont.glyph(up ? "mdi:chevron-up" : "mdi:chevron-down");
    }

    static TextView icon(Context context) {
        TextView icon = new TextView(context);
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        icon.setTypeface(MdiFont.typeface(context));
        return icon;
    }

    /**
     * Stacks the menu, the bar and the tab, edge last. Taps on its transparent space close an open
     * menu. Every method runs on the main thread.
     */
    static final class Root extends LinearLayout {
        private final NavConfig config;
        private final Listener listener;
        private final Bar bar;
        private final Tab tab;
        private final FrameLayout menuHolder;
        private Menu menu;
        private KsTheme theme;
        private String path;
        private int active;
        private int openMenu = -1;
        private boolean collapsed;

        Root(Context context, KsTheme theme, NavConfig config, String path, boolean collapsed, Listener listener) {
            super(context);
            this.config = config;
            this.listener = listener;
            this.theme = theme;
            this.path = path;
            this.active = config.activeIndex(path);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setClipChildren(false);
            bar = new Bar(context, theme, config, active, new Bar.Taps() {
                @Override public void onItem(int index) { tapItem(index); }
                @Override public void onCollapse() { listener.onInteraction(); listener.onCollapse(); }
            });
            tab = new Tab(context, theme, config, active, view -> {
                listener.onInteraction();
                listener.onExpand();
            });
            menuHolder = new FrameLayout(context);
            menuHolder.setVisibility(GONE);
            // The menu sits on the side away from the edge: above a bottom bar, below a top bar.
            if (config.top) {
                addView(bar);
                addView(tab);
                addView(menuHolder);
            } else {
                addView(menuHolder);
                addView(bar);
                addView(tab);
            }
            setOnClickListener(view -> closeMenu());
            setCollapsed(collapsed);
            restyle(theme);
        }

        void setPath(String next) {
            if (java.util.Objects.equals(next, path)) return;
            path = next;
            int index = config.activeIndex(next);
            if (index != active) {
                active = index;
                bar.setActive(index);
                tab.setActive(index);
            }
            closeMenu();
        }

        void setCollapsed(boolean value) {
            closeMenu();
            collapsed = value && config.collapse != NavConfig.Collapse.OFF;
            bar.setVisibility(collapsed ? GONE : VISIBLE);
            tab.setVisibility(collapsed ? VISIBLE : GONE);
        }

        boolean isCollapsed() { return collapsed; }

        void closeMenu() {
            if (openMenu < 0) return;
            openMenu = -1;
            menu = null;
            menuHolder.removeAllViews();
            menuHolder.setVisibility(GONE);
        }

        private void tapItem(int index) {
            listener.onInteraction();
            NavItem item = config.items.get(index);
            if (item.type != NavItem.Type.MENU) {
                closeMenu();
                listener.onTarget(item.target);
                return;
            }
            if (openMenu == index) {
                closeMenu();
                return;
            }
            openMenu = index;
            menu = new Menu(getContext(), theme, config, index, path, child -> {
                listener.onInteraction();
                closeMenu();
                listener.onTarget(item.children.get(child).target);
            });
            menuHolder.removeAllViews();
            menuHolder.addView(menu, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            menuHolder.setVisibility(VISIBLE);
            // Line the menu up with its item once both are laid out, within the overlay's width.
            final Menu shown = menu;
            shown.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                if (menu != shown) return;
                float center = bar.getLeft() + bar.itemCenter(index);
                float x = Math.max(0, Math.min(menuHolder.getWidth() - shown.getWidth(), center - shown.getWidth() / 2f));
                shown.setTranslationX(x);
            });
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            int edge = theme.px(config.inset);
            int gap = theme.px(8 * s);
            bar.restyle(theme);
            tab.restyle(theme);
            if (menu != null) menu.restyle(theme);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            // Insets are inside the view: the overlay itself sits flush so the tab can touch the edge.
            barParams.leftMargin = edge;
            barParams.rightMargin = edge;
            if (config.top) {
                barParams.topMargin = edge;
                menuParams.topMargin = gap;
            } else {
                barParams.bottomMargin = edge;
                menuParams.bottomMargin = gap;
            }
            bar.setLayoutParams(barParams);
            menuHolder.setLayoutParams(menuParams);
            tab.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
    }

    /** A horizontal pill of items. Each item is an icon in an indicator above an optional label. */
    static final class Bar extends LinearLayout {
        interface Taps {
            void onItem(int index);
            void onCollapse();
        }

        private final NavConfig config;
        private final List<LinearLayout> cells = new ArrayList<>();
        private final List<FrameLayout> indicators = new ArrayList<>();
        private final List<TextView> icons = new ArrayList<>();
        private final List<TextView> labels = new ArrayList<>();
        private final TextView collapse;
        private KsTheme theme;
        private int active;

        Bar(Context context, KsTheme theme, NavConfig config, int active, Taps taps) {
            super(context);
            this.config = config;
            this.theme = theme;
            this.active = active;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < config.items.size(); i++) {
                NavItem item = config.items.get(i);
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setContentDescription(item.label);
                FrameLayout indicator = new FrameLayout(context);
                TextView icon = icon(context);
                indicator.addView(icon, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                TextView label = new TextView(context);
                label.setText(item.label);
                label.setSingleLine(true);
                label.setGravity(Gravity.CENTER);
                label.setIncludeFontPadding(false);
                cell.addView(indicator);
                cell.addView(label);
                final int index = i;
                cell.setOnClickListener(view -> taps.onItem(index));
                addView(cell);
                cells.add(cell);
                indicators.add(indicator);
                icons.add(icon);
                labels.add(label);
            }
            if (config.collapse != NavConfig.Collapse.OFF) {
                collapse = icon(context);
                collapse.setContentDescription("Collapse navbar");
                collapse.setOnClickListener(view -> taps.onCollapse());
                addView(collapse);
            } else {
                collapse = null;
            }
            restyle(theme);
        }

        /** The horizontal center of an item, relative to the bar. */
        float itemCenter(int index) {
            View cell = cells.get(index);
            return cell.getLeft() + cell.getWidth() / 2f;
        }

        void setActive(int index) {
            if (index == active) return;
            active = index;
            restyle(theme);
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            setBackground(surface(theme, config));
            setPadding(px(8 * s), px(6 * s), px(8 * s), px(6 * s));
            if (collapse != null) {
                collapse.setText(chevron(config, true));
                collapse.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22 * s));
                collapse.setTextColor(theme.color("onSurfaceVariant"));
                collapse.setBackground(ripple(theme, null, px(theme.radiusRow())));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(px(40 * s), px(48 * s));
                params.leftMargin = px(4 * s);
                collapse.setLayoutParams(params);
            }
            int on = color(theme, "onSecondaryContainer", "onSurface");
            int off = theme.color("onSurfaceVariant");
            int pill = color(theme, "secondaryContainer", "primaryContainer");
            for (int i = 0; i < cells.size(); i++) {
                NavItem item = config.items.get(i);
                boolean selected = i == active;
                LinearLayout cell = cells.get(i);
                cell.setBackground(ripple(theme, null, px(theme.radiusRow())));
                cell.setMinimumWidth(px(68 * s));
                cell.setPadding(px(6 * s), px(6 * s), px(6 * s), px(6 * s));
                GradientDrawable indicator = new GradientDrawable();
                indicator.setCornerRadius(px(16 * s));
                indicator.setColor(selected ? pill : Color.TRANSPARENT);
                FrameLayout frame = indicators.get(i);
                frame.setBackground(indicator);
                frame.setLayoutParams(new LinearLayout.LayoutParams(px(56 * s), px(32 * s)));
                TextView icon = icons.get(i);
                icon.setText(MdiFont.glyph(selected ? item.iconSelected : item.icon));
                icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24 * s));
                icon.setTextColor(selected ? on : off);
                TextView label = labels.get(i);
                theme.styleText(label, 12 * s, selected ? 600 : 500, selected ? "onSurface" : "onSurfaceVariant");
                boolean show = config.labels == NavConfig.Labels.ALWAYS || (config.labels == NavConfig.Labels.ACTIVE && selected);
                label.setVisibility(show ? VISIBLE : GONE);
                LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                spacing.topMargin = px(4 * s);
                label.setLayoutParams(spacing);
            }
        }

        private int px(float dp) { return theme.px(dp); }
    }

    /** The entries of a menu item, as a card of rows. The current page's row is highlighted. */
    static final class Menu extends LinearLayout {
        interface Taps {
            void onChild(int index);
        }

        private final NavConfig config;
        private final NavItem item;
        private final String path;
        private KsTheme theme;

        Menu(Context context, KsTheme theme, NavConfig config, int itemIndex, String path, Taps taps) {
            super(context);
            this.config = config;
            this.item = config.items.get(itemIndex);
            this.path = path;
            this.theme = theme;
            setOrientation(VERTICAL);
            for (int i = 0; i < item.children.size(); i++) {
                NavItem child = item.children.get(i);
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setContentDescription(child.label);
                row.addView(icon(context));
                TextView label = new TextView(context);
                label.setText(child.label);
                label.setSingleLine(true);
                row.addView(label);
                final int index = i;
                row.setOnClickListener(view -> taps.onChild(index));
                addView(row);
            }
            restyle(theme);
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            setBackground(surface(theme, config));
            setPadding(px(8 * s), px(8 * s), px(8 * s), px(8 * s));
            int pill = color(theme, "secondaryContainer", "primaryContainer");
            int on = color(theme, "onSecondaryContainer", "onSurface");
            for (int i = 0; i < getChildCount(); i++) {
                NavItem child = item.children.get(i);
                boolean selected = child.matches(path);
                LinearLayout row = (LinearLayout) getChildAt(i);
                GradientDrawable background = new GradientDrawable();
                background.setCornerRadius(px(theme.radiusRow()));
                background.setColor(selected ? pill : Color.TRANSPARENT);
                row.setBackground(ripple(theme, background, px(theme.radiusRow())));
                row.setPadding(px(14 * s), px(10 * s), px(20 * s), px(10 * s));
                row.setMinimumHeight(px(48 * s));
                row.setMinimumWidth(px(220 * s));
                TextView icon = (TextView) row.getChildAt(0);
                icon.setText(MdiFont.glyph(selected ? child.iconSelected : child.icon));
                icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22 * s));
                icon.setTextColor(selected ? on : theme.color("onSurfaceVariant"));
                TextView label = (TextView) row.getChildAt(1);
                theme.styleText(label, 15 * s, selected ? 600 : 500, "onSurface");
                LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                gap.leftMargin = px(14 * s);
                label.setLayoutParams(gap);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                if (i > 0) rowParams.topMargin = px(2 * s);
                row.setLayoutParams(rowParams);
            }
        }

        private int px(float dp) { return theme.px(dp); }
    }

    /**
     * The collapsed bar: a small tab flush with the edge, showing the current item's icon and a
     * chevron. Tapping it opens the bar.
     */
    static final class Tab extends LinearLayout {
        private final NavConfig config;
        private final TextView current;
        private final TextView chevron;
        private int active;
        private KsTheme theme;

        Tab(Context context, KsTheme theme, NavConfig config, int active, View.OnClickListener open) {
            super(context);
            this.config = config;
            this.theme = theme;
            this.active = active;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setContentDescription("Open navbar");
            current = icon(context);
            chevron = icon(context);
            addView(current);
            addView(chevron);
            setOnClickListener(open);
            restyle(theme);
        }

        void setActive(int index) {
            if (index == active) return;
            active = index;
            restyle(theme);
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            float r = theme.px(theme.radiusCard());
            GradientDrawable shape = surface(theme, config);
            // Round only the corners away from the edge, so the tab reads as attached to it.
            shape.setCornerRadii(config.top
                ? new float[] {0, 0, 0, 0, r, r, r, r}
                : new float[] {r, r, r, r, 0, 0, 0, 0});
            setBackground(ripple(theme, shape, r));
            setMinimumWidth(theme.px(96 * s));
            setMinimumHeight(theme.px(40 * s));
            setPadding(theme.px(20 * s), theme.px(6 * s), theme.px(16 * s), theme.px(6 * s));
            current.setVisibility(active >= 0 ? VISIBLE : GONE);
            if (active >= 0) {
                NavItem item = config.items.get(active);
                current.setText(MdiFont.glyph(item.iconSelected));
                current.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.px(20 * s));
                current.setTextColor(theme.color("onSurface"));
                LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                gap.rightMargin = theme.px(8 * s);
                current.setLayoutParams(gap);
            }
            chevron.setText(chevron(config, false));
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.px(22 * s));
            chevron.setTextColor(theme.color("onSurfaceVariant"));
        }
    }
}
