// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where a navbar item goes. Parsed from the compact target syntax used in settings:
 * a dashboard path ({@code /lovelace/0}), a URL, or a native KS action keyword.
 */
final class NavTarget {
    enum Kind { PAGE, URL, CAMERA, MUSIC, NOW_PLAYING, SCREENSAVER, APPS, RELOAD, HOME }

    /** A KS host command and its arguments. */
    static final class Command {
        final String name;
        final Map<String, Object> arguments;

        Command(String name, Map<String, Object> arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }

    /** The exact path syntax haNavigate accepts, after trailing slashes are removed. */
    static final Pattern NAVIGABLE = Pattern.compile("^/?[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*$");
    private static final Pattern VIEW_ID = Pattern.compile("^[0-9a-fA-F]{32}$");

    final Kind kind;
    /** The path for PAGE, the URL for URL and the view ID for CAMERA. Empty otherwise. */
    final String value;

    private NavTarget(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    static NavTarget parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("target is empty");
        if (text.startsWith("/")) return new NavTarget(Kind.PAGE, text);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (text.contains("@")) throw new IllegalArgumentException("URLs cannot contain credentials");
            return new NavTarget(Kind.URL, text);
        }
        // A KS camera view is named by its ID, the 32 hex characters KS gives every view.
        if (VIEW_ID.matcher(text).matches()) return new NavTarget(Kind.CAMERA, text);
        switch (lower) {
            case "music": return new NavTarget(Kind.MUSIC, "");
            case "nowplaying": case "now-playing": return new NavTarget(Kind.NOW_PLAYING, "");
            case "screensaver": return new NavTarget(Kind.SCREENSAVER, "");
            case "apps": return new NavTarget(Kind.APPS, "");
            case "reload": return new NavTarget(Kind.RELOAD, "");
            case "home": return new NavTarget(Kind.HOME, "");
            default:
                throw new IllegalArgumentException("unknown target '" + raw.trim()
                    + "'. Use a /dashboard/view path, a URL, a camera view ID, music, nowplaying, screensaver, apps, reload or home");
        }
    }

    /** The normalized dashboard path this target highlights, or null when it never matches a page. */
    String matchPath() {
        return kind == Kind.PAGE ? Paths.normalize(value) : null;
    }

    /**
     * The KS command that performs this target. {@code origin} is the scheme, host and port of the
     * page the kiosk is showing, used when a path carries a query string haNavigate cannot take.
     */
    Command command(String origin) {
        switch (kind) {
            case PAGE: {
                String path = Paths.stripTrailingSlashes(value);
                if (NAVIGABLE.matcher(path).matches()) return new Command("haNavigate", args("path", path));
                if (origin == null) throw new IllegalStateException("the dashboard address is not known yet");
                return new Command("loadUrl", args("url", origin + value));
            }
            case URL: return new Command("loadUrl", args("url", value));
            case CAMERA: return new Command("showCameraView", args("viewId", value));
            case MUSIC: return new Command("showMusicAssistant", Collections.<String, Object>emptyMap());
            case NOW_PLAYING: return new Command("showNowPlaying", Collections.<String, Object>emptyMap());
            case SCREENSAVER: return new Command("startScreensaver", Collections.<String, Object>emptyMap());
            case APPS: return new Command("showAppLauncher", Collections.<String, Object>emptyMap());
            case RELOAD: return new Command("reload", Collections.<String, Object>emptyMap());
            case HOME: return new Command("loadStartUrl", Collections.<String, Object>emptyMap());
            default: throw new IllegalStateException("Unhandled target " + kind);
        }
    }

    /** The fallback when haNavigate reports it could not move the page. */
    Command fallback(String origin) {
        if (kind != Kind.PAGE || origin == null) return null;
        return new Command("loadUrl", args("url", origin + value));
    }

    private static Map<String, Object> args(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
