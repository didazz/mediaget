// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result returned by one platform extractor. */
public final class ExtractionResult {
    private final SocialPlatform platform;
    private final String canonicalUrl;
    private final String contentId;
    private final List<MediaItem> items;

    public ExtractionResult(
            SocialPlatform platform,
            String canonicalUrl,
            String contentId,
            List<MediaItem> items) {
        if (platform == null) {
            throw new IllegalArgumentException("La plataforma no puede estar vacía.");
        }
        if (canonicalUrl == null || canonicalUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("La URL canónica no puede estar vacía.");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("La extracción no contiene archivos.");
        }
        this.platform = platform;
        this.canonicalUrl = canonicalUrl;
        this.contentId = contentId == null ? "contenido" : contentId;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    public SocialPlatform getPlatform() {
        return platform;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public String getContentId() {
        return contentId;
    }

    public List<MediaItem> getItems() {
        return items;
    }
}
