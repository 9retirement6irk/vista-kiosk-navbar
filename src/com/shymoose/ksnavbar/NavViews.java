// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
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
        /**
         * How late a tap reached the view and when the view next drew, in ms. Touches pass through
         * Flutter before reaching a plugin view, so delivery time is KS's share of any lag.
         */
        void onLatency(long downMs, long upMs, long drawMs);
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

    /**
     * Pressed feedback without animation: an onSurface wash that appears on touch down and goes on
     * release. A ripple animates for about 300 ms, and every one of its frames has to cross KS's
     * overlay texture, which arrives late on a low-power panel. This costs one frame each way.
     */
    static Drawable pressable(KsTheme theme, Drawable content, float radiusPx) {
        return pressable(theme, content, new float[] {radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx});
    }

    static Drawable pressable(KsTheme theme, Drawable content, float[] radii) {
        int ink = theme.color("onSurface");
        GradientDrawable wash = new GradientDrawable();
        wash.setCornerRadii(radii);
        wash.setColor(Color.argb(36, Color.red(ink), Color.green(ink), Color.blue(ink)));
        StateListDrawable states = new StateListDrawable();
        states.setEnterFadeDuration(0);
        states.setExitFadeDuration(0);
        states.addState(new int[] {android.R.attr.state_pressed},
            content == null ? wash : new LayerDrawable(new Drawable[] {content, wash}));
        states.addState(new int[0], content == null ? new ColorDrawable(Color.TRANSPARENT) : content);
        return states;
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
            bar = new Bar(context, theme, config, active, this::tapItem);
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

        /**
         * KS resizes a wrapped overlay after measuring it, but nothing then redraws the view into the
         * resized surface, so the old frame shows stretched. Seen on a ThinkSmart View (Android 8.1):
         * a 1 px first frame stretched into a blank box, and a tab stretched to the size of the bar.
         * Redrawing whenever the host frame changes size fixes both.
         */
        private final View.OnLayoutChangeListener hostResized = (view, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) redraw();
        };

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (getParent() instanceof View) ((View) getParent()).addOnLayoutChangeListener(hostResized);
            redraw();
        }

        @Override protected void onDetachedFromWindow() {
            if (getParent() instanceof View) ((View) getParent()).removeOnLayoutChangeListener(hostResized);
            removeCallbacks(redrawTree);
            super.onDetachedFromWindow();
        }

        private final Runnable redrawTree = () -> invalidateTree(this);

        /** Redraw now and again shortly after, once KS has applied a new size. */
        void redraw() {
            invalidateTree(this);
            removeCallbacks(redrawTree);
            postDelayed(redrawTree, 150);
            postDelayed(redrawTree, 600);
        }

        private static void invalidateTree(View view) {
            view.invalidate();
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) invalidateTree(group.getChildAt(i));
            }
        }

        void setPath(String next) {
            if (java.util.Objects.equals(next, path)) return;
            path = next;
            removeCallbacks(settle);
            showActive(config.activeIndex(next));
            closeMenu();
        }

        /** Falls back to the real page if a tapped page never loads. */
        private final Runnable settle = this::settle;

        private void settle() {
            showActive(config.activeIndex(path));
        }

        /**
         * Highlights a tapped page at once, the way a native tab bar does, instead of waiting for
         * Home Assistant to report the new page. If the page has not changed after a few seconds,
         * the highlight goes back to where the dashboard really is.
         */
        private void preview(int index) {
            showActive(index);
            removeCallbacks(settle);
            postDelayed(settle, 5000);
        }

        private void showActive(int index) {
            if (index == active) return;
            active = index;
            bar.setActive(index);
            tab.setActive(index);
        }

        private long downDelay = -1;
        private long upDelay = -1;
        private long downAt;
        private boolean measureDraw;

        /** Measures how late touches arrive. The event time is when the finger actually moved. */
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            long now = SystemClock.uptimeMillis();
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downDelay = now - event.getEventTime();
                downAt = now;
                upDelay = -1;
                measureDraw = true;
            } else if (action == MotionEvent.ACTION_UP && downDelay >= 0) {
                upDelay = now - event.getEventTime();
                long draw = drawDelay;
                long down = downDelay;
                downDelay = -1;
                try { listener.onLatency(down, upDelay, draw); } catch (RuntimeException ignored) {}
            }
            return super.dispatchTouchEvent(event);
        }

        private long drawDelay = -1;

        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            super.dispatchDraw(canvas);
            if (measureDraw) {
                measureDraw = false;
                drawDelay = SystemClock.uptimeMillis() - downAt;
            }
        }

        void setCollapsed(boolean value) {
            closeMenu();
            collapsed = value && config.collapse != NavConfig.Collapse.OFF;
            bar.setVisibility(collapsed ? GONE : VISIBLE);
            tab.setVisibility(collapsed ? VISIBLE : GONE);
            redraw();
        }

        boolean isCollapsed() { return collapsed; }

        void closeMenu() {
            if (openMenu < 0) return;
            openMenu = -1;
            menu = null;
            menuHolder.removeAllViews();
            menuHolder.setVisibility(GONE);
            redraw();
        }

        private void tapItem(int index) {
            listener.onInteraction();
            NavItem item = config.items.get(index);
            if (item.type != NavItem.Type.MENU) {
                closeMenu();
                if (item.type == NavItem.Type.PAGE) preview(index);
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
                NavItem entry = item.children.get(child);
                if (entry.type == NavItem.Type.PAGE) preview(index);
                listener.onTarget(entry.target);
            });
            menuHolder.removeAllViews();
            menuHolder.addView(menu, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            menuHolder.setVisibility(VISIBLE);
            redraw();
            // Line the menu up with its item once both are laid out, within the overlay's width.
            final Menu shown = menu;
            shown.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                if (menu != shown) return;
                float center = bar.getLeft() + bar.itemCenter(index);
                float x = Math.max(0, Math.min(menuHolder.getWidth() - shown.getWidth(), center - shown.getWidth() / 2f));
                shown.setTranslationX(x);
            });
        }

        private float startX;
        private float startY;
        private boolean swiped;

        /**
         * A swipe toward the bar's edge folds it into its tab, and a swipe away from the edge opens
         * it again. The action fires as soon as the swipe is recognized, not on release, so it keeps
         * up with the finger even when touches reach the view late.
         */
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (config.collapse == NavConfig.Collapse.OFF) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    swiped = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    return !swiped && checkSwipe(event);
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (swiped) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swiped = false;
                return true;
            }
            // A swipe that starts on empty space, where no child takes the touch.
            if (config.collapse != NavConfig.Collapse.OFF) {
                if (action == MotionEvent.ACTION_DOWN) {
                    startX = event.getX();
                    startY = event.getY();
                } else if (action == MotionEvent.ACTION_MOVE && checkSwipe(event)) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }

        private boolean checkSwipe(MotionEvent event) {
            float dx = event.getX() - startX;
            float dy = event.getY() - startY;
            float threshold = theme.px(24 * config.scale);
            if (Math.abs(dy) < threshold || Math.abs(dy) < 1.5f * Math.abs(dx)) return false;
            boolean towardEdge = config.top ? dy < 0 : dy > 0;
            if (towardEdge == collapsed) return false;
            swiped = true;
            listener.onInteraction();
            if (towardEdge) listener.onCollapse();
            else listener.onExpand();
            return true;
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
        }

        private final NavConfig config;
        private final List<LinearLayout> cells = new ArrayList<>();
        private final List<FrameLayout> indicators = new ArrayList<>();
        private final List<GradientDrawable> indicatorShapes = new ArrayList<>();
        private final List<TextView> icons = new ArrayList<>();
        private final List<TextView> labels = new ArrayList<>();
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
            applySelection();
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            setBackground(surface(theme, config));
            setPadding(px(8 * s), px(6 * s), px(8 * s), px(6 * s));
            indicatorShapes.clear();
            for (int i = 0; i < cells.size(); i++) {
                LinearLayout cell = cells.get(i);
                cell.setBackground(pressable(theme, null, px(theme.radiusRow())));
                cell.setMinimumWidth(px(68 * s));
                cell.setPadding(px(6 * s), px(6 * s), px(6 * s), px(6 * s));
                GradientDrawable indicator = new GradientDrawable();
                indicator.setCornerRadius(px(16 * s));
                indicatorShapes.add(indicator);
                FrameLayout frame = indicators.get(i);
                frame.setBackground(indicator);
                frame.setLayoutParams(new LinearLayout.LayoutParams(px(56 * s), px(32 * s)));
                icons.get(i).setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24 * s));
                TextView label = labels.get(i);
                theme.styleText(label, 12 * s, 500, "onSurfaceVariant");
                LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                spacing.topMargin = px(4 * s);
                label.setLayoutParams(spacing);
            }
            applySelection();
        }

        /**
         * Moves the highlight. Only colors and glyphs change, so a tap costs one redraw and no layout
         * pass. The label weight stays the same for the same reason: a bolder label would be wider.
         */
        private void applySelection() {
            int on = color(theme, "onSecondaryContainer", "onSurface");
            int off = theme.color("onSurfaceVariant");
            int pill = color(theme, "secondaryContainer", "primaryContainer");
            int strong = theme.color("onSurface");
            for (int i = 0; i < cells.size(); i++) {
                NavItem item = config.items.get(i);
                boolean selected = i == active;
                indicatorShapes.get(i).setColor(selected ? pill : Color.TRANSPARENT);
                TextView icon = icons.get(i);
                icon.setText(MdiFont.glyph(selected ? item.iconSelected : item.icon));
                icon.setTextColor(selected ? on : off);
                TextView label = labels.get(i);
                label.setTextColor(selected ? strong : off);
                boolean show = config.labels == NavConfig.Labels.ALWAYS || (config.labels == NavConfig.Labels.ACTIVE && selected);
                int visibility = show ? VISIBLE : GONE;
                if (label.getVisibility() != visibility) label.setVisibility(visibility);
            }
        }

        private int px(float dp) { return theme.px(dp); }
    }

    /**
     * The entries of a menu item on a card: a list of rows, or a grid of tiles for long menus such as
     * cameras. The current page's entry is highlighted.
     */
    static final class Menu extends LinearLayout {
        interface Taps {
            void onChild(int index);
        }

        private final NavConfig config;
        private final NavItem item;
        private final String path;
        private final boolean grid;
        private final int columns;
        /** One per entry, in order. Each holds an icon then a label. */
        private final List<LinearLayout> entries = new ArrayList<>();
        private KsTheme theme;

        Menu(Context context, KsTheme theme, NavConfig config, int itemIndex, String path, Taps taps) {
            super(context);
            this.config = config;
            this.item = config.items.get(itemIndex);
            this.path = path;
            this.theme = theme;
            int count = item.children.size();
            this.grid = config.grid(item);
            this.columns = count <= 4 ? count : count <= 9 ? 3 : 4;
            setOrientation(VERTICAL);
            LinearLayout row = null;
            for (int i = 0; i < count; i++) {
                NavItem child = item.children.get(i);
                LinearLayout entry = new LinearLayout(context);
                entry.setOrientation(grid ? VERTICAL : HORIZONTAL);
                entry.setGravity(grid ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
                entry.setContentDescription(child.label);
                entry.addView(icon(context));
                TextView label = new TextView(context);
                label.setText(child.label);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                if (grid) label.setGravity(Gravity.CENTER);
                entry.addView(label);
                final int index = i;
                entry.setOnClickListener(view -> taps.onChild(index));
                entries.add(entry);
                if (grid) {
                    if (i % columns == 0) {
                        row = new LinearLayout(context);
                        row.setOrientation(HORIZONTAL);
                        addView(row);
                    }
                    row.addView(entry);
                } else {
                    addView(entry);
                }
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
            for (int i = 0; i < entries.size(); i++) {
                NavItem child = item.children.get(i);
                boolean selected = child.matches(path);
                LinearLayout entry = entries.get(i);
                GradientDrawable background = new GradientDrawable();
                background.setCornerRadius(px(theme.radiusRow()));
                background.setColor(selected ? pill : Color.TRANSPARENT);
                entry.setBackground(pressable(theme, background, px(theme.radiusRow())));
                TextView icon = (TextView) entry.getChildAt(0);
                icon.setText(MdiFont.glyph(selected ? child.iconSelected : child.icon));
                icon.setTextColor(selected ? on : theme.color("onSurfaceVariant"));
                TextView label = (TextView) entry.getChildAt(1);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                if (grid) {
                    // Tiles: a large icon over a short label, all the same size so the grid lines up.
                    icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(28 * s));
                    theme.styleText(label, 12.5f * s, 500, "onSurface");
                    labelParams.topMargin = px(6 * s);
                    label.setMaxWidth(px(92 * s));
                    entry.setPadding(px(6 * s), px(12 * s), px(6 * s), px(10 * s));
                    LinearLayout.LayoutParams tile = new LinearLayout.LayoutParams(px(104 * s), LayoutParams.WRAP_CONTENT);
                    tile.setMargins(px(2 * s), px(2 * s), px(2 * s), px(2 * s));
                    entry.setLayoutParams(tile);
                } else {
                    icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22 * s));
                    theme.styleText(label, 15 * s, 500, "onSurface");
                    labelParams.leftMargin = px(14 * s);
                    entry.setPadding(px(14 * s), px(10 * s), px(20 * s), px(10 * s));
                    entry.setMinimumHeight(px(48 * s));
                    entry.setMinimumWidth(px(220 * s));
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                    if (i > 0) rowParams.topMargin = px(2 * s);
                    entry.setLayoutParams(rowParams);
                }
                label.setLayoutParams(labelParams);
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
            float[] radii = config.top ? new float[] {0, 0, 0, 0, r, r, r, r} : new float[] {r, r, r, r, 0, 0, 0, 0};
            setBackground(pressable(theme, shape, radii));
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
