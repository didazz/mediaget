// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Descarga Social contributors
// The public-page fallback is a Java reimplementation derived from
// InstaDownload 2.6.0 by Orang-Studio, licensed under GPL-3.0-or-later.
package com.didazz.descargasocial;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts public Instagram publication media entirely on the Android device.
 *
 * <p>No account, WebView, application server or third-party extraction API is
 * used. Instagram can change its public responses without notice, so callers
 * should display the Spanish exception messages returned by this class.</p>
 */
public final class InstagramExtractor {
    private static final String HOME_URL = "https://www.instagram.com/";
    private static final String GRAPHQL_URL = "https://www.instagram.com/graphql/query";
    private static final String GRAPHQL_DOC_ID = "27128499623469141";
    private static final String WEB_APP_ID = "936619743392459";
    private static final String POLARIS_FLAG =
            "__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider";

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36";
    private static final String GOOGLEBOT_UA =
            "Googlebot/2.1 (+http://www.google.com/bot.html)";

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_SHARE_REDIRECTS = 6;
    private static final int MAX_JSON_DEPTH = 80;
    private static final int MAX_PREVIEW_BYTES = 32 * 1024 * 1024;

    private static final Pattern DIRECT_URL_PATTERN = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?(?:instagram\\.com|instagr\\.am)/"
                    + "(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_SJS_PATTERN = Pattern.compile(
            "<script\\b[^>]*\\bdata-sjs(?:=[^ >]+)?[^>]*>(.*?)</script\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_TAG_PATTERN = Pattern.compile(
            "<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_PATTERN = Pattern.compile(
            "\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL_CANONICAL_PATTERN = Pattern.compile(
            "\\brel\\s*=\\s*[\"'][^\"']*\\bcanonical\\b[^\"']*[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CSRF_BODY_PATTERN = Pattern.compile(
            "[\"']csrf_token[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    private static final Object COOKIE_LOCK = new Object();
    private static final Map<String, String> COOKIES = new LinkedHashMap<>();

    private InstagramExtractor() {
    }

    /** Extracts every image/video from a public post, Reel, video or carousel. */
    public static List<MediaItem> getMediaItems(String url, boolean dataSaver) throws Exception {
        String shortcode = extractShortcode(url);
        if (shortcode == null && isShareUrl(url)) {
            try {
                shortcode = resolveSharedShortcode(url.trim());
            } catch (ExtractorFailure failure) {
                if (failure.kind == FailureKind.RATE_LIMIT) {
                    throw new IOException(
                            "Instagram ha limitado temporalmente las consultas. "
                                    + "Espera unos minutos y vuelve a intentarlo.");
                }
                if (failure.kind == FailureKind.NETWORK) {
                    throw new IOException(
                            "No se pudo conectar con Instagram. Comprueba Internet y vuelve a intentarlo.",
                            failure);
                }
                if (failure.kind == FailureKind.FORMAT) {
                    throw new IOException(
                            "Instagram ha cambiado el formato del enlace compartido. "
                                    + "Es necesario actualizar la aplicación.");
                }
                throw new IOException(
                        "Instagram no ofrece esta publicación de forma pública. "
                                + "Puede haberse eliminado, estar restringida o requerir acceso.");
            }
        }
        if (shortcode == null) {
            throw new IllegalArgumentException(
                    "Usa el enlace de una publicación, un Reel o un vídeo de Instagram.");
        }

        ExtractorFailure graphFailure = null;
        try {
            List<MediaItem> items = fetchWithGraphQl(shortcode, dataSaver);
            if (!items.isEmpty()) {
                return items;
            }
        } catch (ExtractorFailure failure) {
            graphFailure = failure;
        }

        ExtractorFailure pageFailure = null;
        try {
            List<MediaItem> items = fetchFromPublicPage(shortcode, dataSaver);
            if (!items.isEmpty()) {
                return items;
            }
        } catch (ExtractorFailure failure) {
            pageFailure = failure;
        }

        if (hasKind(graphFailure, FailureKind.RATE_LIMIT)
                || hasKind(pageFailure, FailureKind.RATE_LIMIT)) {
            throw new IOException(
                    "Instagram ha limitado temporalmente las consultas. "
                            + "Espera unos minutos y vuelve a intentarlo.");
        }
        if (hasKind(graphFailure, FailureKind.NETWORK)
                || hasKind(pageFailure, FailureKind.NETWORK)) {
            throw new IOException(
                    "No se pudo conectar con Instagram. Comprueba Internet y vuelve a intentarlo.",
                    hasKind(graphFailure, FailureKind.NETWORK) ? graphFailure : pageFailure);
        }
        if (hasKind(graphFailure, FailureKind.UNAVAILABLE)
                || hasKind(pageFailure, FailureKind.UNAVAILABLE)) {
            throw new IOException(
                    "Instagram no ofrece esta publicación de forma pública. "
                            + "Puede haberse eliminado, estar restringida o requerir acceso.");
        }
        if (hasKind(graphFailure, FailureKind.FORMAT)
                || hasKind(pageFailure, FailureKind.FORMAT)) {
            throw new IOException(
                    "Instagram ha cambiado el formato de la publicación. "
                            + "Es necesario actualizar la aplicación.");
        }
        throw new IOException(
                "Instagram no ofrece esta publicación de forma pública. "
                        + "Puede haberse eliminado, estar restringida o requerir acceso.");
    }

