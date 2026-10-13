// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** JVM tests for everything that does not draw. Run by tools/test.py with -ea. */
public final class NavbarTest {
    private static int passed;

    public static void main(String[] args) {
        paths();
        targets();
        commands();
        menus();
        config();
        icons();
        System.out.println("NavbarTest: " + passed + " checks passed");
    }

    private static void paths() {
        eq("/wall-tablet", Paths.normalize("/wall-tablet/0"));
        eq("/wall-tablet", Paths.normalize("/wall-tablet/"));
        eq("/wall-tablet/kitchen", Paths.normalize("/wall-tablet/kitchen?edit=1#x"));
        eq("/", Paths.normalize(""));
        eq("/a/b", Paths.normalize("a/b"));
        eq("/a/b/0", Paths.normalize("/a/b/0"));
        check(Paths.within("/bedroom-wall-tablet/weather", "/bedroom-wall-tablet"));
        check(Paths.within("/bedroom-wall-tablet", "/bedroom-wall-tablet/"));
        check(!Paths.within("/bedroom-wall-tablet-2/x", "/bedroom-wall-tablet"));
        check(!Paths.within("/wall-tablet/0", "/bedroom-wall-tablet"));
        eq("https://ha.example:8123", Paths.origin("https://ha.example:8123/dash/view"));
        eq("http://127.0.0.1:18123", Paths.origin("http://127.0.0.1:18123/dash"));
        eq("https://ha.example", Paths.origin("https://ha.example/x"));
        check(Paths.origin("javascript:alert(1)") == null);
        check(Paths.origin(null) == null);
    }

    private static void targets() {
        eq(NavTarget.Kind.PAGE, NavTarget.parse(" /wall-tablet/0 ").kind);
        eq(NavTarget.Kind.URL, NavTarget.parse("https://example.com/x").kind);
        eq(NavTarget.Kind.URL, NavTarget.parse("url:https://example.com/x").kind);
        eq("front-door", NavTarget.parse("camera:front-door").value);
        eq(NavTarget.Kind.CAMERA, NavTarget.parse("Camera: driveway").kind);
        eq(NavTarget.Kind.MUSIC, NavTarget.parse("music").kind);
        eq(NavTarget.Kind.NOW_PLAYING, NavTarget.parse("NowPlaying").kind);
        eq(NavTarget.Kind.NOW_PLAYING, NavTarget.parse("now-playing").kind);
        eq(NavTarget.Kind.SCREENSAVER, NavTarget.parse("screensaver").kind);
        eq(NavTarget.Kind.APPS, NavTarget.parse("apps").kind);
        eq(NavTarget.Kind.RELOAD, NavTarget.parse("reload").kind);
        eq(NavTarget.Kind.HOME, NavTarget.parse("home").kind);
        rejects("");
        rejects("   ");
        rejects("camera:");
        rejects("wall-tablet");
        rejects("javascript:alert(1)");
        rejects("https://user:pw@example.com");
        eq("/wall-tablet", NavTarget.parse("/wall-tablet/0").matchPath());
        check(NavTarget.parse("music").matchPath() == null);
    }

    private static void commands() {
        NavTarget.Command nav = NavTarget.parse("/wall-tablet/kitchen/").command(null);
        eq("haNavigate", nav.name);
        eq("/wall-tablet/kitchen", nav.arguments.get("path"));
        NavTarget query = NavTarget.parse("/bedroom-wall-tablet/cameras/?advanced-camera-card-action.camera_select=garage");
        NavTarget.Command load = query.command("https://ha.example:8123");
        eq("loadUrl", load.name);
        eq("https://ha.example:8123/bedroom-wall-tablet/cameras/?advanced-camera-card-action.camera_select=garage", load.arguments.get("url"));
        try {
            query.command(null);
            throw new AssertionError("query paths need the origin");
        } catch (IllegalStateException expected) { passed++; }
        eq("showCameraView", NavTarget.parse("camera:front").command(null).name);
        eq("front", NavTarget.parse("camera:front").command(null).arguments.get("viewId"));
        eq("showMusicAssistant", NavTarget.parse("music").command(null).name);
        eq("showNowPlaying", NavTarget.parse("nowplaying").command(null).name);
        eq("startScreensaver", NavTarget.parse("screensaver").command(null).name);
        eq("showAppLauncher", NavTarget.parse("apps").command(null).name);
        eq("reload", NavTarget.parse("reload").command(null).name);
        eq("loadStartUrl", NavTarget.parse("home").command(null).name);
        eq("loadUrl", NavTarget.parse("https://x.example/").command(null).name);
        check(NavTarget.parse("music").command(null).arguments.isEmpty());
        eq("loadUrl", NavTarget.parse("/a/b").fallback("http://h").name);
        eq("http://h/a/b", NavTarget.parse("/a/b").fallback("http://h").arguments.get("url"));
        check(NavTarget.parse("/a/b").fallback(null) == null);
        check(NavTarget.parse("music").fallback("http://h") == null);
    }

