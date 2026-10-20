// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The Windows Vista pictures bundled under {@code assets/vista/}. The navbar has no network access, so
 * the pictures ship in the package. An item picks one in its Vista icon setting, or Automatic matches
 * one to its Material icon and label. An item with no match draws its Material icon in white.
 */
final class VistaIcons {
    private VistaIcons() {}

    static final String AUTOMATIC = "Automatic";

    /** Each Vista icon choice, as its asset key: lower case, spaces as dashes. */
    static final String[] KEYS = {
        "home", "windows", "computer", "display", "camera", "camcorder", "music", "media-player", "pictures",
        "calendar", "clock", "globe", "network", "shield", "lock", "settings", "people", "search", "microphone",
        "speaker", "phone", "power", "gauge", "programs", "folder", "favorites", "notes", "mail", "refresh",
        "garden", "games",
    };

    /**
     * Words in a Material icon name or a label, and the picture each suggests. Checked in order, so the
     * more specific words come first: {@code mdi:home-automation} is about the house, but
     * {@code mdi:microphone-message} is about voice.
     */
    private static final String[][] RULES = {
        {"microphone", "microphone", "voice", "assist", "satellite", "mic"},
        {"camcorder", "cctv", "cameras", "doorbell", "video"},
        {"camera", "camera", "webcam"},
        {"media-player", "now-playing", "play", "media", "movie", "film", "television-play"},
        {"music", "music", "spotify", "playlist", "album"},
        {"speaker", "speaker", "volume", "sonos", "audio"},
        {"pictures", "image", "photo", "picture", "gallery"},
        {"calendar", "calendar", "agenda", "schedule"},
        {"shield", "shield", "security", "alarm-light", "protect", "siren", "alarm-panel"},
        {"clock", "clock", "timer", "alarm", "history"},
        {"globe", "web", "earth", "weather", "globe", "internet", "forecast"},
        {"network", "network", "wifi", "router", "lan", "server"},
        {"lock", "lock", "door", "key", "garage"},
        {"settings", "cog", "settings", "tune", "setup", "wrench"},
        {"people", "account", "people", "family", "person", "user", "kids", "baby"},
        {"search", "magnify", "search", "find"},
        {"phone", "phone", "intercom", "call"},
        {"power", "power", "flash", "lightning", "battery", "solar", "energy", "plug", "ev-station"},
        {"gauge", "chart", "gauge", "speedometer", "stats", "statistics", "heart-pulse", "status"},
        {"programs", "apps", "view-grid", "launcher", "application"},
        {"display", "monitor", "television", "tv", "screensaver", "screen"},
        {"computer", "desktop", "laptop", "computer", "office", "study"},
        {"folder", "folder", "files", "file"},
        {"favorites", "star", "heart", "favorite", "scene"},
        {"notes", "note", "notes", "todo", "list", "shopping", "clipboard"},
        {"mail", "mail", "email", "post", "envelope", "mailbox"},
        {"refresh", "refresh", "reload", "sync", "update"},
        {"garden", "tree", "flower", "garden", "grass", "pine", "yard", "outdoor", "patio", "pool", "sprinkler"},
        {"games", "gamepad", "controller", "game", "games", "playroom"},
        {"windows", "start", "windows", "microsoft"},
        {"home", "home", "house", "sofa", "living", "lounge", "lovelace", "overview"},
    };

    /**
     * The asset key for an item. {@code choice} is the item's Vista icon setting: a named picture, or
     * Automatic or empty to match the Material icon and then the label. Null when nothing matches.
     */
    static String resolve(String choice, String materialIcon, String label) {
        String picked = choice == null ? "" : choice.trim();
        if (!picked.isEmpty() && !AUTOMATIC.equals(picked)) {
            String key = picked.toLowerCase(Locale.ROOT).replace(' ', '-');
            for (String known : KEYS) if (known.equals(key)) return key;
        }
        String fromIcon = match(materialIcon == null ? "" : materialIcon.replace("mdi:", ""));
        return fromIcon != null ? fromIcon : match(label);
    }

    /** The first rule with a word that is a whole dash- or space-separated part of {@code text}. */
    static String match(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String[] words = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        String joined = "-" + String.join("-", words) + "-";
        for (String[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) {
                if (joined.contains("-" + rule[i] + "-")) return rule[0];
            }
        }
        return null;
    }

    private static volatile PluginHost host;
    private static final Map<String, Bitmap> CACHE = new ConcurrentHashMap<>();
    /** Keys that failed to load, so a missing file is read once rather than on every redraw. */
    private static final Map<String, Boolean> MISSING = new ConcurrentHashMap<>();

    /** The session the pictures are read through. Null when the plugin stops. */
    static void attach(PluginHost next) { host = next; }

    /** The decoded picture, or null when it cannot be read, so the caller draws the Material icon. */
    static Bitmap bitmap(String key) {
        if (key == null) return null;
        Bitmap cached = CACHE.get(key);
        if (cached != null) return cached;
        PluginHost current = host;
        if (current == null || MISSING.containsKey(key)) return null;
        try (InputStream in = current.openAsset("vista/" + key + ".png")) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) {
                MISSING.put(key, Boolean.TRUE);
                return null;
            }
            CACHE.put(key, bitmap);
            return bitmap;
        } catch (Exception | LinkageError unavailable) {
            // An older Kiosk Satellite without plugin assets, or a damaged package.
            MISSING.put(key, Boolean.TRUE);
            return null;
        }
    }
}