    /** Convenience overload which always selects the highest available resolution. */
    public static List<MediaItem> getMediaItems(String url) throws Exception {
        return getMediaItems(url, false);
    }

    /** Downloads an extracted media URL to an already-open stream. */
    public static void downloadToStream(String url, OutputStream out) throws Exception {
        if (out == null) {
            throw new IllegalArgumentException("El destino de la descarga no puede estar vacío.");
        }
        HttpURLConnection connection = openMediaConnection(url);
        try {
            int status = connection.getResponseCode();
            if (status == 429) {
                throw new IOException(
                        "Instagram ha limitado temporalmente la descarga. Inténtalo más tarde.");
            }
            if (status == 403 || status == 410) {
                throw new IOException(
                        "El enlace multimedia ha caducado. Analiza de nuevo la publicación.");
            }
            if (status == 404) {
                throw new IOException("El archivo ya no está disponible en Instagram.");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("No se pudo descargar el archivo (HTTP " + status + ").");
            }
            long announced = connection.getContentLengthLong();
            DownloadControl.reportSize(announced);
            try (InputStream input = connection.getInputStream()) {
                long copied = copy(input, out, -1);
                String encoding = connection.getContentEncoding();
                if (announced >= 0 && (encoding == null || "identity".equalsIgnoreCase(encoding))
                        && copied != announced) {
                    throw new java.io.EOFException("El archivo llegó incompleto.");
                }
            }
        } finally {
            DownloadControl.release(connection);
            connection.disconnect();
        }
    }

    /** Downloads a preview image using Instagram-compatible request headers. */
    public static byte[] fetchBytes(String url) throws Exception {
        HttpURLConnection connection = openMediaConnection(url);
        try {
            int status = connection.getResponseCode();
            if (status == 429) {
                throw new IOException(
                        "Instagram ha limitado temporalmente la vista previa. Inténtalo más tarde.");
            }
            if (status == 403 || status == 410) {
                throw new IOException(
                        "La vista previa ha caducado. Analiza de nuevo la publicación.");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("No se pudo cargar la vista previa (HTTP " + status + ").");
            }
            int announcedLength = connection.getContentLength();
            if (announcedLength > MAX_PREVIEW_BYTES) {
                throw new IOException("La vista previa es demasiado grande.");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    announcedLength > 0 ? Math.min(announcedLength, MAX_PREVIEW_BYTES) : 32_768);
            try (InputStream input = connection.getInputStream()) {
                copy(input, output, MAX_PREVIEW_BYTES);
            }
            return output.toByteArray();
        } finally {
            DownloadControl.release(connection);
            connection.disconnect();
        }
    }

