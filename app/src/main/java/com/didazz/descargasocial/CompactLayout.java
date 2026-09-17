// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

/** Pixel geometry shared by the native workspace and deterministic small-window tests. */
final class CompactLayout {
    static final class Box {
        final int x, y, width, height;
        Box(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = Math.max(0, width); this.height = Math.max(0, height);
        }
    }
    final Box editor, status, content, action;
    CompactLayout(int width, int height, int editorDesired, int statusDesired, int actionDesired,
            int gap, boolean wide, boolean hasContent) {
        width = Math.max(0, width); height = Math.max(0, height);
        int actionHeight = Math.min(height, actionDesired);
        int remaining = Math.max(0, height - actionHeight - gap);
        int statusHeight = Math.min(statusDesired, remaining);
        int body = Math.max(0, remaining - statusHeight - gap);
        action = new Box(0, height - actionHeight, width, actionHeight);
        status = new Box(0, remaining - statusHeight, width, statusHeight);
        if (wide) {
            int left = Math.max(0, (width - gap) / 2);
            editor = new Box(0, 0, left, body);
            content = new Box(left + gap, 0, Math.max(0, width - left - gap), body);
        } else {
            int editorHeight = Math.min(editorDesired, hasContent ? (int) (body * 0.55) : body);
            editor = new Box(0, 0, width, editorHeight);
            int y = Math.min(body, editorHeight + gap);
            content = new Box(0, y, width, body - y);
        }
    }
}