    private static void menus() {
        NavItem menu = NavItem.menu("Climate", "mdi:weather-partly-cloudy",
            "Weather|mdi:weather-partly-cloudy|/bedroom-wall-tablet/weather; Health and air|mdi:home-heart|/bedroom-wall-tablet/health-and-air;\n"
                + "Music||music");
        eq(NavItem.Type.MENU, menu.type);
        eq(3, menu.children.size());
        eq(NavItem.Type.ACTION, menu.children.get(2).type);
        eq(NavItem.DEFAULT_ICON, menu.children.get(2).icon);
        check(menu.matches("/bedroom-wall-tablet/weather"));
        check(menu.matches("/bedroom-wall-tablet/health-and-air/"));
        check(!menu.matches("/bedroom-wall-tablet/toggles"));
        check(!menu.matches(null));
        rejectsMenu("");
        rejectsMenu("Weather|mdi:x");
        rejectsMenu("|mdi:x|/a");
        rejectsMenu("Bad|mdi:x|nowhere");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= NavItem.MAX_CHILDREN; i++) many.append("E").append(i).append("||/a/b").append(i).append(';');
        rejectsMenu(many.toString());
        String cameras = "Front porch|mdi:door|camera:front_porch;Garage|mdi:garage|camera:garage;Driveway|mdi:road-variant|camera:driveway;"
            + "Living|mdi:sofa|camera:living;Patio|mdi:patio-heater|camera:patio;South|mdi:grass|camera:south;East|mdi:sun-angle|camera:east;"
            + "Garage interior|mdi:garage|camera:garage_interior;Blue bedroom|mdi:sofa|camera:blue_bedroom";
        check(cameras.length() <= 512);
        eq(9, NavItem.menu("Cameras", "mdi:cctv", cameras).children.size());
        String[] icons = NavItem.icons("mdi:home-outline|mdi:home");
        eq("mdi:home-outline", icons[0]);
        eq("mdi:home", icons[1]);
        eq("mdi:cctv", NavItem.icons("mdi:cctv")[1]);
        eq(NavItem.DEFAULT_ICON, NavItem.icons("")[0]);
        // Separate fields win, a blank selected icon falls back to a legacy pipe, then to the icon.
        eq("mdi:home", NavItem.icons("mdi:home-outline", "mdi:home")[1]);
        eq("mdi:home", NavItem.icons("mdi:home-outline|mdi:home", "")[1]);
        eq("mdi:x", NavItem.icons("mdi:home-outline|mdi:home", "mdi:x")[1]);
        eq("mdi:cctv", NavItem.icons("mdi:cctv", "  ")[1]);
        eq("mdi:home", NavItem.leaf("Home", "mdi:home-outline", "mdi:home", "/a/b").iconSelected);
        eq("mdi:sun", NavItem.menu("M", "mdi:cloud", "mdi:sun", "A||/a/b").iconSelected);
    }

    private static void config() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("position", "Top");
        settings.put("labels", "Active only");
        settings.put("showOn", "/wall-tablet, /bedroom-wall-tablet/");
        settings.put("scale", "120%");
        settings.put("inset", 16.0);
        item(settings, 1, "Page", "Home", "mdi:home-outline", "/wall-tablet/0");
        settings.put("item1IconSelected", "mdi:home");
        item(settings, 2, "Menu", "Climate", "mdi:weather-partly-cloudy", "Weather||/bedroom-wall-tablet/weather");
        item(settings, 3, "Off", "Ignored", "", "");
        item(settings, 4, "Page", "Toggles", "mdi:toggle-switch-outline", "/bedroom-wall-tablet/toggles");
        item(settings, 5, "Action", "Cameras", "mdi:cctv", "camera:front");
        item(settings, 6, "Page", "", "mdi:x", "/a");
        item(settings, 7, "Action", "Wrong", "mdi:x", "/a/b");
        NavConfig cfg = NavConfig.parse(settings);
        check(cfg.top);
        eq(NavConfig.Labels.ACTIVE, cfg.labels);
        eq(1.2f, cfg.scale);
        eq(16, cfg.inset);
        eq(Arrays.asList("/wall-tablet", "/bedroom-wall-tablet"), cfg.showOn);
        eq("mdi:home", cfg.items.get(0).iconSelected);
        eq("mdi:home-outline", cfg.items.get(0).icon);
        eq(4, cfg.items.size());
        eq(2, cfg.problems.size());
        check(cfg.problems.get(0).startsWith("Item 6"));
        check(cfg.problems.get(1).startsWith("Item 7 (Wrong)"));
        eq(0, cfg.activeIndex("/wall-tablet"));
        eq(0, cfg.activeIndex("/wall-tablet/0"));
        eq(1, cfg.activeIndex("/bedroom-wall-tablet/weather"));
        eq(2, cfg.activeIndex("/bedroom-wall-tablet/toggles"));
        eq(-1, cfg.activeIndex("/bedroom-wall-tablet/cars"));
        eq(-1, cfg.activeIndex(null));
        check(cfg.visibleAt("/bedroom-wall-tablet/cars"));
        check(cfg.visibleAt(null));
        check(!cfg.visibleAt("/developer-tools/yaml"));

        Map<String, Object> wider = new HashMap<>(settings);
        wider.put("showOn", "");
        NavConfig everywhere = NavConfig.parse(wider);
        check(everywhere.visibleAt("/developer-tools/yaml"));
        check(everywhere.sameLook(cfg));
        wider.put("item4Label", "Switches");
        check(!NavConfig.parse(wider).sameLook(cfg));

        NavConfig empty = NavConfig.parse(new HashMap<String, Object>());
        check(!empty.top);
        eq(NavConfig.Labels.ALWAYS, empty.labels);
        eq(1f, empty.scale);
        eq(12, empty.inset);
        eq(1f, empty.opacity);
        eq(NavConfig.Collapse.OFF, empty.collapse);
        eq(0, empty.autoCollapse);
        check(empty.items.isEmpty());
        check(!empty.visibleAt("/anything"));

        Map<String, Object> folding = new HashMap<>(settings);
        folding.put("opacity", 70.0);
        folding.put("collapse", "Start collapsed");
        folding.put("autoCollapse", 30.0);
        NavConfig fold = NavConfig.parse(folding);
        eq(0.7f, fold.opacity);
        eq(NavConfig.Collapse.START_COLLAPSED, fold.collapse);
        eq(30, fold.autoCollapse);
        check(!fold.sameLook(cfg));
        folding.put("collapse", "Start open");
        eq(NavConfig.Collapse.START_OPEN, NavConfig.parse(folding).collapse);
        folding.put("collapse", "Off");
        // A countdown without collapsing would have nothing to do.
        eq(0, NavConfig.parse(folding).autoCollapse);
        folding.put("opacity", 5.0);
        eq(0.2f, NavConfig.parse(folding).opacity);

        Map<String, Object> odd = new HashMap<>();
        odd.put("scale", "huge");
        odd.put("inset", "12");
        odd.put("item1Type", 5);
        NavConfig tolerant = NavConfig.parse(odd);
        eq(1f, tolerant.scale);
        eq(12, tolerant.inset);
        check(tolerant.items.isEmpty());
    }

    private static void icons() {
        Map<String, Integer> names = MdiCodec.names(MdiCodec.decode(MdiData.NAMES));
        eq(MdiData.ICON_COUNT, names.size());
        eq(0xF02DC, names.get("home"));
        check(names.containsKey("home-outline"));
        check(names.containsKey("help-circle-outline"));
        check(names.containsKey("circle-outline"));
        byte[] font = MdiCodec.decode(MdiData.FONT);
        check(font.length > 1_000_000);
        // TrueType files start with version 1.0.
        check(font[0] == 0 && font[1] == 1 && font[2] == 0 && font[3] == 0);
        eq("home", MdiCodec.bareName("mdi:home"));
        eq("home", MdiCodec.bareName(" mdi-home "));
        eq("home", MdiCodec.bareName("home"));
        check(MdiCodec.bareName("phu:tesla-icon") == null);
        check(MdiCodec.bareName("mdi:") == null);
        check(MdiCodec.bareName(null) == null);
    }

    private static void item(Map<String, Object> settings, int slot, String type, String label, String icon, String target) {
        settings.put("item" + slot + "Type", type);
        settings.put("item" + slot + "Label", label);
        settings.put("item" + slot + "Icon", icon);
        settings.put("item" + slot + "Target", target);
    }

    private static void rejects(String target) {
        try {
            NavTarget.parse(target);
            throw new AssertionError("Expected a rejected target: '" + target + "'");
        } catch (IllegalArgumentException expected) { passed++; }
    }

    private static void rejectsMenu(String entries) {
        try {
            NavItem.menu("Menu", "", entries);
            throw new AssertionError("Expected a rejected menu: '" + entries + "'");
        } catch (IllegalArgumentException expected) { passed++; }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Check failed");
        passed++;
    }

    private static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
        passed++;
    }
}
