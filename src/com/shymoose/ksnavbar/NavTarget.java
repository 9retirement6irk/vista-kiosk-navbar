// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where a navbar item goes: a dashboard view, a camera view, a kiosk action, a web address or one of
 * the navbar's gesture triggers.
 */
final class NavTarget {
    enum Kind { PAGE, URL, CAMERA, MUSIC, NOW_PLAYING, SCREENSAVER, APPS, RELOAD, HOME, TRIGGER }

    /** The Kiosk action choices in settings, in the order they are offered. */
    static final String[] ACTIONS = {"Music", "Now playing", "Screensaver", "App launcher", "Reload", "Start page"};

    /**
     * The gesture trigger choices in settings, in order. Each fires the manifest trigger
     * {@code button1} to {@code button8}, which the person maps under Gestures > Plugin trigger to any
     * gesture action, such as Start a voice command or Run a plugin action. Kiosk Satellite does not
     * let plugins run those actions directly, so the trigger is the way in. The IDs and titles must
     * never change: a gesture saved for a trigger stops firing if its ID goes away.
     */
    static final String[] TRIGGERS = {
        "Navbar button 1", "Navbar button 2", "Navbar button 3", "Navbar button 4",
        "Navbar button 5", "Navbar button 6", "Navbar button 7", "Navbar button 8",
    };

    /** A KS host command and its arguments. */
    static final class Command {
        final String name;
        final Map<String, Object> arguments;

        Command(String name, Map<String, Object> arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }

    final Kind kind;
    /** The path for PAGE, the URL for URL and the view ID for CAMERA. Empty otherwise. */
    final String value;

    private NavTarget(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    /** A dashboard view, as the dashboard picker stores it ({@code lovelace/0}), with or without a leading slash. */
    static NavTarget page(String path) {
        String text = path == null ? "" : path.trim();
        while (text.startsWith("/")) text = text.substring(1);
        text = Paths.stripTrailingSlashes(text);
        if (text.isEmpty()) throw new IllegalArgumentException("pick a page");
        if (text.contains("://")) throw new IllegalArgumentException("a page is a dashboard view, not a web address");
        return new NavTarget(Kind.PAGE, text);
    }

    /** A camera view ID, as the camera view picker stores it. */
    static NavTarget camera(String viewId) {
        String id = viewId == null ? "" : viewId.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("pick a camera view");
        return new NavTarget(Kind.CAMERA, id);
    }

    static NavTarget url(String address) {
        String text = address == null ? "" : address.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("a web address starts with https:// or http://");
        }
        if (text.contains("@")) throw new IllegalArgumentException("web addresses cannot contain credentials");
        return new NavTarget(Kind.URL, text);
    }

    /** One of {@link #ACTIONS}. */
    static NavTarget action(String name) {
        String text = name == null ? "" : name.trim();
        switch (text) {
            case "Music": return new NavTarget(Kind.MUSIC, "");
            case "Now playing": return new NavTarget(Kind.NOW_PLAYING, "");
            case "Screensaver": return new NavTarget(Kind.SCREENSAVER, "");
            case "App launcher": return new NavTarget(Kind.APPS, "");
            case "Reload": return new NavTarget(Kind.RELOAD, "");
            case "Start page": return new NavTarget(Kind.HOME, "");
            default: throw new IllegalArgumentException("pick a kiosk action");
        }
    }

    /** One of {@link #TRIGGERS}. The value is the manifest trigger ID. */
    static NavTarget trigger(String name) {
        String text = name == null ? "" : name.trim();
        for (int i = 0; i < TRIGGERS.length; i++) {
            if (TRIGGERS[i].equals(text)) return new NavTarget(Kind.TRIGGER, "button" + (i + 1));
        }
        throw new IllegalArgumentException("pick a gesture trigger");
    }

    /** The gesture trigger's title, as Gestures > Plugin trigger lists it. Only for TRIGGER. */
    String triggerTitle() {
        return TRIGGERS[Integer.parseInt(value.substring("button".length())) - 1];
    }

    /** The normalized dashboard path this target highlights, or null when it never matches a page. */
    String matchPath() {
        return kind == Kind.PAGE ? Paths.normalize(value) : null;
    }

    /** The KS command that performs this target. A trigger is fired with fireTrigger instead. */
    Command command() {
        switch (kind) {
            // haNavigate takes a query string and fragment too, and stays in the page.
            case PAGE: return new Command("haNavigate", args("path", value));
            case URL: return new Command("loadUrl", args("url", value));
            case CAMERA: return new Command("showCameraView", args("viewId", value));
            case MUSIC: return new Command("showMusicAssistant", Collections.<String, Object>emptyMap());
            case NOW_PLAYING: return new Command("showNowPlaying", Collections.<String, Object>emptyMap());
            case SCREENSAVER: return new Command("startScreensaver", Collections.<String, Object>emptyMap());
            case APPS: return new Command("showAppLauncher", Collections.<String, Object>emptyMap());
            case RELOAD: return new Command("reload", Collections.<String, Object>emptyMap());
            case HOME: return new Command("loadStartUrl", Collections.<String, Object>emptyMap());
            case TRIGGER: throw new IllegalStateException("A gesture trigger has no KS command");
            default: throw new IllegalStateException("Unhandled target " + kind);
        }
    }

    private static Map<String, Object> args(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}