// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Windows Vista's Aero look, drawn in code so it scales to any size and density: the black glass of
 * the taskbar with its two-tone gloss, a lit taskbar button, and the Start menu's white pane in a
 * glass frame with Vista's blue selection.
 */
final class VistaGlass extends Drawable {
    enum Kind {
        /** The taskbar: dark glass, lighter on the half away from the edge, with a bright rim. */
        BAR,
        /** The active item: a lit glass button with a blue glow along its foot. */
        BUTTON,
        /** The item whose menu is open: the same button, fainter. */
        BUTTON_OPEN,
        /** A button under the finger. */
        BUTTON_PRESSED,
        /** A menu: the Start menu's white pane in a dark glass frame. */
        FRAME,
        /** A menu entry for the current page: Vista's selected row. */
        ROW,
        /** A menu entry under the finger. */
        ROW_PRESSED,
    }

    private final Kind kind;
    private final float radius;
    private final float frame;
    private final float hairline;
    /** Background opacity, 0 to 1. Only the glass of the bar and the frame follow it. */
    private final float opacity;
    /** Whether the gloss splits left and right, for a rail, rather than top and bottom. */
    private final boolean sideways;
    private float[] radii;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();

    /**
     * {@code radius} and {@code frame} are in pixels. {@code frame} is the width of a menu's glass
     * border around its white pane and is ignored by the other kinds.
     */
    VistaGlass(Kind kind, float radius, float frame, float density, float opacity, boolean sideways) {
        this.kind = kind;
        this.radius = radius;
        this.frame = frame;
        this.hairline = Math.max(1f, density);
        this.opacity = Math.max(0f, Math.min(1f, opacity));
        this.sideways = sideways;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(hairline);
    }

    /** Per-corner radii, as GradientDrawable takes them, for a shape flush with the screen edge. */
    VistaGlass corners(float[] next) {
        radii = next;
        return this;
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        box.set(b);
        switch (kind) {
            case BAR: drawBar(canvas, box); break;
            case BUTTON: drawButton(canvas, box, 1f, false); break;
            case BUTTON_OPEN: drawButton(canvas, box, 0.55f, false); break;
            case BUTTON_PRESSED: drawButton(canvas, box, 0.8f, true); break;
            case FRAME: drawFrame(canvas, box); break;
            case ROW: drawRow(canvas, box, false); break;
            case ROW_PRESSED: drawRow(canvas, box, true); break;
        }
    }

    private int alpha(float a) { return Math.round(255 * Math.max(0f, Math.min(1f, a))); }

    private void round(Path path, RectF r, float rad) {
        path.reset();
        if (radii != null) path.addRoundRect(r, radii, Path.Direction.CW);
        else path.addRoundRect(r, rad, rad, Path.Direction.CW);
    }

    /** Two gradients that meet with a hard step in the middle, the gloss of every Aero surface. */
    private void gloss(Canvas canvas, RectF r, int[] first, int[] second) {
        float mid = sideways ? r.centerX() : r.centerY();
        RectF half = new RectF(r);
        if (sideways) half.right = mid; else half.bottom = mid;
        fill.setShader(sideways
            ? new LinearGradient(r.left, 0, mid, 0, first[0], first[1], Shader.TileMode.CLAMP)
            : new LinearGradient(0, r.top, 0, mid, first[0], first[1], Shader.TileMode.CLAMP));
        canvas.drawRect(half, fill);
        half.set(r);
        if (sideways) half.left = mid; else half.top = mid;
        fill.setShader(sideways
            ? new LinearGradient(mid, 0, r.right, 0, second[0], second[1], Shader.TileMode.CLAMP)
            : new LinearGradient(0, mid, 0, r.bottom, second[0], second[1], Shader.TileMode.CLAMP));
        canvas.drawRect(half, fill);
        fill.setShader(null);
    }

