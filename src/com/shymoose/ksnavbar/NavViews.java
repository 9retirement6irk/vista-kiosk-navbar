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
import android.view.ViewGroup;
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
        /** A menu item was tapped. {@code offsetPx} is its distance from the bar's center, {@code barHeightDp} the bar's height. */
        void onMenu(int index, float offsetPx, int barHeightDp);
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
        return surface(theme, config.opacity);
    }

    static GradientDrawable surface(KsTheme theme, float opacity) {
        GradientDrawable card = theme.card();
        card.setAlpha(Math.round(255 * opacity));
        return card;
    }

    /** Menus cover more of the dashboard than the bar, so they stay nearly opaque to keep entries readable. */
    static final float MENU_MIN_OPACITY = 0.95f;

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
     * Hides an overlay's content while its size is changing, so a frame drawn at the wrong size is
     * never shown. KS draws a plugin overlay into a texture that it resizes only after measuring the
     * view. Whatever the texture held is scaled to the new size until the view redraws, which shows
     * as a stretched copy of the previous state. The gate keeps the content transparent from the
     * moment its size will change until the host surface has exactly the measured size, then
     * reveals it. A timeout reveals it anyway, so a size that never arrives cannot hide the bar.
     */
    static final class SizeGate {
        private static final long TIMEOUT_MS = 700;

        private final ViewGroup owner;
        private boolean waiting;
        private final Runnable timeout = this::open;
        private final View.OnLayoutChangeListener hostListener = (view, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) invalidateTree(owner());
            check();
        };

        SizeGate(ViewGroup owner) { this.owner = owner; }

        private ViewGroup owner() { return owner; }

        private View host() {
            return owner.getParent() instanceof View ? (View) owner.getParent() : null;
        }

        void attach() {
            View host = host();
            if (host != null) host.addOnLayoutChangeListener(hostListener);
        }

        void detach() {
            View host = host();
            if (host != null) host.removeOnLayoutChangeListener(hostListener);
            owner.removeCallbacks(timeout);
        }

        /** The owner's size is about to change. Hide it until the host matches. */
        void close() {
            waiting = true;
            owner.setAlpha(0f);
            owner.removeCallbacks(timeout);
            owner.postDelayed(timeout, TIMEOUT_MS);
        }

        /** Reveal once the host surface has the owner's measured size. */
        void check() {
            if (!waiting) return;
            View host = host();
            int width = owner.getMeasuredWidth();
            int height = owner.getMeasuredHeight();
            if (host != null && width > 1 && height > 1 && host.getWidth() == width && host.getHeight() == height) open();
        }

        private void open() {
            waiting = false;
            owner.removeCallbacks(timeout);
            owner.setAlpha(1f);
            invalidateTree(owner);
        }
    }

    static void invalidateTree(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) invalidateTree(group.getChildAt(i));
        }
    }

    /**
     * The bar and the tab it collapses into, edge last. Menus live in their own overlay, so opening
     * one never resizes this view. Every method runs on the main thread.
     */
    static final class Root extends LinearLayout {
        private final NavConfig config;
        private final Listener listener;
        private final Bar bar;
        private final Tab tab;
        private final SizeGate gate = new SizeGate(this);
        private KsTheme theme;
        private String path;
        private int active;
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
            bar = new Bar(context, theme, config, active, this::tapItem);
            tab = new Tab(context, theme, config, active, view -> {
                listener.onInteraction();
                listener.onExpand();
            });
            addView(bar);
            addView(tab);
            this.collapsed = collapsed && config.collapse != NavConfig.Collapse.OFF;
            bar.setVisibility(this.collapsed ? GONE : VISIBLE);
            tab.setVisibility(this.collapsed ? VISIBLE : GONE);
            restyle(theme);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            gate.attach();
            // The first frame is drawn before KS knows the size.
            gate.close();
        }

        @Override protected void onDetachedFromWindow() {
            gate.detach();
            removeCallbacks(settle);
            super.onDetachedFromWindow();
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            gate.check();
        }

        void setPath(String next) {
            if (java.util.Objects.equals(next, path)) return;
            path = next;
            removeCallbacks(settle);
            showActive(config.activeIndex(next));
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
        void preview(int index) {
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
        private long downAt;
        private long drawDelay = -1;
        private boolean measureDraw;

        /** Measures how late touches arrive. The event time is when the finger actually moved. */
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            long now = SystemClock.uptimeMillis();
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downDelay = now - event.getEventTime();
                downAt = now;
                measureDraw = true;
            } else if (action == MotionEvent.ACTION_UP && downDelay >= 0) {
                long up = now - event.getEventTime();
                long down = downDelay;
                downDelay = -1;
                try { listener.onLatency(down, up, drawDelay); } catch (RuntimeException ignored) {}
            }
            return super.dispatchTouchEvent(event);
        }

        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            super.dispatchDraw(canvas);
            if (measureDraw) {
                measureDraw = false;
                drawDelay = SystemClock.uptimeMillis() - downAt;
            }
        }

        void setCollapsed(boolean value) {
            boolean next = value && config.collapse != NavConfig.Collapse.OFF;
            if (next == collapsed) return;
            collapsed = next;
            // Swapping the bar for the tab changes the overlay's size.
            gate.close();
            bar.setVisibility(collapsed ? GONE : VISIBLE);
            tab.setVisibility(collapsed ? VISIBLE : GONE);
        }

        boolean isCollapsed() { return collapsed; }

        private void tapItem(int index) {
            listener.onInteraction();
            NavItem item = config.items.get(index);
            if (item.type == NavItem.Type.MENU) {
                // Where the item sits relative to the center, where both overlays are centered.
                float offset = bar.getLeft() + bar.itemCenter(index) - getWidth() / 2f;
                float density = getResources().getDisplayMetrics().density;
                listener.onMenu(index, offset, Math.round(getHeight() / density));
                return;
            }
            if (item.type == NavItem.Type.PAGE) preview(index);
            listener.onTarget(item.target);
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
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE && checkSwipe(event)) return true;
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
            int edge = theme.px(config.inset);
            bar.restyle(theme);
            tab.restyle(theme);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            // Insets are inside the view: the overlay itself sits flush so the tab can touch the edge.
            barParams.leftMargin = edge;
            barParams.rightMargin = edge;
            if (config.top) barParams.topMargin = edge;
            else barParams.bottomMargin = edge;
            bar.setLayoutParams(barParams);
            tab.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * A menu's own overlay: the full width of the kiosk above (or below) the bar, transparent except
     * for the menu card, which sits over its item. Taps on the transparent space close the menu.
     */
    static final class MenuPanel extends FrameLayout {
        private final Menu menu;
        private final float offset;
        private final SizeGate gate = new SizeGate(this);

        MenuPanel(Context context, KsTheme theme, NavConfig config, int itemIndex, String path, float offset,
                  Menu.Taps taps, Runnable close) {
            super(context);
            this.offset = offset;
            menu = new Menu(context, theme, config, itemIndex, path, taps);
            addView(menu, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                config.top ? Gravity.TOP : Gravity.BOTTOM));
            setOnClickListener(view -> close.run());
        }

        void restyle(KsTheme theme) { menu.restyle(theme); }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            gate.attach();
            gate.close();
        }

        @Override protected void onDetachedFromWindow() {
            gate.detach();
            super.onDetachedFromWindow();
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            // Both overlays are centered, so the item's offset from the center places the card.
            float center = getWidth() / 2f + offset;
            float x = Math.max(0, Math.min(getWidth() - menu.getWidth(), center - menu.getWidth() / 2f));
            menu.setTranslationX(x);
            gate.check();
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
            setBackground(surface(theme, Math.max(config.opacity, MENU_MIN_OPACITY)));
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
