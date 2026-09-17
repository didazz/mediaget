// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.OutputStream;
import java.net.URI;
import java.util.List;

/** Routes one supported link to its isolated platform extractor. */
public final class SocialExtractor {
    private SocialExtractor() {
    }

    public static ExtractionResult extract(String url, boolean dataSaver) throws Exception {
        SocialPlatform platform = SocialLinkParser.detectPlatform(url);
        if (platform == SocialPlatform.YOUTUBE) { return YoutubeExtractor.extract(url, dataSaver); }
        if (platform == SocialPlatform.FACEBOOK) {
            return FacebookExtractor.extract(url, dataSaver);
        }
        if (platform == SocialPlatform.INSTAGRAM) {
            List<MediaItem> items = InstagramExtractor.getMediaItems(url, dataSaver);
            String contentId = InstagramExtractor.extractShortcode(url);
            if (contentId == null) {
                contentId = lastUsefulPathSegment(url);
            }
            return new ExtractionResult(
                    SocialPlatform.INSTAGRAM,
                    url,
                    contentId == null ? "contenido" : contentId,
                    items);
        }
        if (platform == SocialPlatform.GENERIC) {
            return GenericWebExtractor.extract(url, dataSaver);
        }
        throw new IllegalArgumentException("El enlace no pertenece a una plataforma compatible.");
    }

    public static byte[] fetchPreview(MediaItem item) throws Exception {
        if (item == null) {
            throw new IllegalArgumentException("El archivo no puede estar vacío.");
        }
        String preview = item.getPreviewUrl();
        if (preview == null && !item.isVideo()) {
            preview = item.getUrl();
        }
        if (preview == null) {
            throw new IllegalArgumentException("Este archivo no tiene vista previa.");
        }
        if (item.getPlatform() == SocialPlatform.FACEBOOK) {
            return FacebookExtractor.fetchBytes(preview);
        }
        if (item.getPlatform() == SocialPlatform.INSTAGRAM) {
            return InstagramExtractor.fetchBytes(preview);
        }
        if (item.getPlatform() == SocialPlatform.GENERIC || item.getPlatform() == SocialPlatform.YOUTUBE) {
            return GenericWebExtractor.fetchPreview(item);
        }
        throw new IllegalArgumentException("La plataforma de la vista previa no es compatible.");
    }

    public static void downloadToStream(MediaItem item, OutputStream output) throws Exception {
        if (item == null) {
            throw new IllegalArgumentException("El archivo no puede estar vacío.");
        }
        if (item.getPlatform() == SocialPlatform.FACEBOOK) {
            FacebookExtractor.downloadToStream(item, output);
        } else if (item.getPlatform() == SocialPlatform.INSTAGRAM) {
            InstagramExtractor.downloadToStream(item.getUrl(), output);
        } else if (item.getPlatform() == SocialPlatform.GENERIC) {
            GenericWebExtractor.downloadToStream(item, output);
        } else {
            throw new IllegalArgumentException("La plataforma de descarga no es compatible.");
        }
    }

    private static String lastUsefulPathSegment(String value) {
        try {
            URI uri = new URI(value);
            String[] parts = uri.getPath() == null ? new String[0] : uri.getPath().split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].replaceAll("[^A-Za-z0-9_-]", "");
                if (!candidate.isEmpty()
                        && !candidate.equalsIgnoreCase("share")
                        && !candidate.equalsIgnoreCase("p")
                        && !candidate.equalsIgnoreCase("reel")) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // El extractor de plataforma ya ha validado el enlace.
        }
        return null;
    }
}
