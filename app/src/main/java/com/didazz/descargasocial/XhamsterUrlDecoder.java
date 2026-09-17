// SPDX-License-Identifier: GPL-3.0-or-later
// The byte-generator behavior follows the Unlicense yt-dlp source implementation.
package com.didazz.descargasocial;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decodes the bounded URL representation used by current xHamster player metadata. */
final class XhamsterUrlDecoder {
    private static final int MAX_VALUE_CHARS = 16 * 1024;
    private static final Pattern BARE_HEX = Pattern.compile("^[0-9A-Fa-f]{12,}$");
    private static final Pattern ENCRYPTED_PATH = Pattern.compile(
            "^/([0-9A-Fa-f]{12,})([/,].+)$");

    private XhamsterUrlDecoder() {
    }

    static String decode(String value) {
        if (value == null || value.length() > MAX_VALUE_CHARS) {
            return null;
        }
        String trimmed = value.trim();
        if (BARE_HEX.matcher(trimmed).matches()) {
            String decoded = decryptHex(trimmed);
            return decoded != null && decoded.startsWith("https://") ? decoded : null;
        }
        try {
            URI uri = new URI(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawAuthority() == null) {
                return null;
            }
            String rawPath = uri.getRawPath();
            Matcher encrypted = rawPath == null ? null : ENCRYPTED_PATH.matcher(rawPath);
            if (encrypted == null || !encrypted.matches()) {
                return null;
            }
            String decodedPath = decryptHex(encrypted.group(1));
            if (!isSafePathText(decodedPath)) {
                return null;
            }
            int pathStart = findPathStart(trimmed);
            if (pathStart < 0) {
                return null;
            }
            int pathEnd = trimmed.length();
            int query = trimmed.indexOf('?', pathStart);
            int fragment = trimmed.indexOf('#', pathStart);
            if (query >= 0) pathEnd = Math.min(pathEnd, query);
            if (fragment >= 0) pathEnd = Math.min(pathEnd, fragment);
            return trimmed.substring(0, pathStart) + "/" + decodedPath
                    + encrypted.group(2) + trimmed.substring(pathEnd);
        } catch (Exception invalid) {
            return null;
        }
    }

    private static int findPathStart(String value) {
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return -1;
        }
        int start = value.indexOf('/', scheme + 3);
        if (start >= 0) {
            return start;
        }
        int query = value.indexOf('?', scheme + 3);
        int fragment = value.indexOf('#', scheme + 3);
        if (query >= 0) return query;
        return fragment;
    }

    private static String decryptHex(String hex) {
        if (hex == null || (hex.length() & 1) != 0 || hex.length() < 12
                || hex.length() > MAX_VALUE_CHARS) {
            return null;
        }
        byte[] payload = new byte[hex.length() / 2];
        for (int index = 0; index < payload.length; index++) {
            int high = Character.digit(hex.charAt(index * 2), 16);
            int low = Character.digit(hex.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            payload[index] = (byte) ((high << 4) | low);
        }
        if (payload.length <= 5) {
            return null;
        }
        int algorithm = payload[0] & 0xff;
        if (algorithm < 1 || algorithm > 7) {
            return null;
        }
        int seed = payload[1] & 0xff
                | (payload[2] & 0xff) << 8
                | (payload[3] & 0xff) << 16
                | (payload[4] & 0xff) << 24;
        Generator generator = new Generator(algorithm, seed);
        byte[] decoded = new byte[payload.length - 5];
        for (int index = 0; index < decoded.length; index++) {
            decoded[index] = (byte) ((payload[index + 5] & 0xff) ^ (generator.next() & 0xff));
        }
        String result = new String(decoded, StandardCharsets.ISO_8859_1);
        return isSafeText(result) ? result : null;
    }

    private static boolean isSafeText(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafePathText(String value) {
        return isSafeText(value) && value.indexOf('?') < 0 && value.indexOf('#') < 0
                && value.indexOf('\\') < 0;
    }

    private static final class Generator {
        private final int algorithm;
        private int state;

        Generator(int algorithm, int seed) {
            this.algorithm = algorithm;
            this.state = seed;
        }

        int next() {
            int mixed;
            switch (algorithm) {
                case 1:
                    state = state * 1664525 + 1013904223;
                    return state;
                case 2:
                    mixed = state ^ state << 13;
                    mixed ^= mixed >>> 17;
                    state = mixed ^ mixed << 5;
                    return state;
                case 3:
                    state += 0x9e3779b9;
                    mixed = state ^ state >>> 16;
                    mixed *= 0x85ebca77;
                    mixed ^= mixed >>> 13;
                    mixed *= 0xc2b2ae3d;
                    return mixed ^ mixed >>> 16;
                case 4:
                    state += 0x6d2b79f5;
                    mixed = Integer.rotateLeft(state, 7) + 0x9e3779b9;
                    mixed ^= mixed >>> 11;
                    return mixed * 0x27d4eb2d;
                case 5:
                    mixed = state ^ state << 7;
                    mixed ^= mixed >>> 9;
                    mixed ^= mixed << 8;
                    state = mixed + 0xa5a5a5a5;
                    return state;
                case 6:
                    state = state * 0x2c9277b5 + 0xac564b05;
                    mixed = state ^ state >>> 18;
                    return mixed >>> ((state >>> 27) & 31);
                case 7:
                    state += 0x9e3779b9;
                    mixed = state ^ state << 5;
                    mixed *= 0x7feb352d;
                    mixed ^= mixed >>> 15;
                    return mixed * 0x846ca68b;
                default:
                    throw new IllegalStateException("Algoritmo no compatible");
            }
        }
    }
}