    private void drawBar(Canvas canvas, RectF r) {
        float a = opacity;
        int save = canvas.save();
        round(clip, r, radius);
        canvas.clipPath(clip);
        gloss(canvas, r,
            new int[] {Color.argb(alpha(a * 0.88f), 86, 99, 114), Color.argb(alpha(a * 0.9f), 44, 53, 64)},
            new int[] {Color.argb(alpha(a * 0.94f), 10, 14, 19), Color.argb(alpha(a * 0.92f), 24, 33, 42)});
        // The faint teal sheen Aero shows across the lower half of the taskbar.
        float cx = r.centerX();
        float cy = sideways ? r.centerY() : r.bottom;
        float spread = Math.max(r.width(), r.height()) * 0.6f;
        fill.setShader(new RadialGradient(cx, cy, spread, Color.argb(alpha(a * 0.22f), 90, 170, 210), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(r, fill);
        fill.setShader(null);
        canvas.restoreToCount(save);
        // A white inner rim, brightest along the glossy side, inside a black outline.
        RectF inner = new RectF(r.left + hairline * 1.5f, r.top + hairline * 1.5f, r.right - hairline * 1.5f, r.bottom - hairline * 1.5f);
        round(clip, inner, Math.max(0, radius - hairline));
        line.setShader(sideways
            ? new LinearGradient(inner.left, 0, inner.right, 0, Color.argb(alpha(0.45f), 255, 255, 255), Color.argb(alpha(0.12f), 255, 255, 255), Shader.TileMode.CLAMP)
            : new LinearGradient(0, inner.top, 0, inner.bottom, Color.argb(alpha(0.45f), 255, 255, 255), Color.argb(alpha(0.12f), 255, 255, 255), Shader.TileMode.CLAMP));
        canvas.drawPath(clip, line);
        line.setShader(null);
        RectF outer = new RectF(r.left + hairline / 2, r.top + hairline / 2, r.right - hairline / 2, r.bottom - hairline / 2);
        round(clip, outer, radius);
        line.setColor(Color.argb(alpha(0.75f), 0, 0, 0));
        canvas.drawPath(clip, line);
    }

    private void drawButton(Canvas canvas, RectF r, float strength, boolean pressed) {
        int save = canvas.save();
        round(clip, r, radius);
        canvas.clipPath(clip);
        if (pressed) {
            gloss(canvas, r,
                new int[] {Color.argb(alpha(0.30f), 20, 40, 60), Color.argb(alpha(0.36f), 10, 25, 40)},
                new int[] {Color.argb(alpha(0.42f), 0, 10, 20), Color.argb(alpha(0.30f), 20, 60, 90)});
        } else {
            gloss(canvas, r,
                new int[] {Color.argb(alpha(0.34f * strength), 255, 255, 255), Color.argb(alpha(0.20f * strength), 255, 255, 255)},
                new int[] {Color.argb(alpha(0.06f * strength), 255, 255, 255), Color.argb(alpha(0.14f * strength), 255, 255, 255)});
        }
        // Vista's blue glow rising from the foot of a lit button.
        float cx = r.centerX();
        float cy = sideways ? r.centerY() : r.bottom;
        float rx = sideways ? r.height() * 0.7f : r.width() * 0.62f;
        int glow = pressed ? Color.argb(alpha(0.75f), 90, 190, 255) : Color.argb(alpha(0.85f * strength), 110, 200, 255);
        fill.setShader(new RadialGradient(cx, cy, Math.max(1, rx), glow, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(r, fill);
        fill.setShader(null);
        canvas.restoreToCount(save);
        RectF edge = new RectF(r.left + hairline / 2, r.top + hairline / 2, r.right - hairline / 2, r.bottom - hairline / 2);
        round(clip, edge, radius);
        line.setColor(Color.argb(alpha(0.55f * strength), 255, 255, 255));
        canvas.drawPath(clip, line);
        RectF inner = new RectF(edge.left + hairline, edge.top + hairline, edge.right - hairline, edge.bottom - hairline);
        round(clip, inner, Math.max(0, radius - hairline));
        line.setColor(Color.argb(alpha(0.35f * strength), 0, 0, 0));
        canvas.drawPath(clip, line);
    }

    private void drawFrame(Canvas canvas, RectF r) {
        drawBar(canvas, r);
        // The white pane, a little transparent like the Start menu's, with a soft blue-grey border.
        RectF pane = new RectF(r.left + frame, r.top + frame, r.right - frame, r.bottom - frame);
        float paneRadius = Math.max(hairline * 2, radius - frame);
        clip.reset();
        clip.addRoundRect(pane, paneRadius, paneRadius, Path.Direction.CW);
        fill.setShader(new LinearGradient(0, pane.top, 0, pane.bottom, Color.argb(250, 255, 255, 255), Color.argb(244, 241, 245, 251), Shader.TileMode.CLAMP));
        canvas.drawPath(clip, fill);
        fill.setShader(null);
        line.setColor(Color.argb(255, 130, 150, 175));
        canvas.drawPath(clip, line);
    }

    private void drawRow(Canvas canvas, RectF r, boolean pressed) {
        RectF edge = new RectF(r.left + hairline / 2, r.top + hairline / 2, r.right - hairline / 2, r.bottom - hairline / 2);
        clip.reset();
        clip.addRoundRect(edge, radius, radius, Path.Direction.CW);
        int top = pressed ? Color.rgb(0xc1, 0xdb, 0xfc) : Color.rgb(0xdc, 0xeb, 0xfc);
        int bottom = pressed ? Color.rgb(0xa8, 0xcb, 0xf6) : Color.rgb(0xc1, 0xdb, 0xfc);
        fill.setShader(new LinearGradient(0, edge.top, 0, edge.bottom, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawPath(clip, fill);
        fill.setShader(null);
        line.setColor(pressed ? Color.rgb(0x5f, 0x8c, 0xc4) : Color.rgb(0x7d, 0xa2, 0xce));
        canvas.drawPath(clip, line);
        // The white inner line that makes Vista's selection look raised.
        RectF inner = new RectF(edge.left + hairline, edge.top + hairline, edge.right - hairline, edge.bottom - hairline);
        clip.reset();
        clip.addRoundRect(inner, Math.max(0, radius - hairline), Math.max(0, radius - hairline), Path.Direction.CW);
        line.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawPath(clip, line);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
