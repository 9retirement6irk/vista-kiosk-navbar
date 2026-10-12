// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

import java.net.URI;

/** Dashboard path helpers shared by matching and visibility. */
final class Paths {
    private Paths() {}

    static String stripTrailingSlashes(String path) {
        String result = path;
        while (result.length() > 1 && result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    /**
     * Drops the query and fragment and trailing slashes, and treats a dashboard's first view
     * ({@code /dash/0}) as its root ({@code /dash}), the way Home Assistant serves both.
     */
    static String normalize(String path) {
        if (path == null) return null;
        String result = path.trim();
        int cut = indexOfAny(result, '?', '#');
        if (cut >= 0) result = result.substring(0, cut);
        if (result.isEmpty()) return "/";
        if (!result.startsWith("/")) result = "/" + result;
        result = stripTrailingSlashes(result);
        if (result.matches("^/[^/]+/0$")) result = result.substring(0, result.length() - 2);
        return result;
    }

    /** Whether {@code path} is {@code prefix} or sits below it. Both are normalized first. */
    static boolean within(String path, String prefix) {
        String p = normalize(path);
        String root = normalize(prefix);
        if ("/".equals(root)) return true;
        return p.equals(root) || p.startsWith(root + "/");
    }

    /** {@code scheme://host[:port]} of an http(s) URL, or null. */
    static String origin(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return null;
            return scheme + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static int indexOfAny(String text, char a, char b) {
        int first = text.indexOf(a);
        int second = text.indexOf(b);
        if (first < 0) return second;
        if (second < 0) return first;
        return Math.min(first, second);
    }
}
