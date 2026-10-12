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

/** The navbar pill and its menu panel, drawn natively in the KS design language. */
final class NavViews {
    private NavViews() {}

    interface Listener {
        void onItem(int index, View view);
        void onChild(int itemIndex, int childIndex);
    }

    /** A Material 3 role with a fallback for KS versions that do not provide it. */
    static int color(KsTheme theme, String role, String fallback) {
        try {
            return theme.color(role);
        } catch (IllegalArgumentException missing) {
            return theme.color(fallback);
        }
    }

    /** A touch ripple in onSurface over {@code content}, clipped to a rounded rectangle. */
    static Drawable ripple(KsTheme theme, Drawable content, float radiusPx) {
        int ink = theme.color("onSurface");
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(radiusPx);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(31, Color.red(ink), Color.green(ink), Color.blue(ink))), content, mask);
    }

    static TextView icon(Context context) {
        TextView icon = new TextView(context);
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        icon.setTypeface(MdiFont.typeface(context));
        return icon;
    }

    /** A horizontal pill of items. Each item is an icon in an indicator above an optional label. */
    static final class Bar extends LinearLayout {
        private final NavConfig config;
        private final List<FrameLayout> indicators = new ArrayList<>();
        private final List<TextView> icons = new ArrayList<>();
        private final List<TextView> labels = new ArrayList<>();
        private KsTheme theme;
        private int active;

        Bar(Context context, KsTheme theme, NavConfig config, int active, Listener listener) {
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
                cell.setOnClickListener(view -> listener.onItem(index, view));
                addView(cell);
                indicators.add(indicator);
                icons.add(icon);
                labels.add(label);
            }
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
            setBackground(theme.card());
            setPadding(px(8 * s), px(6 * s), px(8 * s), px(6 * s));
            int on = color(theme, "onSecondaryContainer", "onSurface");
            int off = theme.color("onSurfaceVariant");
            int pill = color(theme, "secondaryContainer", "primaryContainer");
            for (int i = 0; i < indicators.size(); i++) {
                NavItem item = config.items.get(i);
                boolean selected = i == active;
                LinearLayout cell = (LinearLayout) indicators.get(i).getParent();
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
        private final NavConfig config;
        private final NavItem item;
        private final String path;
        private KsTheme theme;

        Menu(Context context, KsTheme theme, NavConfig config, int itemIndex, String path, Listener listener) {
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
                row.setOnClickListener(view -> listener.onChild(itemIndex, index));
                addView(row);
            }
            restyle(theme);
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            setBackground(theme.card());
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
}
