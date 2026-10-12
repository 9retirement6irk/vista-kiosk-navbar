// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import android.content.Context;
import android.graphics.Typeface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * The bundled Material Design Icons font. The first use writes it to the app's cache directory,
 * where later sessions reuse it, and loads the name table once per process. Failures never throw:
 * without the font the bar shows a plain dot for each icon instead.
 */
final class MdiFont {
    private static final String PREFIX = "ksnavbar-mdi-";
    private static final String FALLBACK = "help-circle-outline";
    private static final String NO_FONT_GLYPH = "\u2022";
    private static Typeface typeface;
    private static boolean failed;
    private static Map<String, Integer> codes;

    private MdiFont() {}

    static synchronized Typeface typeface(Context context) {
        if (typeface != null) return typeface;
        if (failed) return Typeface.DEFAULT;
        File dir = context.getCacheDir();
        File font = new File(dir, PREFIX + MdiData.FONT_ID + ".ttf");
        for (int attempt = 0; attempt < 2 && typeface == null; attempt++) {
            try {
                if (attempt > 0 || !font.isFile() || font.length() == 0) write(dir, font);
                typeface = Typeface.createFromFile(font);
            } catch (RuntimeException | IOException error) {
                // A corrupt or partial cached file: remove it and extract again once.
                font.delete();
            }
        }
        if (typeface == null) {
            failed = true;
            return Typeface.DEFAULT;
        }
        return typeface;
    }

    /** The glyph for an icon such as {@code mdi:home}. Unknown and non-MDI icons get a question mark icon. */
    static synchronized String glyph(String icon) {
        if (failed) return NO_FONT_GLYPH;
        if (codes == null) {
            try {
                codes = MdiCodec.names(MdiCodec.decode(MdiData.NAMES));
            } catch (RuntimeException error) {
                codes = Collections.emptyMap();
            }
        }
        String name = MdiCodec.bareName(icon);
        Integer code = name == null ? null : codes.get(name);
        if (code == null) code = codes.get(FALLBACK);
        return code == null ? NO_FONT_GLYPH : new String(Character.toChars(code));
    }

    private static void write(File dir, File font) throws IOException {
        File partial = File.createTempFile(PREFIX + MdiData.FONT_ID, ".tmp", dir);
        try {
            try (FileOutputStream out = new FileOutputStream(partial)) {
                out.write(MdiCodec.decode(MdiData.FONT));
                out.getFD().sync();
            }
            font.delete();
            if (!partial.renameTo(font)) throw new IOException("Could not store the icon font");
        } finally {
            partial.delete();
        }
        File[] old = dir.listFiles((parent, name) -> name.startsWith(PREFIX) && !name.startsWith(PREFIX + MdiData.FONT_ID));
        if (old != null) for (File stale : old) stale.delete();
    }
}