    /** Returns the shortcode for a direct Instagram URL, or {@code null}. */
    public static String extractShortcode(String url) {
        if (url == null) {
            return null;
        }
        String value = url.trim();
        Matcher matcher = DIRECT_URL_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        String shortcode = matcher.group(1);
        return shortcode == null || shortcode.length() > 32 ? null : shortcode;
    }

    private static List<MediaItem> fetchWithGraphQl(String shortcode, boolean dataSaver)
            throws ExtractorFailure {
        String csrf = bootstrapCsrf();
        JSONObject variables = new JSONObject();
        try {
            variables.put("shortcode", shortcode);
            variables.put(POLARIS_FLAG, false);
        } catch (JSONException impossible) {
            throw new ExtractorFailure(FailureKind.FORMAT, "No se pudo crear la consulta.", impossible);
        }

        String body;
        try {
            body = "variables=" + URLEncoder.encode(variables.toString(), "UTF-8")
                    + "&doc_id=" + URLEncoder.encode(GRAPHQL_DOC_ID, "UTF-8")
                    + "&server_timestamps=true";
        } catch (Exception encodingFailure) {
            throw new ExtractorFailure(
                    FailureKind.FORMAT, "No se pudo codificar la consulta.", encodingFailure);
        }

        HttpURLConnection connection = null;
        try {
            connection = openConnection(GRAPHQL_URL, "POST", BROWSER_UA, HOME_URL, true);
            connection.setRequestProperty(
                    "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("x-ig-app-id", WEB_APP_ID);
            connection.setRequestProperty("x-csrftoken", csrf);
            connection.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int status = connection.getResponseCode();
            captureCookies(connection);
            String text = readResponse(connection, status);
            if (status == 429) {
                throw new ExtractorFailure(FailureKind.RATE_LIMIT, "GraphQL HTTP 429");
            }
            if (status == 401 || status == 403 || status == 404) {
                throw new ExtractorFailure(
                        FailureKind.UNAVAILABLE, "GraphQL HTTP " + status);
            }
            if (status < 200 || status >= 300) {
                throw new ExtractorFailure(
                        FailureKind.UNAVAILABLE, "GraphQL HTTP " + status);
            }
            if (looksLikeHtml(text)) {
                throw new ExtractorFailure(
                        FailureKind.UNAVAILABLE, "Instagram devolvió una página de acceso.");
            }

            JSONObject root;
            try {
                root = new JSONObject(text);
            } catch (JSONException invalidJson) {
                throw new ExtractorFailure(
                        FailureKind.FORMAT, "GraphQL devolvió un formato desconocido.", invalidJson);
            }

            if (containsRateLimit(root)) {
                throw new ExtractorFailure(FailureKind.RATE_LIMIT, "Límite indicado por GraphQL.");
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null && root.optJSONArray("errors") != null) {
                throw new ExtractorFailure(
                        FailureKind.UNAVAILABLE, "La publicación no está disponible.");
            }
            JSONObject webInfo = data == null
                    ? null
                    : data.optJSONObject("xdt_api__v1__media__shortcode__web_info");
            JSONArray returnedItems = webInfo == null ? null : webInfo.optJSONArray("items");
            if (returnedItems != null && returnedItems.length() == 0) {
                throw new ExtractorFailure(
                        FailureKind.UNAVAILABLE, "La publicación no está disponible.");
            }
            JSONObject product = graphQlProduct(root, shortcode);
            if (product == null) {
                if (containsUnavailableMarker(text)) {
                    throw new ExtractorFailure(
                            FailureKind.UNAVAILABLE, "La publicación no está disponible.");
                }
                throw new ExtractorFailure(
                        FailureKind.FORMAT, "GraphQL no incluyó el contenido esperado.");
            }
            List<MediaItem> result = extractProductMedia(product, dataSaver);
            if (result.isEmpty()) {
                throw new ExtractorFailure(
                        FailureKind.FORMAT, "GraphQL no incluyó archivos multimedia.");
            }
            return result;
        } catch (ExtractorFailure failure) {
            throw failure;
        } catch (IOException networkFailure) {
            throw new ExtractorFailure(
                    FailureKind.NETWORK, "No se pudo consultar Instagram.", networkFailure);
        } finally {
            if (connection != null) {
                DownloadControl.release(connection);
                connection.disconnect();
            }
        }
    }

    private static String bootstrapCsrf() throws ExtractorFailure {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(HOME_URL, "GET", BROWSER_UA, null, true);
            int status = connection.getResponseCode();
            captureCookies(connection);
            String text = readResponse(connection, status);
            if (status == 429) {
                throw new ExtractorFailure(FailureKind.RATE_LIMIT, "Inicio HTTP 429");
            }
            if (status < 200 || status >= 400) {
                throw new ExtractorFailure(
                        FailureKind.UNAVAILABLE, "Inicio HTTP " + status);
            }

            String csrf;
            synchronized (COOKIE_LOCK) {
                csrf = COOKIES.get("csrftoken");
            }
            if (isBlank(csrf)) {
                Matcher bodyToken = CSRF_BODY_PATTERN.matcher(text);
                if (bodyToken.find()) {
                    csrf = bodyToken.group(1);
                    synchronized (COOKIE_LOCK) {
                        COOKIES.put("csrftoken", csrf);
                    }
                }
            }
            if (isBlank(csrf)) {
                throw new ExtractorFailure(
                        FailureKind.FORMAT, "Instagram no entregó el token de acceso público.");
            }
            return csrf;
        } catch (ExtractorFailure failure) {
            throw failure;
        } catch (IOException networkFailure) {
            throw new ExtractorFailure(
                    FailureKind.NETWORK, "No se pudo iniciar la consulta pública.", networkFailure);
        } finally {
            if (connection != null) {
                DownloadControl.release(connection);
                connection.disconnect();
            }
        }
    }

