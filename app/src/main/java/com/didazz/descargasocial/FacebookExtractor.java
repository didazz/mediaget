// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Descarga Social contributors
package com.didazz.descargasocial;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts public Facebook posts, photos, videos and Reels on the device. */
public final class FacebookExtractor {
    private static final String HOME_URL = "https://www.facebook.com/";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_PAGE_BYTES = 18 * 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 32 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 8;
    private static final int MAX_JSON_DEPTH = 90;

    private static final Pattern SCRIPT_JSON_PATTERN = Pattern.compile(
            "<script\\b(?=[^>]*\\btype\\s*=\\s*[\"']application/json[\"'])"
                    + "[^>]*>(.*?)</script\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_TAG_PATTERN = Pattern.compile(
            "<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*([\"'])(.*?)\\2",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PATH_REEL_PATTERN = Pattern.compile(
            "(?i)/(?:reel|reels)/(\\d{5,30})(?:[/?#]|$)");
    private static final Pattern PATH_VIDEO_PATTERN = Pattern.compile(
            "(?i)/videos/(?:[^/?#]+/)?(\\d{5,30})(?:[/?#]|$)");
    private static final Pattern PATH_POST_PATTERN = Pattern.compile(
            "(?i)/posts/(?:[^/?#]+/)?([A-Za-z0-9]{5,80})(?:[/?#]|$)");
    private static final Pattern PATH_PERMALINK_PATTERN = Pattern.compile(
            "(?i)/permalink/([A-Za-z0-9_-]{5,80})(?:[/?#]|$)");
    private static final Pattern PATH_PHOTO_PATTERN = Pattern.compile(
            "(?i)/photo/([A-Za-z0-9_-]{5,80})(?:[/?#]|$)");

    private FacebookExtractor() {
    }

    /** Resolves one Facebook link and extracts every public media attachment it contains. */
    public static ExtractionResult extract(String url, boolean dataSaver) throws Exception {
        if (!SocialLinkParser.isSupportedPath(url)
                || SocialLinkParser.detectPlatform(url) != SocialPlatform.FACEBOOK) {
            throw new IllegalArgumentException(
                    "Usa el enlace de una publicación, una foto, un vídeo o un Reel de Facebook.");
        }
        if (isStoryUrl(url)) {
            throw new IOException(
                    "Las Historias de Facebook requieren una sesión y todavía no son compatibles.");
        }

        Page page = fetchPage(url);
        if (isStoryUrl(page.responseUrl)) {
            throw new IOException(
                    "Las Historias de Facebook requieren una sesión y todavía no son compatibles.");
        }
        try {
            return parseResponse(url, page.responseUrl, page.html, dataSaver);
        } catch (IOException firstFailure) {
            String canonical = findMetaContent(page.html, "property", "og:url");
            if (!isSafeFacebookUrl(canonical)
                    || canonical.equals(page.responseUrl)
                    || isStoryUrl(canonical)) {
                throw firstFailure;
            }
            try {
                Page canonicalPage = fetchPage(canonical);
                return parseResponse(url, canonicalPage.responseUrl, canonicalPage.html, dataSaver);
            } catch (IOException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
    }

    /** Package-private entry point used by deterministic parser tests. */
    static ExtractionResult parseResponse(
            String requestedUrl,
            String responseUrl,
            String html,
            boolean dataSaver) throws Exception {
        if (html == null || html.trim().isEmpty()) {
            throw new IOException("Facebook devolvió una respuesta vacía.");
        }

        String canonical = firstNonBlank(
                findMetaContent(html, "property", "og:url"), responseUrl, requestedUrl);
        if (!isSafeFacebookUrl(canonical)) {
            canonical = isSafeFacebookUrl(responseUrl) ? responseUrl : requestedUrl;
        }
        if (isStoryUrl(canonical)) {
            throw new IOException(
                    "Las Historias de Facebook requieren una sesión y todavía no son compatibles.");
        }
        if (isLoginUrl(responseUrl) || isLoginUrl(canonical)) {
            throw new IOException(
                    "Facebook exige iniciar sesión para este contenido; puede ser privado, "
                            + "estar restringido por edad o no estar disponible.");
        }

        String ogImage = findMetaContent(html, "property", "og:image");
        if (!isAllowedMediaUrl(ogImage)) {
            ogImage = null;
        }
        String ogVideo = firstNonBlank(
                findMetaContent(html, "property", "og:video:secure_url"),
                findMetaContent(html, "property", "og:video:url"),
                findMetaContent(html, "property", "og:video"));
        if (!isAllowedMediaUrl(ogVideo)) {
            ogVideo = null;
        }

        LinkedHashSet<String> responseIds = idsFromUrl(responseUrl);
        LinkedHashSet<String> canonicalIds = idsFromUrl(canonical);
        LinkedHashSet<String> requestedIds = idsFromUrl(requestedUrl);
        LinkedHashSet<String> targetIds = !responseIds.isEmpty()
                ? responseIds
                : (!canonicalIds.isEmpty() ? canonicalIds : requestedIds);
        boolean identityConflict = identitiesConflict(responseIds, canonicalIds)
                || identitiesConflict(responseIds, requestedIds)
                || identitiesConflict(canonicalIds, requestedIds);
        ParseAccumulator parsed = parseJsonScripts(html, targetIds, dataSaver, ogImage);
        if (parsed.items.isEmpty() && looksRateLimited(html)) {
            throw new IOException(
                    "Facebook ha limitado temporalmente las consultas. Espera un poco y reintenta.");
        }
        if (parsed.items.isEmpty() && looksLoginRequired(html)) {
            throw new IOException(
                    "Facebook exige iniciar sesión para este contenido; puede ser privado, "
                            + "estar restringido por edad o no estar disponible.");
        }
        if (parsed.items.isEmpty() && !identityConflict && ogVideo != null) {
            parsed.add(
                    firstTargetId(targetIds),
                    new MediaItem(
                            ogVideo,
                            true,
                            ogImage,
                            0,
                            0,
                            SocialPlatform.FACEBOOK,
                            "video/mp4",
                            "mp4"));
        }

        boolean videoPage = isLikelyVideoUrl(responseUrl)
                || isLikelyVideoUrl(canonical)
                || isLikelyVideoUrl(requestedUrl);
        if (parsed.items.isEmpty() && !identityConflict && !videoPage && ogImage != null) {
            String originalImage = ogImage;
            String selectedImage = dataSaver ? originalImage : maximizeFacebookImageUrl(originalImage);
            parsed.add(
                    firstTargetId(targetIds),
                    new MediaItem(
                            selectedImage,
                            false,
                            originalImage,
                            0,
                            0,
                            SocialPlatform.FACEBOOK,
                            "image/jpeg",
                            "jpg"));
        }

        if (parsed.items.isEmpty()) {
            if (videoPage) {
                throw new IOException(
                        "Facebook no entregó el vídeo público en un formato compatible. "
                                + "Será necesario actualizar la aplicación si el formato ha cambiado.");
            }
            throw new IOException(
                    "Facebook no ofrece archivos públicos descargables para esta publicación.");
        }

        String contentId = firstNonBlank(parsed.discoveredId, firstTargetId(targetIds));
        if (contentId == null) {
            contentId = safeLastSegment(canonical);
        }
        return new ExtractionResult(
                SocialPlatform.FACEBOOK,
                canonical,
                contentId == null ? "contenido" : contentId,
                new ArrayList<>(parsed.items.values()));
    }

    /** Downloads one previously extracted Facebook item. */
    public static void downloadToStream(MediaItem item, OutputStream output) throws Exception {
        if (item == null || item.getPlatform() != SocialPlatform.FACEBOOK) {
            throw new IllegalArgumentException("El archivo no pertenece a Facebook.");
        }
        if (output == null) {
            throw new IllegalArgumentException("El destino de la descarga no puede estar vacío.");
        }
        try {
            copyMedia(item.getUrl(), output, item.isVideo(), -1);
        } catch (MediaRejectedException primaryFailure) {
            String fallback = item.getThumbnailUrl();
            if (item.isVideo() || isBlank(fallback) || fallback.equals(item.getUrl())) {
                throw primaryFailure;
            }
            try {
                copyMedia(fallback, output, false, -1);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    /** Downloads a Facebook thumbnail with a strict memory limit. */
    public static byte[] fetchBytes(String url) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(32_768);
        copyMedia(url, output, false, MAX_PREVIEW_BYTES);
        return output.toByteArray();
    }

    private static Page fetchPage(String inputUrl) throws Exception {
        String current = inputUrl.trim();
        String referer = null;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            ensureFacebookPageUrl(current);
            HttpURLConnection connection = null;
            try {
                connection = openConnection(current, referer, false);
                int status = connection.getResponseCode();
                if (status == 429) {
                    throw new IOException(
                            "Facebook ha limitado temporalmente las consultas. Espera un poco y reintenta.");
                }
                if (isRedirect(status)) {
                    String location = connection.getHeaderField("Location");
                    if (isBlank(location)) {
                        throw new IOException("Facebook devolvió una redirección sin destino.");
                    }
                    String next = resolveLocation(current, location);
                    if (isStoryUrl(next)) {
                        throw new IOException(
                                "Las Historias de Facebook requieren una sesión y todavía no son compatibles.");
                    }
                    ensureFacebookPageUrl(next);
                    referer = current;
                    current = next;
                    continue;
                }
                String body = readResponse(connection, status, MAX_PAGE_BYTES);
                if (status == 401 || status == 403 || status == 404 || status == 410) {
                    throw new IOException(
                            "Facebook exige iniciar sesión o no ofrece públicamente este contenido.");
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("No se pudo consultar Facebook (HTTP " + status + ").");
                }
                return new Page(current, body);
            } catch (IOException error) {
                throw error;
            } finally {
                if (connection != null) {
                    DownloadControl.release(connection);
                    connection.disconnect();
                }
            }
        }
        throw new IOException("El enlace de Facebook contiene demasiadas redirecciones.");
    }

    private static ParseAccumulator parseJsonScripts(
            String html,
            Set<String> targetIds,
            boolean dataSaver,
            String thumbnail) {
        ParseAccumulator result = new ParseAccumulator(dataSaver);
        Matcher scripts = SCRIPT_JSON_PATTERN.matcher(html);
        while (scripts.find()) {
            String text = scripts.group(1).trim();
            if (text.startsWith("<!--")) {
                text = text.substring(4);
            }
            if (text.endsWith("-->")) {
                text = text.substring(0, text.length() - 3);
            }
            try {
                Object root = new JSONTokener(text).nextValue();
                if (root instanceof JSONObject || root instanceof JSONArray) {
                    inspectTargets(root, targetIds, dataSaver, thumbnail, result, 0);
                }
            } catch (JSONException ignored) {
                // Open Graph metadata remains as the bounded fallback for unstructured pages.
            }
        }

        return result;
    }

    private static void inspectTargets(
            Object value,
            Set<String> targetIds,
            boolean dataSaver,
            String thumbnail,
            ParseAccumulator output,
            int depth) {
        if (value == null || value == JSONObject.NULL || depth > MAX_JSON_DEPTH) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                inspectTargets(array.opt(i), targetIds, dataSaver, thumbnail, output, depth + 1);
            }
            return;
        }
        if (!(value instanceof JSONObject)) {
            return;
        }

        JSONObject object = (JSONObject) value;
        String matchedId = matchedIdentifier(object, targetIds);
        if (matchedId != null) {
            output.discoveredId = firstNonBlank(output.discoveredId, matchedId);
            if (!addTargetVideoFromObject(object, dataSaver, thumbnail, output)) {
                addMediaFromObject(object, dataSaver, thumbnail, output);
            }
            Object attachments = object.opt("attachments");
            if (attachments != null && attachments != JSONObject.NULL) {
                collectAttachmentMedia(attachments, dataSaver, thumbnail, output, depth + 1);
            }
        } else {
            addVideoFromMatchedSibling(
                    object, targetIds, dataSaver, thumbnail, output);
        }

        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (matchedId != null
                    && ("videoDeliveryLegacyFields".equals(key)
                    || "attachments".equals(key))) {
                continue;
            }
            inspectTargets(
                    object.opt(key),
                    targetIds,
                    dataSaver,
                    thumbnail,
                    output,
                    depth + 1);
        }
    }

    private static boolean addTargetVideoFromObject(
            JSONObject object,
            boolean dataSaver,
            String fallbackThumbnail,
            ParseAccumulator output) {
        return addTargetVideoFromObject(
                object,
                object.optJSONObject("videoDeliveryLegacyFields"),
                dataSaver,
                fallbackThumbnail,
                output);
    }

    private static boolean addTargetVideoFromObject(
            JSONObject object,
            JSONObject delivery,
            boolean dataSaver,
            String fallbackThumbnail,
            ParseAccumulator output) {
        String hd = firstNonBlank(
                stringField(delivery, "browser_native_hd_url"),
                stringField(delivery, "playable_url_quality_hd"),
                stringField(delivery, "hd_src"),
                stringField(object, "browser_native_hd_url"),
                stringField(object, "playable_url_quality_hd"),
                stringField(object, "hd_src"));
        String sd = firstNonBlank(
                stringField(delivery, "browser_native_sd_url"),
                stringField(delivery, "playable_url"),
                stringField(delivery, "sd_src"),
                stringField(object, "browser_native_sd_url"),
                stringField(object, "playable_url"),
                stringField(object, "sd_src"));
        String video = dataSaver ? firstNonBlank(sd, hd) : firstNonBlank(hd, sd);
        if (!isAllowedMediaUrl(video)) {
            return false;
        }
        String id = firstNonBlank(
                nonBlank(object.optString("id")),
                nonBlank(object.optString("video_id")),
                delivery == null ? null : nonBlank(delivery.optString("id")));
        int width = positiveInt(object, "width", "original_width");
        if (width <= 0) {
            width = positiveInt(delivery, "width", "original_width");
        }
        int height = positiveInt(object, "height", "original_height");
        if (height <= 0) {
            height = positiveInt(delivery, "height", "original_height");
        }
        output.add(
                id,
                new MediaItem(
                        video,
                        true,
                        videoThumbnail(object, fallbackThumbnail),
                        width,
                        height,
                        SocialPlatform.FACEBOOK,
                        "video/mp4",
                        "mp4"),
                video.equals(hd) ? 2 : 1);
        return true;
    }

    private static void addVideoFromMatchedSibling(
            JSONObject parent,
            Set<String> targetIds,
            boolean dataSaver,
            String fallbackThumbnail,
            ParseAccumulator output) {
        JSONObject delivery = parent.optJSONObject("videoDeliveryLegacyFields");
        if (delivery == null) {
            return;
        }
        Iterator<String> keys = parent.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("videoDeliveryLegacyFields".equals(key)) {
                continue;
            }
            JSONObject candidate = parent.optJSONObject(key);
            if (candidate == null) {
                continue;
            }
            String matchedId = matchedIdentifier(candidate, targetIds);
            if (matchedId != null
                    && addTargetVideoFromObject(
                            candidate,
                            delivery,
                            dataSaver,
                            fallbackThumbnail,
                            output)) {
                output.discoveredId = firstNonBlank(output.discoveredId, matchedId);
                return;
            }
        }
    }

