// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Identifies media from bytes and MIME instead of trusting a filename extension. */
public final class MediaValidator {
    public static final long MAX_VIDEO_BYTES = 2L * 1024L * 1024L * 1024L;

    public enum Format {
        MP4("video/mp4", "mp4", false),
        WEBM("video/webm", "webm", false),
        MPEG_TS("video/mp2t", "ts", false),
        HLS("application/vnd.apple.mpegurl", "ts", true);

        private final String mimeType;
        private final String extension;
        private final boolean manifest;

        Format(String mimeType, String extension, boolean manifest) {
            this.mimeType = mimeType;
            this.extension = extension;
            this.manifest = manifest;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getExtension() {
            return extension;
        }

        public boolean isManifest() {
            return manifest;
        }
    }

    private MediaValidator() {
    }

    public static Format detect(byte[] prefix, String contentType) throws IOException {
        String mime = normalizeMime(contentType);
        if (mime.startsWith("text/html")
                || mime.contains("json")
                || mime.contains("xml")
                || mime.startsWith("image/")
                || mime.equals("application/zip")
                || mime.equals("application/gzip")
                || mime.equals("application/x-rar-compressed")
                || mime.equals("application/x-7z-compressed")) {
            throw new IOException("La dirección candidata no devolvió un vídeo.");
        }
        if (looksLikeArchive(prefix)) {
            throw new IOException("La dirección candidata devolvió un archivo comprimido.");
        }
        if (looksLikeHls(prefix)) {
            return Format.HLS;
        }
        if (looksLikeIsoVideo(prefix)) {
            return Format.MP4;
        }
        if (looksLikeWebm(prefix)) {
            return Format.WEBM;
        }
        if (looksLikeTransportStream(prefix)) {
            return Format.MPEG_TS;
        }
        throw new IOException("El servidor no entregó un formato de vídeo reconocible.");
    }

    public static String normalizeMime(String value) {
        if (value == null) {
            return "";
        }
        int semicolon = value.indexOf(';');
        String mime = semicolon < 0 ? value : value.substring(0, semicolon);
        return mime.trim().toLowerCase(Locale.US);
    }

    static boolean looksLikeHls(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            start = 3;
        }
        while (start < bytes.length && Character.isWhitespace((char) (bytes[start] & 0xff))) {
            start++;
        }
        byte[] marker = "#EXTM3U".getBytes(StandardCharsets.US_ASCII);
        if (bytes.length - start < marker.length) {
            return false;
        }
        for (int index = 0; index < marker.length; index++) {
            if (bytes[start + index] != marker[index]) {
                return false;
            }
        }
        return true;
    }

    static boolean looksLikeIsoVideo(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        int limit = Math.min(bytes.length - 8, 128);
        for (int offset = 0; offset <= limit; offset++) {
            if (bytes[offset + 4] != 'f' || bytes[offset + 5] != 't'
                    || bytes[offset + 6] != 'y' || bytes[offset + 7] != 'p') {
                continue;
            }
            long boxSize = uint32(bytes, offset);
            if (boxSize < 12 || boxSize > MediaValidator.MAX_VIDEO_BYTES) {
                continue;
            }
            String brand = new String(bytes, offset + 8, 4, StandardCharsets.US_ASCII)
                    .toLowerCase(Locale.US);
            if (brand.equals("avif") || brand.equals("avis") || brand.equals("heic")
                    || brand.equals("heix") || brand.equals("hevc") || brand.equals("mif1")
                    || brand.equals("msf1")) {
                return false;
            }
            return true;
        }
        return false;
    }

    static boolean looksLikeWebm(byte[] bytes) {
        if (bytes == null || bytes.length < 12
                || (bytes[0] & 0xff) != 0x1a
                || (bytes[1] & 0xff) != 0x45
                || (bytes[2] & 0xff) != 0xdf
                || (bytes[3] & 0xff) != 0xa3) {
            return false;
        }
        String header = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.US);
        return header.contains("webm") || header.contains("matroska");
    }

    static boolean looksLikeTransportStream(byte[] bytes) {
        if (bytes == null || bytes.length < 377 || (bytes[0] & 0xff) != 0x47) {
            return false;
        }
        return (bytes[188] & 0xff) == 0x47 || bytes.length > 384 && (bytes[192] & 0xff) == 0x47;
    }

    private static boolean looksLikeArchive(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        return bytes[0] == 'P' && bytes[1] == 'K'
                || (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b
                || bytes[0] == 'R' && bytes[1] == 'a' && bytes[2] == 'r' && bytes[3] == '!'
                || (bytes[0] & 0xff) == 0x37 && (bytes[1] & 0xff) == 0x7a
                && (bytes[2] & 0xff) == 0xbc && (bytes[3] & 0xff) == 0xaf;
    }

    private static long uint32(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xffL) << 24
                | ((long) bytes[offset + 1] & 0xffL) << 16
                | ((long) bytes[offset + 2] & 0xffL) << 8
                | (long) bytes[offset + 3] & 0xffL;
    }
}
