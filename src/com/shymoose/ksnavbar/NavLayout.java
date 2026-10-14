// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksnavbar;

/** Sizing rules shared by native layouts and their boundary tests. */
final class NavLayout {
    static final int MIN_TOUCH_DP = 48;

    private NavLayout() {}

    static int columns(int count, int availablePx, int cellPx) {
        int preferred = count <= 9 ? 3 : 4;
        return Math.max(1, Math.min(preferred, availablePx / Math.max(1, cellPx)));
    }

    static int tileWidth(int availablePx, int columns, int preferredPx, int marginPx) {
        return Math.max(1, Math.min(preferredPx, availablePx / columns - marginPx));
    }
}