    private static JSONObject graphQlProduct(JSONObject root, String shortcode) {
        JSONObject data = root.optJSONObject("data");
        JSONObject webInfo = data == null
                ? null
                : data.optJSONObject("xdt_api__v1__media__shortcode__web_info");
        JSONArray items = webInfo == null ? null : webInfo.optJSONArray("items");
        JSONObject direct = items == null ? null : items.optJSONObject(0);
        if (direct != null && hasMedia(direct)) {
            return direct;
        }

        JSONObject legacy = data == null ? null : data.optJSONObject("xdt_shortcode_media");
        if (legacy != null && hasMedia(legacy)) {
            return legacy;
        }
        return findMediaProduct(root, shortcode, shortcodeToMediaId(shortcode), 0);
    }

    private static List<MediaItem> fetchFromPublicPage(String shortcode, boolean dataSaver)
            throws ExtractorFailure {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(
                    "https://www.instagram.com/p/" + shortcode + "/",
                    "GET", GOOGLEBOT_UA, HOME_URL, true);
            int status = connection.getResponseCode();
            captureCookies(connection);
            String html = readResponse(connection, status);
            if (status == 429) {
                throw new ExtractorFailure(FailureKind.RATE_LIMIT, "Página HTTP 429");
            }
            if (status == 401 || status == 403 || status == 404 || status == 410) {
                throw new ExtractorFailure(FailureKind.UNAVAILABLE, "Página HTTP " + status);
            }
            if (status < 200 || status >= 300) {
                throw new ExtractorFailure(FailureKind.UNAVAILABLE, "Página HTTP " + status);
            }

            String mediaId = shortcodeToMediaId(shortcode);
            Matcher matcher = DATA_SJS_PATTERN.matcher(html);
            boolean foundStructuredScript = false;
            while (matcher.find()) {
                String script = matcher.group(1).trim();
                if (script.isEmpty() || script.charAt(0) != '{') {
                    continue;
                }
                JSONObject scriptRoot;
                try {
                    scriptRoot = new JSONObject(script);
                } catch (JSONException ignored) {
                    continue;
                }
                foundStructuredScript = true;
                JSONObject product = findMediaProduct(scriptRoot, shortcode, mediaId, 0);
                if (product == null) {
                    continue;
                }
                List<MediaItem> items = extractProductMedia(product, dataSaver);
                if (!items.isEmpty()) {
                    return items;
                }
            }

            if (!foundStructuredScript && containsUnavailableMarker(html)) {
                throw new ExtractorFailure(
                        FailureKind.UNAVAILABLE, "Instagram solicita acceso para esta publicación.");
            }
            throw new ExtractorFailure(
                    FailureKind.FORMAT, "La página no contiene el formato público esperado.");
        } catch (ExtractorFailure failure) {
            throw failure;
        } catch (IOException networkFailure) {
            throw new ExtractorFailure(
                    FailureKind.NETWORK, "No se pudo abrir la publicación.", networkFailure);
        } finally {
            if (connection != null) {
                DownloadControl.release(connection);
                connection.disconnect();
            }
        }
    }

    private static JSONObject findMediaProduct(
            Object value, String shortcode, String mediaId, int depth) {
        if (value == null || value == JSONObject.NULL || depth > MAX_JSON_DEPTH) {
            return null;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;

            JSONObject publicProduct = object.optJSONObject("if_not_gated_logged_out");
            if (publicProduct != null) {
                if (hasMedia(publicProduct)
                        && (matchesProduct(publicProduct, shortcode, mediaId)
                        || !hasIdentity(publicProduct))) {
                    return publicProduct;
                }
                JSONObject nestedPublic = findMediaProduct(
                        publicProduct, shortcode, mediaId, depth + 1);
                if (nestedPublic != null) {
                    return nestedPublic;
                }
            }

            if (hasMedia(object) && matchesProduct(object, shortcode, mediaId)) {
                return object;
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("if_not_gated_logged_out".equals(key)) {
                    continue;
                }
                JSONObject found = findMediaProduct(
                        object.opt(key), shortcode, mediaId, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findMediaProduct(
                        array.opt(i), shortcode, mediaId, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean matchesProduct(JSONObject product, String shortcode, String mediaId) {
        return shortcode.equals(product.optString("code"))
                || shortcode.equals(product.optString("shortcode"))
                || mediaId.equals(product.optString("pk"))
                || mediaId.equals(product.optString("id"));
    }

    private static boolean hasIdentity(JSONObject product) {
        return !isBlank(product.optString("code"))
                || !isBlank(product.optString("shortcode"))
                || !isBlank(product.optString("pk"))
                || !isBlank(product.optString("id"));
    }

    private static boolean hasMedia(JSONObject product) {
        return product.has("video_versions")
                || product.has("carousel_media")
                || product.has("image_versions2")
                || product.has("display_url")
                || product.has("video_url")
                || product.has("edge_sidecar_to_children");
    }

    private static List<MediaItem> extractProductMedia(JSONObject product, boolean dataSaver) {
        JSONArray carousel = product.optJSONArray("carousel_media");
        if (carousel != null && carousel.length() > 0) {
            List<MediaItem> result = new ArrayList<>();
            for (int i = 0; i < carousel.length(); i++) {
                JSONObject child = carousel.optJSONObject(i);
                MediaItem item = child == null ? null : extractSingleItem(child, dataSaver);
                if (item != null) {
                    result.add(item);
                }
            }
            return withoutDuplicateUrls(result);
        }

        JSONObject sidecar = product.optJSONObject("edge_sidecar_to_children");
        JSONArray edges = sidecar == null ? null : sidecar.optJSONArray("edges");
        if (edges != null && edges.length() > 0) {
            List<MediaItem> result = new ArrayList<>();
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.optJSONObject(i);
                JSONObject node = edge == null ? null : edge.optJSONObject("node");
                MediaItem item = node == null ? null : extractSingleItem(node, dataSaver);
                if (item != null) {
                    result.add(item);
                }
            }
            return withoutDuplicateUrls(result);
        }

        MediaItem single = extractSingleItem(product, dataSaver);
        if (single == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(single);
    }

    private static MediaItem extractSingleItem(JSONObject item, boolean dataSaver) {
        JSONArray imageCandidates = null;
        JSONObject imageVersions = item.optJSONObject("image_versions2");
        if (imageVersions != null) {
            imageCandidates = imageVersions.optJSONArray("candidates");
        }
        if (imageCandidates == null) {
            imageCandidates = item.optJSONArray("display_resources");
        }
        Candidate poster = pickCandidate(imageCandidates, false);
        if (poster == null) {
            String displayUrl = nonBlank(item.optString("display_url"));
            if (displayUrl != null) {
                poster = new Candidate(displayUrl, positiveInt(item, "width"),
                        positiveInt(item, "height"));
            }
        }

        Candidate video = pickCandidate(item.optJSONArray("video_versions"), dataSaver);
        if (video == null) {
            String videoUrl = nonBlank(item.optString("video_url"));
            if (videoUrl != null) {
                video = new Candidate(videoUrl, positiveInt(item, "width"),
                        positiveInt(item, "height"));
            }
        }
        if (video != null) {
            return new MediaItem(video.url, true, poster == null ? null : poster.url,
                    video.width, video.height);
        }

        Candidate image = pickCandidate(imageCandidates, dataSaver);
        if (image == null) {
            image = poster;
        }
        return image == null
                ? null
                : new MediaItem(image.url, false, image.url, image.width, image.height);
    }

    private static Candidate pickCandidate(JSONArray values, boolean dataSaver) {
        if (values == null || values.length() == 0) {
            return null;
        }
        Candidate selected = null;
        long selectedArea = 0;
        int selectedIndex = -1;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) {
                continue;
            }
            String url = nonBlank(value.optString("url"));
            if (url == null) {
                url = nonBlank(value.optString("src"));
            }
            if (url == null) {
                continue;
            }
            int width = positiveInt(value, "width");
            int height = positiveInt(value, "height");
            long area = (long) width * (long) height;
            Candidate candidate = new Candidate(url, width, height);
            if (selected == null
                    || (dataSaver && (area < selectedArea
                    || (area == selectedArea && i > selectedIndex)))
                    || (!dataSaver && area > selectedArea)) {
                selected = candidate;
                selectedArea = area;
                selectedIndex = i;
            }
        }
        return selected;
    }

    private static List<MediaItem> withoutDuplicateUrls(List<MediaItem> items) {
        if (items.size() < 2) {
            return items;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<MediaItem> unique = new ArrayList<>();
        for (MediaItem item : items) {
            if (seen.add(item.getUrl())) {
                unique.add(item);
            }
        }
        return unique;
    }

    private static String resolveSharedShortcode(String inputUrl) throws ExtractorFailure {
        String current = inputUrl;
        String lastHtml = null;
        for (int redirect = 0; redirect <= MAX_SHARE_REDIRECTS; redirect++) {
            ensureInstagramUrl(current);
            HttpURLConnection connection = null;
            try {
                connection = openConnection(current, "GET", BROWSER_UA, HOME_URL, false);
                int status = connection.getResponseCode();
                captureCookies(connection);
                if (status == 429) {
                    throw new ExtractorFailure(FailureKind.RATE_LIMIT, "Enlace compartido HTTP 429");
                }
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (isBlank(location)) {
                        throw new ExtractorFailure(
                                FailureKind.FORMAT, "La redirección compartida no tiene destino.");
                    }
                    current = resolveLocation(current, location);
                    String direct = extractShortcode(current);
                    if (direct != null) {
                        return direct;
                    }
                    continue;
                }
                lastHtml = readResponse(connection, status);
                if (status == 401 || status == 403 || status == 404 || status == 410) {
                    throw new ExtractorFailure(
                            FailureKind.UNAVAILABLE, "Enlace compartido HTTP " + status);
                }
                if (status < 200 || status >= 300) {
                    throw new ExtractorFailure(
                            FailureKind.UNAVAILABLE, "Enlace compartido HTTP " + status);
                }
                String direct = extractShortcode(connection.getURL().toString());
                if (direct != null) {
                    return direct;
                }
                String canonical = findCanonicalUrl(lastHtml);
                direct = extractShortcode(canonical);
                if (direct != null) {
                    return direct;
                }
                String unescaped = lastHtml.replace("\\/", "/")
                        .replace("\\u0026", "&")
                        .replace("&amp;", "&");
                Matcher embedded = Pattern.compile(
                        "https?://(?:www\\.|m\\.)?(?:instagram\\.com|instagr\\.am)/"
                                + "(?:p|reel|reels|tv)/[A-Za-z0-9_-]+[^\"'<>\\s]*",
                        Pattern.CASE_INSENSITIVE).matcher(unescaped);
                while (embedded.find()) {
                    direct = extractShortcode(embedded.group());
                    if (direct != null) {
                        return direct;
                    }
                }
                break;
            } catch (ExtractorFailure failure) {
                throw failure;
            } catch (IOException networkFailure) {
                throw new ExtractorFailure(
                        FailureKind.NETWORK,
                        "No se pudo resolver el enlace compartido.", networkFailure);
            } finally {
                if (connection != null) {
                    DownloadControl.release(connection);
                    connection.disconnect();
                }
            }
        }
        if (lastHtml != null && containsUnavailableMarker(lastHtml)) {
            throw new ExtractorFailure(
                    FailureKind.UNAVAILABLE, "El enlace compartido no está disponible.");
        }
        throw new ExtractorFailure(
                FailureKind.FORMAT, "Instagram ha cambiado el formato del enlace compartido.");
    }

    private static boolean isShareUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            return isInstagramHost(uri.getHost())
                    && uri.getPath() != null
                    && uri.getPath().toLowerCase(Locale.US).startsWith("/share/");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static void ensureInstagramUrl(String value) throws ExtractorFailure {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !isInstagramHost(uri.getHost())) {
                throw new ExtractorFailure(
                        FailureKind.UNAVAILABLE, "El enlace compartido salió de Instagram.");
            }
        } catch (URISyntaxException invalid) {
            throw new ExtractorFailure(
                    FailureKind.FORMAT, "El enlace compartido no es válido.", invalid);
        }
    }

    private static boolean isInstagramHost(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.US);
        return lower.equals("instagram.com")
                || lower.endsWith(".instagram.com")
                || lower.equals("instagr.am")
                || lower.endsWith(".instagr.am");
    }

    private static String resolveLocation(String base, String location) throws ExtractorFailure {
        try {
            return new URI(base).resolve(location).toString();
        } catch (URISyntaxException invalid) {
            throw new ExtractorFailure(
                    FailureKind.FORMAT, "La redirección compartida no es válida.", invalid);
        }
    }

    private static String findCanonicalUrl(String html) {
        if (html == null) {
            return null;
        }
        Matcher tags = LINK_TAG_PATTERN.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            if (!REL_CANONICAL_PATTERN.matcher(tag).find()) {
                continue;
            }
            Matcher href = HREF_PATTERN.matcher(tag);
            if (href.find()) {
                return decodeHtml(href.group(1));
            }
        }
        return null;
    }

    private static String shortcodeToMediaId(String shortcode) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        BigInteger result = BigInteger.ZERO;
        BigInteger radix = BigInteger.valueOf(64L);
        for (int i = 0; i < shortcode.length(); i++) {
            int digit = alphabet.indexOf(shortcode.charAt(i));
            if (digit < 0) {
                return "";
            }
            result = result.multiply(radix).add(BigInteger.valueOf(digit));
        }
        return result.toString();
    }

    private static HttpURLConnection openMediaConnection(String mediaUrl) throws IOException {
        if (isBlank(mediaUrl)) {
            throw new IllegalArgumentException("La URL multimedia no puede estar vacía.");
        }
        URL parsed = new URL(mediaUrl);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            throw new IllegalArgumentException("La URL multimedia debe usar HTTPS.");
        }
        return openConnection(mediaUrl, "GET", BROWSER_UA, HOME_URL, true);
    }

    private static HttpURLConnection openConnection(
            String url, String method, String userAgent, String referer, boolean followRedirects)
            throws IOException {
        URL parsedUrl = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) parsedUrl.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        DownloadControl.track(connection);
        connection.setInstanceFollowRedirects(followRedirects);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
        connection.setRequestProperty("User-Agent", userAgent);
        if (!isBlank(referer)) {
            connection.setRequestProperty("Referer", referer);
        }
        if (isInstagramHost(parsedUrl.getHost())) {
            String cookieHeader = cookieHeader();
            if (!cookieHeader.isEmpty()) {
                connection.setRequestProperty("Cookie", cookieHeader);
            }
        }
        return connection;
    }

    private static void captureCookies(HttpURLConnection connection) {
        Map<String, List<String>> headers = connection.getHeaderFields();
        if (headers == null) {
            return;
        }
        synchronized (COOKIE_LOCK) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null
                        || !"set-cookie".equalsIgnoreCase(entry.getKey())
                        || entry.getValue() == null) {
                    continue;
                }
                for (String header : entry.getValue()) {
                    if (header == null) {
                        continue;
                    }
                    int semicolon = header.indexOf(';');
                    String pair = semicolon >= 0 ? header.substring(0, semicolon) : header;
                    int equals = pair.indexOf('=');
                    if (equals <= 0) {
                        continue;
                    }
                    String name = pair.substring(0, equals).trim();
                    String value = pair.substring(equals + 1).trim();
                    if (!name.isEmpty()) {
                        COOKIES.put(name, value);
                    }
                }
            }
        }
    }

    private static String cookieHeader() {
        synchronized (COOKIE_LOCK) {
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String, String> cookie : COOKIES.entrySet()) {
                if (result.length() > 0) {
                    result.append("; ");
                }
                result.append(cookie.getKey()).append('=').append(cookie.getValue());
            }
            return result.toString();
        }
    }

    private static String readResponse(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output, 12 * 1024 * 1024);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static long copy(InputStream input, OutputStream output, int maximumBytes)
            throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (maximumBytes >= 0 && total + read > maximumBytes) {
                throw new IOException("La respuesta de Instagram es demasiado grande.");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static boolean containsRateLimit(JSONObject root) {
        String text = root.toString().toLowerCase(Locale.US);
        return text.contains("rate limit")
                || text.contains("please wait a few minutes")
                || text.contains("feedback_required");
    }

    private static boolean containsUnavailableMarker(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("login_required")
                || lower.contains("/accounts/login")
                || lower.contains("content isn't available")
                || lower.contains("page isn't available")
                || lower.contains("contenido no disponible")
                || lower.contains("checkpoint_required")
                || lower.contains("challenge_required");
    }

    private static boolean looksLikeHtml(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim().toLowerCase(Locale.US);
        return trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html");
    }

    private static boolean hasKind(ExtractorFailure failure, FailureKind kind) {
        return failure != null && failure.kind == kind;
    }

    private static int positiveInt(JSONObject object, String key) {
        long value = object.optLong(key, 0L);
        return value <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static String nonBlank(String value) {
        return isBlank(value) ? null : decodeHtml(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String decodeHtml(String value) {
        return value == null ? null : value.replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&#x26;", "&");
    }

    private enum FailureKind {
        RATE_LIMIT,
        NETWORK,
        UNAVAILABLE,
        FORMAT
    }

    private static final class ExtractorFailure extends Exception {
        private static final long serialVersionUID = 1L;

        final FailureKind kind;

        ExtractorFailure(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        ExtractorFailure(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    private static final class Candidate {
        final String url;
        final int width;
        final int height;

        Candidate(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }
    }
}