    private static void collectAttachmentMedia(
            Object value,
            boolean dataSaver,
            String thumbnail,
            ParseAccumulator output,
            int depth) {
        if (value == null || value == JSONObject.NULL || depth > MAX_JSON_DEPTH) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectAttachmentMedia(
                        array.opt(i), dataSaver, thumbnail, output, depth + 1);
            }
            return;
        }
        if (!(value instanceof JSONObject)) {
            return;
        }

        JSONObject object = (JSONObject) value;
        boolean consolidatedVideo = addTargetVideoFromObject(
                object, dataSaver, thumbnail, output);
        if (!consolidatedVideo) {
            addMediaFromObject(object, dataSaver, thumbnail, output);
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isUnrelatedAttachmentBranch(key)) {
                continue;
            }
            if (consolidatedVideo && "videoDeliveryLegacyFields".equals(key)) {
                continue;
            }
            collectAttachmentMedia(
                    object.opt(key), dataSaver, thumbnail, output, depth + 1);
        }
    }

    private static boolean isUnrelatedAttachmentBranch(String key) {
        String value = lower(key);
        return value.contains("recommend")
                || value.contains("suggest")
                || value.contains("related")
                || value.contains("feedback")
                || value.contains("comment")
                || value.contains("reaction")
                || value.contains("tracking")
                || value.equals("actor")
                || value.equals("owner")
                || value.equals("profile");
    }

    private static void addMediaFromObject(
            JSONObject object,
            boolean dataSaver,
            String fallbackThumbnail,
            ParseAccumulator output) {
        String id = firstNonBlank(
                nonBlank(object.optString("id")),
                nonBlank(object.optString("video_id")));

        String hd = firstNonBlank(
                stringField(object, "browser_native_hd_url"),
                stringField(object, "playable_url_quality_hd"),
                stringField(object, "hd_src"));
        String sd = firstNonBlank(
                stringField(object, "browser_native_sd_url"),
                stringField(object, "playable_url"),
                stringField(object, "sd_src"));
        String video = dataSaver ? firstNonBlank(sd, hd) : firstNonBlank(hd, sd);
        if (isAllowedMediaUrl(video)) {
            output.add(
                    id,
                    new MediaItem(
                            video,
                            true,
                            videoThumbnail(object, fallbackThumbnail),
                            positiveInt(object, "width", "original_width"),
                            positiveInt(object, "height", "original_height"),
                            SocialPlatform.FACEBOOK,
                            "video/mp4",
                            "mp4"),
                    video.equals(hd) ? 2 : 1);
            return;
        }

        JSONObject photo = object.optJSONObject("photo_image");
        if (photo == null && "Photo".equalsIgnoreCase(object.optString("__typename"))) {
            photo = object.optJSONObject("image");
        }
        String originalImage = photo == null ? null : stringField(photo, "uri");
        if (isAllowedMediaUrl(originalImage)) {
            String selectedImage = dataSaver
                    ? originalImage
                    : maximizeFacebookImageUrl(originalImage);
            JSONObject viewerImage = object.optJSONObject("viewer_image");
            int width = dataSaver
                    ? firstPositiveInt(photo, viewerImage, "width")
                    : firstPositiveInt(viewerImage, photo, "width");
            int height = dataSaver
                    ? firstPositiveInt(photo, viewerImage, "height")
                    : firstPositiveInt(viewerImage, photo, "height");
            output.add(
                    id,
                    new MediaItem(
                            selectedImage,
                            false,
                            originalImage,
                            width,
                            height,
                            SocialPlatform.FACEBOOK,
                            "image/jpeg",
                            "jpg"));
        }
    }

    private static String videoThumbnail(JSONObject object, String fallback) {
        for (String key : new String[]{
                "preferred_thumbnail", "thumbnailImage", "first_frame_thumbnail"}) {
            JSONObject candidate = object.optJSONObject(key);
            String uri = stringField(candidate, "uri");
            if (uri == null && candidate != null) {
                uri = stringField(candidate.optJSONObject("image"), "uri");
            }
            if (isAllowedMediaUrl(uri)) {
                return uri;
            }
        }
        String direct = firstNonBlank(
                stringField(object, "thumbnail_url"),
                stringField(object, "image_url"));
        return isAllowedMediaUrl(direct) ? direct : fallback;
    }

    private static void copyMedia(
            String inputUrl,
            OutputStream output,
            boolean video,
            int maximumBytes) throws Exception {
        if (!isAllowedMediaUrl(inputUrl)) {
            throw new IOException("Facebook devolvió una dirección multimedia no permitida.");
        }
        String current = inputUrl;
        for (int redirect = 0; redirect <= 5; redirect++) {
            HttpURLConnection connection = null;
            try {
                connection = openConnection(current, HOME_URL, false);
                connection.setRequestProperty(
                        "Accept",
                        video
                                ? "video/mp4,*/*;q=0.2"
                                : "image/jpeg,image/jpg;q=0.9,*/*;q=0.2");
                int status = connection.getResponseCode();
                if (status == 429) {
                    throw new IOException(
                            "Facebook ha limitado temporalmente la descarga. Inténtalo más tarde.");
                }
                if (isRedirect(status)) {
                    String location = connection.getHeaderField("Location");
                    if (isBlank(location)) {
                        throw new IOException("La descarga fue redirigida sin destino.");
                    }
                    current = resolveLocation(current, location);
                    if (!isAllowedMediaUrl(current)) {
                        throw new IOException("La descarga salió de los servidores multimedia permitidos.");
                    }
                    continue;
                }
                if (status == 403 || status == 410) {
                    throw new MediaRejectedException(
                            "El enlace multimedia de Facebook ha caducado. Analiza de nuevo la publicación.");
                }
                if (status == 404) {
                    throw new MediaRejectedException(
                            "El archivo ya no está disponible en Facebook.");
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new MediaRejectedException(
                            "No se pudo descargar el archivo (HTTP " + status + ").");
                }
                String type = lower(connection.getContentType());
                boolean expectedType = video
                        ? type.startsWith("video/mp4")
                        : type.startsWith("image/jpeg") || type.startsWith("image/jpg");
                boolean genericType = type.isEmpty()
                        || type.startsWith("application/octet-stream");
                if (!expectedType && !genericType) {
                    throw new MediaRejectedException(
                            "Facebook devolvió un tipo de archivo inesperado.");
                }
                long length = connection.getContentLengthLong();
                DownloadControl.reportSize(length);
                if (maximumBytes >= 0 && length > maximumBytes) {
                    throw new IOException("La vista previa de Facebook es demasiado grande.");
                }
                try (InputStream input = connection.getInputStream()) {
                    byte[] prefix = new byte[32];
                    int prefixLength = readPrefix(input, prefix);
                    if (!hasExpectedMediaSignature(prefix, prefixLength, video, length)) {
                        throw new MediaRejectedException(
                                "Facebook devolvió un archivo multimedia inválido.");
                    }
                    if (maximumBytes >= 0 && prefixLength > maximumBytes) {
                        throw new IOException("La vista previa de Facebook es demasiado grande.");
                    }
                    output.write(prefix, 0, prefixLength);
                    long remaining = copy(
                            input,
                            output,
                            maximumBytes < 0 ? -1 : maximumBytes - prefixLength);
                    long total = prefixLength + remaining;
                    if (length >= 0L && total != length) {
                        throw new MediaRejectedException(
                                "Facebook entregó un archivo multimedia incompleto.");
                    }
                }
                return;
            } finally {
                if (connection != null) {
                    DownloadControl.release(connection);
                    connection.disconnect();
                }
            }
        }
        throw new IOException("La descarga contiene demasiadas redirecciones.");
    }

    private static HttpURLConnection openConnection(
            String url,
            String referer,
            boolean followRedirects) throws IOException {
        URL parsed = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        DownloadControl.track(connection);
        connection.setInstanceFollowRedirects(followRedirects);
        connection.setUseCaches(false);
        connection.setRequestProperty(
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
        connection.setRequestProperty("User-Agent", BROWSER_UA);
        connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
        if (SocialLinkParser.isFacebookHost(parsed.getHost())) {
            connection.setRequestProperty("Sec-Fetch-Dest", "document");
            connection.setRequestProperty("Sec-Fetch-Mode", "navigate");
            connection.setRequestProperty("Sec-Fetch-Site", fetchSiteFor(referer, url));
            connection.setRequestProperty("Sec-Fetch-User", "?1");
        }
        String safeReferer = safeReferer(referer, url);
        if (safeReferer != null) {
            connection.setRequestProperty("Referer", safeReferer);
        }
        return connection;
    }

    private static String readResponse(
            HttpURLConnection connection,
            int status,
            int maximumBytes) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        int length = connection.getContentLength();
        if (length > maximumBytes) {
            throw new IOException("La respuesta pública de Facebook es demasiado grande.");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                length > 0 ? Math.min(length, maximumBytes) : 64 * 1024);
        try (InputStream input = stream) {
            copy(input, output, maximumBytes);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static long copy(InputStream input, OutputStream output, int maximumBytes)
            throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (maximumBytes >= 0 && total + read > maximumBytes) {
                throw new IOException("La respuesta de Facebook es demasiado grande.");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static int readPrefix(InputStream input, byte[] prefix) throws IOException {
        int total = 0;
        while (total < prefix.length) {
            int read = input.read(prefix, total, prefix.length - total);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    /** Package-private for deterministic validation tests. */
    static boolean hasExpectedMediaSignature(
            byte[] prefix,
            int length,
            boolean video,
            long contentLength) {
        if (prefix == null || length < 4 || length > prefix.length) {
            return false;
        }
        if (video) {
            if (length < 16
                    || prefix[4] != 'f'
                    || prefix[5] != 't'
                    || prefix[6] != 'y'
                    || prefix[7] != 'p') {
                return false;
            }
            long boxSize = ((long) (prefix[0] & 0xFF) << 24)
                    | ((long) (prefix[1] & 0xFF) << 16)
                    | ((long) (prefix[2] & 0xFF) << 8)
                    | (long) (prefix[3] & 0xFF);
            if (boxSize < 16L || (contentLength >= 0L && boxSize > contentLength)) {
                return false;
            }
            int availableBoxBytes = (int) Math.min((long) length, boxSize);
            for (int offset = 8; offset + 3 < availableBoxBytes; offset += 4) {
                if (isVideoBrand(prefix, offset)) {
                    return true;
                }
            }
            return false;
        }
        return (contentLength < 0L || contentLength >= 32L)
                && length >= 4
                && (prefix[0] & 0xFF) == 0xFF
                && (prefix[1] & 0xFF) == 0xD8
                && (prefix[2] & 0xFF) == 0xFF
                && (prefix[3] & 0xFF) != 0x00
                && (prefix[3] & 0xFF) != 0xD8;
    }

    private static boolean isVideoBrand(byte[] value, int offset) {
        String brand = new String(value, offset, 4, StandardCharsets.US_ASCII);
        return brand.equals("isom")
                || brand.equals("iso2")
                || brand.equals("avc1")
                || brand.equals("mp41")
                || brand.equals("mp42")
                || brand.equals("M4V ")
                || brand.equals("M4VH")
                || brand.equals("dash")
                || brand.startsWith("3gp");
    }

    private static String findMetaContent(String html, String attribute, String expected) {
        Matcher tags = META_TAG_PATTERN.matcher(html);
        while (tags.find()) {
            Map<String, String> attributes = new LinkedHashMap<>();
            Matcher values = ATTRIBUTE_PATTERN.matcher(tags.group());
            while (values.find()) {
                attributes.put(lower(values.group(1)), decodeHtml(values.group(3)));
            }
            if (expected.equalsIgnoreCase(attributes.get(lower(attribute)))) {
                return nonBlank(attributes.get("content"));
            }
        }
        return null;
    }

    private static void collectIdsFromUrl(String value, Set<String> output) {
        if (isBlank(value)) {
            return;
        }
        String path;
        try {
            path = new URI(value).getPath();
        } catch (URISyntaxException ignored) {
            return;
        }
        if (path == null) {
            path = "";
        }
        for (Pattern pattern : new Pattern[]{
                PATH_REEL_PATTERN,
                PATH_VIDEO_PATTERN,
                PATH_POST_PATTERN,
                PATH_PERMALINK_PATTERN,
                PATH_PHOTO_PATTERN}) {
            Matcher matcher = pattern.matcher(path);
            if (matcher.find()) {
                output.add(matcher.group(1));
                return;
            }
        }
        for (String name : new String[]{"v", "fbid", "story_fbid"}) {
            String parameter = queryParameter(value, name);
            if (!isBlank(parameter) && parameter.matches("[A-Za-z0-9_-]{5,80}")) {
                output.add(parameter);
                return;
            }
        }
    }

    private static LinkedHashSet<String> idsFromUrl(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectIdsFromUrl(value, result);
        return result;
    }

    private static boolean identitiesConflict(Set<String> first, Set<String> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        for (String value : first) {
            if (second.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static String queryParameter(String value, String expectedName) {
        try {
            URI uri = new URI(value);
            String query = uri.getRawQuery();
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String name = equals >= 0 ? pair.substring(0, equals) : pair;
                if (expectedName.equals(URLDecoder.decode(name, "UTF-8"))) {
                    String raw = equals >= 0 ? pair.substring(equals + 1) : "";
                    return URLDecoder.decode(raw, "UTF-8");
                }
            }
        } catch (Exception ignored) {
            // La validación principal dará un mensaje claro si el enlace es inválido.
        }
        return null;
    }

    private static String stringField(JSONObject object, String key) {
        return object == null ? null : decodeHtml(nonBlank(object.optString(key)));
    }

    private static String matchedIdentifier(JSONObject object, Set<String> targetIds) {
        for (String key : new String[]{"id", "video_id", "post_id", "story_fbid"}) {
            String value = nonBlank(object.optString(key));
            if (value != null && targetIds.contains(value)) {
                return value;
            }
        }
        return null;
    }

    private static int positiveInt(JSONObject object, String... keys) {
        if (object == null) {
            return 0;
        }
        for (String key : keys) {
            long value = object.optLong(key, 0L);
            if (value > 0) {
                return (int) Math.min(Integer.MAX_VALUE, value);
            }
        }
        return 0;
    }

    private static int firstPositiveInt(
            JSONObject preferred,
            JSONObject fallback,
            String key) {
        int value = positiveInt(preferred, key);
        return value > 0 ? value : positiveInt(fallback, key);
    }

    private static boolean isSafeFacebookUrl(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && SocialLinkParser.isFacebookHost(uri.getHost());
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static void ensureFacebookPageUrl(String value) throws IOException {
        if (!isSafeFacebookUrl(value)) {
            throw new IOException("El enlace salió de los dominios oficiales de Facebook.");
        }
    }

    private static boolean isAllowedMediaUrl(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = new URI(decodeHtml(value));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            String host = lower(uri.getHost());
            return host.equals("fbcdn.net")
                    || host.endsWith(".fbcdn.net")
                    || host.equals("fbsbx.com")
                    || host.endsWith(".fbsbx.com");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    /** Removes only Facebook's presentation-size hint while preserving its signed parameters. */
    private static String maximizeFacebookImageUrl(String value) {
        if (isBlank(value)) {
            return value;
        }
        int fragmentPosition = value.indexOf('#');
        String fragment = fragmentPosition >= 0 ? value.substring(fragmentPosition) : "";
        String withoutFragment = fragmentPosition >= 0 ? value.substring(0, fragmentPosition) : value;
        int queryPosition = withoutFragment.indexOf('?');
        if (queryPosition < 0) {
            return value;
        }
        String base = withoutFragment.substring(0, queryPosition);
        String query = withoutFragment.substring(queryPosition + 1);
        String[] pairs = query.split("&");
        boolean hasFacebookSizeContract = false;
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String rawName = equals >= 0 ? pair.substring(0, equals) : pair;
            String name;
            try {
                name = URLDecoder.decode(rawName, "UTF-8");
            } catch (Exception ignored) {
                name = rawName;
            }
            if ("cstp".equalsIgnoreCase(name)) {
                hasFacebookSizeContract = true;
                break;
            }
        }
        if (!hasFacebookSizeContract) {
            return value;
        }
        StringBuilder kept = new StringBuilder();
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String rawName = equals >= 0 ? pair.substring(0, equals) : pair;
            String name;
            try {
                name = URLDecoder.decode(rawName, "UTF-8");
            } catch (Exception ignored) {
                name = rawName;
            }
            if ("ctp".equalsIgnoreCase(name)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(pair);
        }
        return base + (kept.length() == 0 ? "" : "?" + kept) + fragment;
    }

    private static boolean isStoryUrl(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String path = lower(uri.getPath());
            if (path.equals("/stories")
                    || path.startsWith("/stories/")
                    || path.startsWith("/share/s/")) {
                return true;
            }
            if (path.equals("/login.php") || path.startsWith("/login/")) {
                String next = queryParameter(value, "next");
                if (!isBlank(next)) {
                    try {
                        String nextPath = lower(new URI(next).getPath());
                        return nextPath.equals("/stories") || nextPath.startsWith("/stories/");
                    } catch (URISyntaxException ignored) {
                        return lower(next).startsWith("/stories/");
                    }
                }
            }
            return false;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static boolean isLikelyVideoUrl(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String path = lower(uri.getPath());
            return path.startsWith("/reel/")
                    || path.startsWith("/reels/")
                    || path.contains("/videos/")
                    || path.equals("/video.php")
                    || path.equals("/watch")
                    || path.startsWith("/watch/")
                    || path.startsWith("/share/r/")
                    || path.startsWith("/share/v/");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static boolean looksRateLimited(String html) {
        String lower = lower(html);
        return lower.contains("rate limit")
                || lower.contains("temporarily blocked")
                || lower.contains("please try again later");
    }

    private static boolean looksLoginRequired(String html) {
        String lower = lower(html);
        return lower.contains("you must log in")
                || lower.contains("inicia sesión para")
                || lower.contains("log in to continue")
                || lower.contains("content isn't available")
                || lower.contains("contenido no disponible");
    }

    private static boolean isLoginUrl(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            String path = lower(new URI(value).getPath());
            return path.equals("/login.php") || path.startsWith("/login/");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    /** Package-private for deterministic Fetch Metadata tests. */
    static String fetchSiteFor(String referer, String destination) {
        if (isBlank(referer)) {
            return "none";
        }
        try {
            URI from = new URI(referer);
            URI to = new URI(destination);
            if (lower(from.getScheme()).equals(lower(to.getScheme()))
                    && lower(from.getHost()).equals(lower(to.getHost()))
                    && effectivePort(from) == effectivePort(to)) {
                return "same-origin";
            }
            if (facebookSite(from.getHost()).equals(facebookSite(to.getHost()))) {
                return "same-site";
            }
        } catch (URISyntaxException ignored) {
            // Treat an unparseable referrer conservatively as a cross-site navigation.
        }
        return "cross-site";
    }

    /** Package-private for deterministic referrer-policy tests. */
    static String safeReferer(String referer, String destination) {
        if (isBlank(referer) || isBlank(destination)) {
            return null;
        }
        try {
            URI from = new URI(referer);
            URI to = new URI(destination);
            if (!"https".equalsIgnoreCase(from.getScheme()) || from.getHost() == null) {
                return null;
            }
            boolean sameOrigin = lower(from.getScheme()).equals(lower(to.getScheme()))
                    && lower(from.getHost()).equals(lower(to.getHost()))
                    && effectivePort(from) == effectivePort(to);
            if (sameOrigin) {
                return new URI(
                        from.getScheme(),
                        null,
                        from.getHost(),
                        from.getPort(),
                        from.getPath(),
                        from.getQuery(),
                        null).toASCIIString();
            }
            if (facebookSite(from.getHost()).equals(facebookSite(to.getHost()))) {
                return originOnly(from);
            }
        } catch (URISyntaxException ignored) {
            // An invalid referrer is omitted rather than forwarded.
        }
        return null;
    }

    private static String originOnly(URI uri) {
        StringBuilder value = new StringBuilder("https://").append(uri.getHost());
        if (uri.getPort() >= 0 && uri.getPort() != 443) {
            value.append(':').append(uri.getPort());
        }
        return value.append('/').toString();
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String facebookSite(String host) {
        String value = lower(host);
        if (value.equals("facebook.com") || value.endsWith(".facebook.com")) {
            return "facebook.com";
        }
        if (value.equals("fb.watch") || value.endsWith(".fb.watch")) {
            return "fb.watch";
        }
        return value;
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307
                || status == 308;
    }

    private static String resolveLocation(String base, String location) throws IOException {
        try {
            return new URI(base).resolve(location).toString();
        } catch (URISyntaxException invalid) {
            throw new IOException("Facebook devolvió una redirección inválida.", invalid);
        }
    }

    private static String firstTargetId(Set<String> ids) {
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    private static String safeLastSegment(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            String path = new URI(value).getPath();
            if (path == null) {
                return null;
            }
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String safe = parts[i].replaceAll("[^A-Za-z0-9_-]", "");
                if (!safe.isEmpty() && !safe.equalsIgnoreCase("share")) {
                    return safe.length() > 64 ? safe.substring(0, 64) : safe;
                }
            }
        } catch (URISyntaxException ignored) {
            return null;
        }
        return null;
    }

    private static String nonBlank(String value) {
        return isBlank(value) || "null".equalsIgnoreCase(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String candidate = nonBlank(value);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String decodeHtml(String value) {
        return value == null ? null : value
                .replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&#x26;", "&")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static final class Page {
        final String responseUrl;
        final String html;

        Page(String responseUrl, String html) {
            this.responseUrl = responseUrl;
            this.html = html;
        }
    }

    private static final class ParseAccumulator {
        final LinkedHashMap<String, MediaItem> items = new LinkedHashMap<>();
        final Map<String, Long> qualityRanks = new LinkedHashMap<>();
        final boolean dataSaver;
        String discoveredId;

        ParseAccumulator(boolean dataSaver) {
            this.dataSaver = dataSaver;
        }

        void add(String id, MediaItem item) {
            add(id, item, pixelCount(item));
        }

        void add(String id, MediaItem item, long qualityRank) {
            for (MediaItem existing : items.values()) {
                if (existing.isVideo() == item.isVideo()
                        && existing.getUrl().equals(item.getUrl())) {
                    return;
                }
            }
            String key = nonBlank(id);
            if (key == null) {
                try {
                    URI uri = new URI(item.getUrl());
                    String discriminator = queryParameter(item.getUrl(), "oh");
                    if (discriminator == null) {
                        discriminator = uri.getRawQuery();
                    }
                    key = (item.isVideo() ? "video:" : "image:")
                            + uri.getPath()
                            + (discriminator == null ? "" : "?" + discriminator);
                } catch (URISyntaxException ignored) {
                    key = (item.isVideo() ? "video:" : "image:") + item.getUrl();
                }
            } else {
                key = (item.isVideo() ? "video:" : "image:") + key;
            }
            MediaItem existing = items.get(key);
            if (existing == null) {
                items.put(key, item);
                qualityRanks.put(key, qualityRank);
                return;
            }
            long existingRank = qualityRanks.containsKey(key) ? qualityRanks.get(key) : 0L;
            if (isBetterQuality(qualityRank, existingRank, dataSaver)) {
                items.put(key, item);
                qualityRanks.put(key, qualityRank);
            }
        }

        private static long pixelCount(MediaItem item) {
            return (long) item.getWidth() * (long) item.getHeight();
        }

        private static boolean isBetterQuality(
                long candidateRank,
                long existingRank,
                boolean preferSmaller) {
            if (candidateRank <= 0L) {
                return false;
            }
            if (existingRank <= 0L) {
                return true;
            }
            return preferSmaller
                    ? candidateRank < existingRank
                    : candidateRank > existingRank;
        }
    }

    private static final class MediaRejectedException extends IOException {
        private static final long serialVersionUID = 1L;

        MediaRejectedException(String message) {
            super(message);
        }
    }
}
