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
        guesses();
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
        eq("84981591289e0d2f116e8744ccbad468", NavTarget.parse(" 84981591289e0d2f116e8744ccbad468 ").value);
        eq(NavTarget.Kind.CAMERA, NavTarget.parse("febf930c62bb15cd2a7ade43c77b092f").kind);
        eq(NavTarget.Kind.MUSIC, NavTarget.parse("music").kind);
        eq(NavTarget.Kind.NOW_PLAYING, NavTarget.parse("NowPlaying").kind);
        eq(NavTarget.Kind.NOW_PLAYING, NavTarget.parse("now-playing").kind);
        eq(NavTarget.Kind.SCREENSAVER, NavTarget.parse("screensaver").kind);
        eq(NavTarget.Kind.APPS, NavTarget.parse("apps").kind);
        eq(NavTarget.Kind.RELOAD, NavTarget.parse("reload").kind);
        eq(NavTarget.Kind.HOME, NavTarget.parse("home").kind);
        rejects("");
        rejects("   ");
        rejects("camera:84981591289e0d2f116e8744ccbad468");
        rejects("4981591289e0d2f116e8744ccbad468");
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
        eq("showCameraView", NavTarget.parse("84981591289e0d2f116e8744ccbad468").command(null).name);
        eq("84981591289e0d2f116e8744ccbad468", NavTarget.parse("84981591289e0d2f116e8744ccbad468").command(null).arguments.get("viewId"));
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
            "Weather = /bedroom-wall-tablet/weather = mdi:weather-partly-cloudy; Health and air=/bedroom-wall-tablet/health-and-air=mdi:home-heart;\n"
                + "Music = music");
        eq(NavItem.Type.MENU, menu.type);
        eq(3, menu.children.size());
        eq(NavItem.Type.ACTION, menu.children.get(2).type);
        eq("mdi:music", menu.children.get(2).icon);
        check(menu.matches("/bedroom-wall-tablet/weather"));
        check(menu.matches("/bedroom-wall-tablet/health-and-air/"));
        check(!menu.matches("/bedroom-wall-tablet/toggles"));
        check(!menu.matches(null));
        rejectsMenu("");
        rejectsMenu("Weather");
        rejectsMenu(" = /a/b");
        rejectsMenu("Bad = nowhere");
        rejectsMenu("Old|mdi:x|/a/b");
        // Only the first = ends the label, so a page keeps = in its query.
        eq("/a/b?x=1", NavItem.menu("M", "", "A = /a/b?x=1").children.get(0).target.value);
        eq("mdi:door", NavItem.menu("M", "", "A = /a/b?x=1 = mdi:door").children.get(0).icon);
        eq("mdi:weather-partly-cloudy", menu.children.get(0).icon);
        eq("mdi:home-heart", menu.children.get(1).icon);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= NavItem.MAX_CHILDREN; i++) many.append("E").append(i).append(" = /a/b").append(i).append(';');
        rejectsMenu(many.toString());
        // Twelve cameras with real-length view IDs fit in one setting.
        StringBuilder cams = new StringBuilder();
        String[] names = {"Exterior", "Interior", "Front porch", "Garage", "Driveway", "Living", "Patio", "South", "East",
            "Garage interior", "Blue bedroom", "Trey"};
        for (int i = 0; i < names.length; i++) {
            if (i > 0) cams.append(';');
            cams.append(names[i]).append('=').append(String.format("%032x", i + 1));
        }
        check(cams.length() <= 512);
        eq(12, NavItem.menu("Cameras", "mdi:cctv", cams.toString()).children.size());
        // Camera entries without an icon get a camera icon. Other entries get the generic one.
        String cam = "8c65cf7cebe2af4c301a5cf41d402cc9";
        NavItem bare = NavItem.menu("Cameras", "mdi:cctv", "Cam 9 = " + cam + "; Garage = " + cam + " = mdi:door; Zork = /a/z; Driveway = " + cam);
        eq(NavItem.CAMERA_ICON, bare.children.get(0).icon);
        eq("mdi:door", bare.children.get(1).icon);
        eq(NavItem.DEFAULT_ICON, bare.children.get(2).icon);
        eq("mdi:road-variant", bare.children.get(3).icon);
        eq("mdi:weather-partly-cloudy", NavItem.menu("Weather", "", "A = /a/a").icon);
        eq("mdi:home", NavItem.leaf("Home", "", "mdi:home", "/a/b").iconSelected);
        eq("mdi:home-outline", NavItem.leaf("Home", "", "", "/a/b").icon);
        eq(NavItem.CAMERA_ICON, NavItem.leaf("C", "", "mdi:x", cam).icon);
        eq("mdi:x", NavItem.leaf("C", "", "mdi:x", cam).iconSelected);
        // A blank icon uses the fallback, a blank selected icon the normal icon.
        eq(NavItem.DEFAULT_ICON, NavItem.icons("", "", NavItem.DEFAULT_ICON)[0]);
        eq("mdi:home", NavItem.icons("mdi:home-outline", "mdi:home", "x")[1]);
        eq("mdi:cctv", NavItem.icons("mdi:cctv", "  ", "x")[1]);
        eq("mdi:home", NavItem.leaf("Home", "mdi:home-outline", "mdi:home", "/a/b").iconSelected);
        eq("mdi:sun", NavItem.menu("M", "mdi:cloud", "mdi:sun", "A = /a/b").iconSelected);
        // Menus are recognized by their entries. A page or URL with = in its query is not a menu.
        check(NavConfig.isMenu("Weather = /a/b"));
        check(NavConfig.isMenu("A = /a; B = /b"));
        check(!NavConfig.isMenu("/a/b?x=1"));
        check(!NavConfig.isMenu("https://x.example/?a=b"));
        check(!NavConfig.isMenu("music"));
    }
    private static void guesses() {
        eq("mdi:garage-variant", IconGuess.forLabel("Garage interior"));
        eq("mdi:garage", IconGuess.forLabel("Garage"));
        eq("mdi:road-variant", IconGuess.forLabel("Driveway"));
        eq("mdi:door", IconGuess.forLabel("Front porch"));
        eq("mdi:sofa", IconGuess.forLabel("Living room"));
        eq("mdi:bed", IconGuess.forLabel("Blue bedroom"));
        eq("mdi:home-export-outline", IconGuess.forLabel("Exterior"));
        eq("mdi:home-import-outline", IconGuess.forLabel("Interior"));
        eq("mdi:arrow-down-circle-outline", IconGuess.forLabel("South"));
        eq("mdi:patio-heater", IconGuess.forLabel("Patio"));
        eq("mdi:heart-pulse", IconGuess.forLabel("System health"));
        eq("mdi:air-filter", IconGuess.forLabel("Health and air"));
        eq("mdi:toggle-switch-outline", IconGuess.forLabel("Toggles"));
        // Whole words only: these must not match bed, door or air.
        check(IconGuess.forLabel("Bedside") == null);
        check(IconGuess.forLabel("Outdoorsy") == null);
        check(IconGuess.forLabel("Fairy") == null);
        check(IconGuess.forLabel("Trey") == null);
        check(IconGuess.forLabel(null) == null);
    }

    private static void config() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("position", "Top");
        settings.put("labels", "Active only");
        settings.put("hideOn", "/developer-tools, /profile/");
        settings.put("scale", "120%");
        item(settings, 1, "Home", "mdi:home-outline", "/wall-tablet/0");
        settings.put("item1IconSelected", "mdi:home");
        item(settings, 2, "Climate", "mdi:weather-partly-cloudy", "Weather = /bedroom-wall-tablet/weather");
        item(settings, 3, "", "mdi:x", "/ignored");
        item(settings, 4, "Toggles", "", "/bedroom-wall-tablet/toggles");
        item(settings, 5, "Cameras", "mdi:cctv", "8c65cf7cebe2af4c301a5cf41d402cc9");
        item(settings, 6, "Broken", "", "");
        item(settings, 7, "Bad", "", "nowhere");
        NavConfig cfg = NavConfig.parse(settings);
        check(cfg.top);
        eq(NavConfig.Labels.ACTIVE, cfg.labels);
        eq(1.2f, cfg.scale);
        eq(NavConfig.DEFAULT_INSET, cfg.inset);
        eq(Arrays.asList("/developer-tools", "/profile"), cfg.hideOn);
        eq("mdi:home", cfg.items.get(0).iconSelected);
        eq("mdi:home-outline", cfg.items.get(0).icon);
        // The kind follows the target: a path is a page, entries make a menu, the rest are actions.
        eq(4, cfg.items.size());
        eq(NavItem.Type.PAGE, cfg.items.get(0).type);
        eq(NavItem.Type.MENU, cfg.items.get(1).type);
        eq(NavItem.Type.ACTION, cfg.items.get(3).type);
        eq("mdi:toggle-switch-outline", cfg.items.get(2).icon);
        eq(2, cfg.problems.size());
        check(cfg.problems.get(0).startsWith("Item 6 (Broken)"));
        check(cfg.problems.get(1).startsWith("Item 7 (Bad)"));
        eq(0, cfg.activeIndex("/wall-tablet"));
        eq(0, cfg.activeIndex("/wall-tablet/0"));
        eq(1, cfg.activeIndex("/bedroom-wall-tablet/weather"));
        eq(2, cfg.activeIndex("/bedroom-wall-tablet/toggles"));
        eq(-1, cfg.activeIndex("/bedroom-wall-tablet/cars"));
        eq(-1, cfg.activeIndex(null));
        // Shown everywhere except Hide on.
        check(cfg.visibleAt("/bedroom-wall-tablet/cars"));
        check(cfg.visibleAt(null));
        check(!cfg.visibleAt("/developer-tools/yaml"));
        check(!cfg.visibleAt("/profile"));
        check(cfg.visibleAt("/profiles"));
        check(NavConfig.isMenu("A=/a;B=/b"));
        check(NavConfig.isMenu("A = /a = mdi:x"));
        check(!NavConfig.isMenu("/a/b"));

        Map<String, Object> wider = new HashMap<>(settings);
        wider.put("hideOn", "");
        NavConfig everywhere = NavConfig.parse(wider);
        check(everywhere.visibleAt("/developer-tools/yaml"));
        check(everywhere.sameLook(cfg));
        wider.put("item4Label", "Switches");
        check(!NavConfig.parse(wider).sameLook(cfg));

        StringBuilder seven = new StringBuilder();
        for (int i = 0; i < 7; i++) seven.append("E").append(i).append(" = /a/e").append(i).append(';');
        NavItem longMenu = NavItem.menu("M", "", seven.toString());
        NavItem shortMenu = NavItem.menu("M", "", "A = /a/a; B = /a/b");
        NavConfig auto = NavConfig.parse(new HashMap<String, Object>());
        eq(NavConfig.MenuLayout.AUTO, auto.menuLayout);
        check(auto.grid(longMenu));
        check(!auto.grid(shortMenu));

        NavConfig empty = NavConfig.parse(new HashMap<String, Object>());
        check(!empty.top);
        eq(NavConfig.Labels.ALWAYS, empty.labels);
        eq(1f, empty.scale);
        eq(NavConfig.DEFAULT_INSET, empty.inset);
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
        odd.put("item1Label", 5);
        odd.put("item1Target", "/a");
        NavConfig tolerant = NavConfig.parse(odd);
        eq(1f, tolerant.scale);
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

    private static void item(Map<String, Object> settings, int slot, String label, String icon, String target) {
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
