// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Descarga Social contributors
package com.didazz.descargasocial;

/** An immutable image or video discovered in one supported publication. */
public final class MediaItem {
    private final String url;
    private final boolean video;
    private final String thumbnailUrl;
    private final int width;
    private final int height;
    private final SocialPlatform platform;
    private final String mimeType;
    private final String extension;
    private final String requestOrigin;
    private final boolean streamingManifest;
    private final boolean youtubeDataSaver;

    public MediaItem(String url, boolean video, String thumbnailUrl) {
        this(url, video, thumbnailUrl, 0, 0);
    }

    public MediaItem(String url, boolean video, String thumbnailUrl, int width, int height) {
        this(
                url,
                video,
                thumbnailUrl,
                width,
                height,
                SocialPlatform.INSTAGRAM,
                video ? "video/mp4" : "image/jpeg",
                video ? "mp4" : "jpg");
    }

    public MediaItem(
            String url,
            boolean video,
            String thumbnailUrl,
            int width,
            int height,
            SocialPlatform platform,
            String mimeType,
            String extension) {
        this(
                url,
                video,
                thumbnailUrl,
                width,
                height,
                platform,
                mimeType,
                extension,
                null,
                false);
    }

    public MediaItem(
            String url,
            boolean video,
            String thumbnailUrl,
            int width,
            int height,
            SocialPlatform platform,
            String mimeType,
            String extension,
            String requestOrigin,
            boolean streamingManifest) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("La URL multimedia no puede estar vacía.");
        }
        if (platform == null) {
            throw new IllegalArgumentException("La plataforma multimedia no puede estar vacía.");
        }
        this.url = url;
        this.video = video;
        this.thumbnailUrl = thumbnailUrl == null || thumbnailUrl.trim().isEmpty()
                ? null
                : thumbnailUrl;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.platform = platform;
        this.mimeType = mimeType == null || mimeType.trim().isEmpty()
                ? (video ? "video/mp4" : "image/jpeg")
                : mimeType.trim();
        String safeExtension = extension == null ? "" : extension.trim().toLowerCase();
        safeExtension = safeExtension.replaceAll("[^a-z0-9]", "");
        this.extension = safeExtension.isEmpty() ? (video ? "mp4" : "jpg") : safeExtension;
        this.requestOrigin = requestOrigin == null || requestOrigin.trim().isEmpty()
                ? null
                : requestOrigin.trim();
        this.streamingManifest = streamingManifest;
        this.youtubeDataSaver = false;
    }

    private MediaItem(MediaItem source, boolean youtubeDataSaver) {
        this.url=source.url; this.video=source.video; this.thumbnailUrl=source.thumbnailUrl;
        this.width=source.width; this.height=source.height; this.platform=source.platform;
        this.mimeType=source.mimeType; this.extension=source.extension;
        this.requestOrigin=source.requestOrigin; this.streamingManifest=source.streamingManifest;
        this.youtubeDataSaver=youtubeDataSaver;
    }
    static MediaItem youtube(String canonical, boolean saver, String thumbnail, int width, int height) {
        return new MediaItem(new MediaItem(canonical,true,thumbnail,width,height,SocialPlatform.YOUTUBE,
                "video/mp4","mp4","https://www.youtube.com",false),saver);
    }
    public boolean isYoutubeDataSaver() { return youtubeDataSaver; }

    public String getUrl() {
        return url;
    }

    public boolean isVideo() {
        return video;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    /** Returns an image suitable for a preview, or {@code null} if none is available. */
    public String getPreviewUrl() {
        if (thumbnailUrl != null) {
            return thumbnailUrl;
        }
        return video ? null : url;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public SocialPlatform getPlatform() {
        return platform;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getSuggestedExtension() {
        return extension;
    }

    /** HTTPS origin used only as a minimal Referer for media CDNs that require it. */
    public String getRequestOrigin() {
        return requestOrigin;
    }

    public boolean isStreamingManifest() {
        return streamingManifest;
    }
}
