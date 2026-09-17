// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Safe on-device transport and selection layer for videos exposed by ordinary web pages. */
public final class GenericWebExtractor {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HTML_BYTES = 6 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;
    private static final int PROBE_BYTES = 64 * 1024;
    private static final int MAX_PROBES = 16;
    private static final int MAX_REFRESHES = 2;
    private static final int MAX_REFRESH_DELAY_SECONDS = 12;
    private static final int MAX_TOTAL_REFRESH_DELAY_SECONDS = 15;

    private static final Pattern META_TAG = Pattern.compile(
            "<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_COMMENT = Pattern.compile(
            "<!--.*?(?:-->|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REFRESH_INERT_BLOCK = Pattern.compile(
            "<(script|style|template|textarea|xmp|noscript)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:([\"'])(.*?)\\2|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REFRESH = Pattern.compile(
            "^\\s*(\\d{1,2})(?:\\s*;\\s*(?:url\\s*=\\s*)?(.+))?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET = Pattern.compile(
            "(?i)(?:^|;)\\s*charset\\s*=\\s*[\"']?([^;\"'\\s]+)");
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile(
            "(?i)^bytes\\s+\\d+-\\d+/(\\d+|\\*)$");
    private static final Pattern QUERY_ID = Pattern.compile(
            "(?i)(?:^|&)(?:viewkey|video_id|video|id|v)=([^&]{1,100})(?:&|$)");

    private GenericWebExtractor() {
    }

    /** Finds and returns only the video most likely to be the page's principal content. */
    public static ExtractionResult extract(String url, boolean dataSaver) throws Exception {
        URI requested = PublicWebUrlPolicy.requirePublicHttps(url);
        if (isYouTubeHost(requested.getHost())) {
            throw new IOException(
                    "YouTube utiliza un sistema distinto y necesita su propio módulo; "
                            + "no se analiza como una web genérica.");
        }

        PageDocument page = fetchPage(requested);
        GenericExtractor.Result parsed = null;
        int refreshes = 0;
        int waitedSeconds = 0;
        while (true) {
            if (page.directMedia) {
                return resultForDirectPage(page, dataSaver);
            }
            IOException parseFailure = null;
            try {
                parsed = GenericExtractor.parse(page.html, page.finalUri, dataSaver);
            } catch (IOException noMedia) {
                String lower = safeMessage(noMedia).toLowerCase(Locale.US);
                if (!lower.contains("no se encontr")) {
                    throw noMedia;
                }
                parseFailure = noMedia;
            }

            // If the only thing found is a weak clip/ad, prefer the page's declared transition.
            RefreshInstruction instruction = findRefresh(page);
            if (canFollowRefresh(parsed, instruction, refreshes, waitedSeconds)) {
                URI next = instruction.target == null
                        ? page.finalUri
                        : PublicWebUrlPolicy.resolvePublicHttps(page.finalUri, instruction.target);
                if (!sameOrigin(page.finalUri, next)) {
                    throw new IOException(
                            "La espera de la página intenta abrir otro sitio y se ha bloqueado.");
                }
                waitInterruptibly(instruction.delaySeconds);
                waitedSeconds += instruction.delaySeconds;
                refreshes++;
                page = fetchPage(next, page.finalUri);
                parsed = null;
                continue;
            }
            if (parsed == null) {
                if (parseFailure != null && instruction == null) {
                    throw new IOException(
                            "La página no expone el vídeo directamente. Si exige ejecutar "
                                    + "publicidad, iniciar sesión o usar un reproductor protegido, "
                                    + "no puede analizarse de forma segura.");
                }
                throw new IOException(
                        "La espera indicada por la página supera el límite seguro de 15 segundos.");
            }
            break;
        }

        String pageUrl = page.finalUri.toASCIIString();
        String origin = requestOriginForPage(page.finalUri);
        List<ValidatedCandidate> valid = new ArrayList<>();
        Exception lastFailure = null;
        int attempts = 0;
        for (GenericExtractor.Media candidate : parsed.getCandidates()) {
            if (attempts++ >= MAX_PROBES) {
                break;
            }
            try {
                valid.add(validateCandidate(candidate, pageUrl, origin, dataSaver));
            } catch (Exception rejected) {
                lastFailure = rejected;
            }
        }
        if (valid.isEmpty()) {
            String reason = lastFailure == null ? "" : safeMessage(lastFailure).toLowerCase(Locale.US);
            if (reason.contains("sesión") || reason.contains("401") || reason.contains("403")) {
                throw new IOException("El sitio exige una sesión para entregar el vídeo.");
            }
            throw new IOException(
                    "La página anunció vídeos, pero ninguno devolvió un archivo público válido.");
        }

        Collections.sort(valid, ValidatedCandidate.BEST_FIRST);
        ValidatedCandidate selected = valid.get(0);
        if (dataSaver) {
            selected = selectDataSaverVariant(selected, valid);
        }
        if (!selected.isStrongAfterProbe()) {
            throw new IOException(
                    "La página solo expone clips pequeños o secundarios; no se descargará "
                            + "uno de ellos fingiendo que es el vídeo principal.");
        }
        MediaItem item = selected.toMediaItem(origin);
        return new ExtractionResult(
                SocialPlatform.GENERIC,
                pageUrl,
                contentId(page.finalUri),
                Collections.singletonList(item));
    }

    /** Downloads a previously validated generic-web video without closing the destination. */
    public static void downloadToStream(MediaItem item, OutputStream output) throws Exception {
        requireGenericVideo(item, output);
        if (item.isStreamingManifest()) {
            GenericHlsDownloader.ResolvedStream stream = GenericHlsDownloader.resolve(
                    item.getUrl(), false, requiredOrigin(item));
            if (!stream.getSuggestedExtension().equals(item.getSuggestedExtension())) {
                throw new IOException("El formato HLS cambió; analiza de nuevo la página.");
            }
            GenericHlsDownloader.downloadToStream(stream, output);
            return;
        }
        downloadDirect(item, output);
    }

    /** Downloads a bounded JPEG, PNG or WebP poster for the native preview. */
    public static byte[] fetchPreview(MediaItem item) throws Exception {
        if (item == null || item.getPlatform() != SocialPlatform.GENERIC) {
            throw new IllegalArgumentException("La vista previa no pertenece al extractor web.");
        }
        String preview = item.getPreviewUrl();
        if (preview == null) {
            throw new IOException("Este vídeo no ofrece una vista previa pública.");
        }
        URI current = PublicWebUrlPolicy.requirePublicHttps(preview);
        String origin = requiredOrigin(item);
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!visited.add(current.toASCIIString())) {
                throw new IOException("La vista previa contiene un bucle de redirecciones.");
            }
            HttpURLConnection connection = open(current, origin, "image/avif,image/webp,image/png,image/jpeg,*/*;q=0.1");
            try {
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    current = redirectTarget(connection, current, "La vista previa");
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("La vista previa no está disponible (HTTP " + status + ").");
                }
                ensureIdentityEncoding(connection);
                long length = connection.getContentLengthLong();
                if (length > MAX_PREVIEW_BYTES) {
                    throw new IOException("La vista previa es demasiado grande.");
                }
                byte[] bytes;
                try (InputStream input = connection.getInputStream()) {
                    bytes = readBounded(input, MAX_PREVIEW_BYTES, "La vista previa es demasiado grande.");
                }
                if (!isSafeImage(bytes, connection.getContentType())) {
                    throw new IOException("La vista previa no es una imagen segura.");
                }
                return bytes;
            } finally {
                DownloadControl.release(connection);
                connection.disconnect();
            }
        }
        throw new IOException("La vista previa contiene demasiadas redirecciones.");
    }

    private static ExtractionResult resultForDirectPage(PageDocument page, boolean dataSaver)
            throws Exception {
        GenericExtractor.Media synthetic = GenericExtractor.parseHtml(
                page.finalUri.toASCIIString(),
                "<video controls src=\"" + htmlAttribute(page.finalUri.toASCIIString()) + "\"></video>")
                .getMainMedia();
        String origin = requestOriginForPage(page.finalUri);
        ValidatedCandidate candidate = validateCandidate(
                synthetic, page.finalUri.toASCIIString(), origin, dataSaver);
        return new ExtractionResult(
                SocialPlatform.GENERIC,
                page.finalUri.toASCIIString(),
                contentId(page.finalUri),
                Collections.singletonList(candidate.toMediaItem(origin)));
    }

    private static ValidatedCandidate validateCandidate(
            GenericExtractor.Media candidate,
            String pageUrl,
            String origin,
            boolean dataSaver) throws Exception {
        return validateCandidate(candidate, pageUrl, origin, dataSaver, 0);
    }

    private static ValidatedCandidate validateCandidate(
            GenericExtractor.Media candidate,
            String pageUrl,
            String origin,
            boolean dataSaver,
            int metadataDepth) throws Exception {
        Probe probe = probe(candidate.getUrl(), origin);
        if (probe.metadataDocument) {
            if (metadataDepth >= 1 || probe.totalLength > MAX_METADATA_BYTES) {
                throw new IOException("La dirección de metadatos no entregó un vídeo directo.");
            }
            MetadataDocument metadata = fetchMetadataDocument(probe.finalUri, origin);
            GenericExtractor.Result expanded = parseMediaMetadata(
                    metadata.finalUri, metadata.body, dataSaver);
            List<ValidatedCandidate> nested = new ArrayList<>();
            Exception lastFailure = null;
            int attempts = 0;
            for (GenericExtractor.Media nestedCandidate : expanded.getCandidates()) {
                if (attempts++ >= MAX_PROBES) {
                    break;
                }
                try {
                    nested.add(validateCandidate(
                            nestedCandidate, pageUrl, origin, dataSaver, metadataDepth + 1));
                } catch (Exception rejected) {
                    lastFailure = rejected;
                }
            }
            if (nested.isEmpty()) {
                throw new IOException(
                        "La dirección de metadatos no publicó ningún vídeo válido.", lastFailure);
            }
            Collections.sort(nested, ValidatedCandidate.BEST_FIRST);
            ValidatedCandidate selected = nested.get(0);
            return dataSaver ? selectDataSaverVariant(selected, nested) : selected;
        }
        if (probe.format == MediaValidator.Format.HLS) {
            GenericHlsDownloader.ResolvedStream stream = GenericHlsDownloader.resolve(
                    probe.finalUri.toASCIIString(), dataSaver, origin);
            int width = stream.getWidth() > 0 ? stream.getWidth() : candidate.getWidth();
            int height = stream.getHeight() > 0 ? stream.getHeight() : candidate.getHeight();
            double duration = stream.getDurationMillis() > 0
                    ? stream.getDurationMillis() / 1000.0d
                    : candidate.getDurationSeconds();
            long estimated = stream.getBandwidth() > 0 && duration > 0
                    ? Math.min(MediaValidator.MAX_VIDEO_BYTES,
                            Math.round(stream.getBandwidth() * duration / 8.0d))
                    : candidate.getContentLength();
            return new ValidatedCandidate(
                    candidate,
                    stream.getMediaPlaylistUrl(),
                    candidate.getPosterUrl(),
                    width,
                    height,
                    duration,
                    estimated,
                    stream.getMimeType(),
                    stream.getSuggestedExtension(),
                    true);
        }
        return new ValidatedCandidate(
                candidate,
                probe.finalUri.toASCIIString(),
                candidate.getPosterUrl(),
                candidate.getWidth(),
                candidate.getHeight(),
                candidate.getDurationSeconds(),
                probe.totalLength > 0 ? probe.totalLength : candidate.getContentLength(),
                probe.format.getMimeType(),
                probe.format.getExtension(),
                false);
    }

    private static Probe probe(String url, String origin) throws Exception {
        URI current = PublicWebUrlPolicy.requirePublicHttps(url);
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!visited.add(current.toASCIIString())) {
                throw new IOException("El vídeo contiene un bucle de redirecciones.");
            }
            HttpURLConnection connection = open(
                    current,
                    origin,
                    "video/*,application/vnd.apple.mpegurl,application/x-mpegURL,*/*;q=0.1");
            try {
                connection.setRequestProperty("Range", "bytes=0-" + (PROBE_BYTES - 1));
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    current = redirectTarget(connection, current, "El vídeo");
                    continue;
                }
                if (status == 401 || status == 403) {
                    throw new IOException("El sitio exige una sesión para entregar el vídeo.");
                }
                if (status != HttpURLConnection.HTTP_OK
                        && status != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException("El vídeo no está disponible (HTTP " + status + ").");
                }
                ensureIdentityEncoding(connection);
                long total = responseTotalLength(connection, status);
                if (total > MediaValidator.MAX_VIDEO_BYTES) {
                    throw new IOException("El vídeo supera el límite de seguridad de 2 GiB.");
                }
                byte[] prefix;
                try (InputStream input = connection.getInputStream()) {
                    prefix = readAtMost(input, PROBE_BYTES);
                }
                try {
                    MediaValidator.Format format =
                            MediaValidator.detect(prefix, connection.getContentType());
                    return new Probe(current, format, total, false);
                } catch (IOException notMedia) {
                    if (looksLikeJsonMetadata(prefix, connection.getContentType())) {
                        return new Probe(current, null, total, true);
                    }
                    throw notMedia;
                }
            } finally {
                DownloadControl.release(connection);
                connection.disconnect();
            }
        }
        throw new IOException("El vídeo contiene demasiadas redirecciones.");
    }

    private static MetadataDocument fetchMetadataDocument(URI initial, String origin)
            throws Exception {
        URI current = PublicWebUrlPolicy.requirePublicHttps(initial.toASCIIString());
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!visited.add(current.toASCIIString())) {
                throw new IOException("Los metadatos contienen un bucle de redirecciones.");
            }
            HttpURLConnection connection = open(
                    current, origin, "application/json,text/json,text/plain,*/*;q=0.1");
            try {
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    current = redirectTarget(connection, current, "Los metadatos");
                    continue;
                }
                if (status == 401 || status == 403) {
                    throw new IOException("El sitio exige una sesión para entregar el vídeo.");
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException(
                            "Los metadatos del vídeo no están disponibles (HTTP " + status + ").");
                }
                ensureIdentityEncoding(connection);
                long announced = connection.getContentLengthLong();
                if (announced > MAX_METADATA_BYTES) {
                    throw new IOException("Los metadatos del vídeo son demasiado grandes.");
                }
                byte[] bytes;
                try (InputStream input = connection.getInputStream()) {
                    bytes = readBounded(
                            input,
                            MAX_METADATA_BYTES,
                            "Los metadatos del vídeo son demasiado grandes.");
                }
                if (!looksLikeJsonMetadata(bytes, connection.getContentType())) {
                    throw new IOException("La dirección de metadatos no devolvió un documento JSON.");
                }
                return new MetadataDocument(
                        current, new String(bytes, responseCharset(connection.getContentType())));
            } finally {
                DownloadControl.release(connection);
                connection.disconnect();
            }
        }
        throw new IOException("Los metadatos contienen demasiadas redirecciones.");
    }

    static GenericExtractor.Result parseMediaMetadata(
            URI endpoint, String body, boolean dataSaver) throws Exception {
        if (endpoint == null || body == null
                || body.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw new IOException("Los metadatos del vídeo no son válidos.");
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[') {
            throw new IOException("La dirección de metadatos no devolvió un documento JSON.");
        }
        String safeJson = body.replaceAll("(?i)</script", "<\\\\/script");
        return GenericExtractor.parse("<script>" + safeJson + "</script>", endpoint, dataSaver);
    }

    private static boolean looksLikeJsonMetadata(byte[] bytes, String contentType) {
        String mime = MediaValidator.normalizeMime(contentType);
        if (mime.contains("json")) {
            return firstNonWhitespace(bytes) == '{' || firstNonWhitespace(bytes) == '[';
        }
        int marker = firstNonWhitespace(bytes);
        return marker == '{' || marker == '[';
    }

    private static int firstNonWhitespace(byte[] bytes) {
        if (bytes == null) {
            return -1;
        }
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            start = 3;
        }
        while (start < bytes.length && Character.isWhitespace((char) (bytes[start] & 0xff))) {
            start++;
        }
        return start < bytes.length ? bytes[start] & 0xff : -1;
    }

    private static void downloadDirect(MediaItem item, OutputStream output) throws Exception {
        URI current = PublicWebUrlPolicy.requirePublicHttps(item.getUrl());
        String origin = requiredOrigin(item);
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!visited.add(current.toASCIIString())) {
                throw new IOException("La descarga contiene un bucle de redirecciones.");
            }
            HttpURLConnection connection = open(current, origin, "video/*,application/octet-stream;q=0.5");
            try {
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    current = redirectTarget(connection, current, "La descarga");
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("La descarga fue rechazada (HTTP " + status + ").");
                }
                ensureIdentityEncoding(connection);
                long announced = connection.getContentLengthLong();
                if (announced > MediaValidator.MAX_VIDEO_BYTES) {
                    throw new IOException("El vídeo supera el límite de seguridad de 2 GiB.");
                }
                DownloadControl.reportSize(announced);
                long written = 0;
                try (InputStream input = connection.getInputStream()) {
                    byte[] prefix = readAtMost(input, 4096);
                    MediaValidator.Format actual = MediaValidator.detect(prefix, connection.getContentType());
                    if (actual.isManifest()
                            || !actual.getExtension().equals(item.getSuggestedExtension())) {
                        throw new IOException("El servidor cambió el formato; analiza de nuevo la página.");
                    }
                    output.write(prefix);
                    written = prefix.length;
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkInterrupted();
                        written += count;
                        if (written > MediaValidator.MAX_VIDEO_BYTES) {
                            throw new IOException("El vídeo supera el límite de seguridad de 2 GiB.");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                if (written <= 0 || announced >= 0 && written != announced) {
                    throw new IOException("El servidor entregó un vídeo incompleto.");
                }
                return;
            } finally {
                DownloadControl.release(connection);
                connection.disconnect();
            }
        }
        throw new IOException("La descarga contiene demasiadas redirecciones.");
    }

    private static PageDocument fetchPage(URI initial) throws Exception {
        return fetchPage(initial, null);
    }

    private static PageDocument fetchPage(URI initial, URI requiredOrigin) throws Exception {
        URI current = PublicWebUrlPolicy.requirePublicHttps(initial.toASCIIString());
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (requiredOrigin != null && !sameOrigin(requiredOrigin, current)) {
                throw new IOException(
                        "La espera de la página intenta salir del sitio y se ha bloqueado.");
            }
            if (!visited.add(current.toASCIIString())) {
                throw new IOException("La página contiene un bucle de redirecciones.");
            }
            HttpURLConnection connection = open(
                    current,
                    null,
                    "text/html,application/xhtml+xml,video/*,application/vnd.apple.mpegurl,*/*;q=0.1");
            try {
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    URI redirected = redirectTarget(connection, current, "La página");
                    if (requiredOrigin != null && !sameOrigin(requiredOrigin, redirected)) {
                        throw new IOException(
                                "La espera de la página intenta salir del sitio y se ha bloqueado.");
                    }
                    current = redirected;
                    continue;
                }
                if (status == 401 || status == 403) {
                    throw new IOException("La página exige iniciar sesión o bloquea el acceso público.");
                }
                if (status == 404 || status == 410) {
                    throw new IOException("La página ya no está disponible.");
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("No se pudo consultar la página (HTTP " + status + ").");
                }
                ensureIdentityEncoding(connection);
                long announced = connection.getContentLengthLong();
                String mime = MediaValidator.normalizeMime(connection.getContentType());
                if (looksLikeMediaMimeOrPath(mime, current.getPath())) {
                    return new PageDocument(current, null, true, connection.getHeaderField("Refresh"));
                }
                if (announced > MAX_HTML_BYTES) {
                    throw new IOException("La página es demasiado grande para analizarla con seguridad.");
                }
                byte[] bytes;
                try (InputStream input = connection.getInputStream()) {
                    bytes = readBounded(input, MAX_HTML_BYTES, "La página es demasiado grande para analizarla con seguridad.");
                }
                boolean direct = false;
                try {
                    direct = MediaValidator.detect(
                            bytes.length > PROBE_BYTES ? copyPrefix(bytes, PROBE_BYTES) : bytes,
                            connection.getContentType()) != null;
                } catch (IOException notMedia) {
                    // El cuerpo se procesa como HTML.
                }
                if (direct) {
                    return new PageDocument(current, null, true, connection.getHeaderField("Refresh"));
                }
                if (!mime.isEmpty() && !mime.equals("text/html")
                        && !mime.equals("application/xhtml+xml") && !mime.equals("text/plain")) {
                    throw new IOException("El enlace no devuelve una página HTML pública.");
                }
                String html = new String(bytes, responseCharset(connection.getContentType()));
                return new PageDocument(current, html, false, connection.getHeaderField("Refresh"));
            } finally {
                DownloadControl.release(connection);
                connection.disconnect();
            }
        }
        throw new IOException("La página contiene demasiadas redirecciones.");
    }

    private static HttpURLConnection open(URI uri, String refererOrigin, String accept)
            throws Exception {
        URI safe = PublicWebUrlPolicy.requirePublicHttps(uri.toASCIIString());
        HttpURLConnection connection = (HttpURLConnection) new URL(safe.toASCIIString())
                .openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        DownloadControl.track(connection);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.5");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Cookie", "");
        connection.setRequestProperty("Cookie2", "");
        if (refererOrigin != null) {
            URI origin = PublicWebUrlPolicy.requirePublicHttps(refererOrigin);
            String safeOrigin = PublicWebUrlPolicy.origin(origin);
            connection.setRequestProperty("Origin", safeOrigin);
            connection.setRequestProperty("Referer", safeOrigin + "/");
        }
        return connection;
    }

    private static URI redirectTarget(HttpURLConnection connection, URI current, String label)
            throws Exception {
        String location = connection.getHeaderField("Location");
        if (location == null || location.trim().isEmpty()) {
            throw new IOException(label + " redirige sin indicar un destino.");
        }
        return PublicWebUrlPolicy.resolvePublicHttps(current, location);
    }

    private static void ensureIdentityEncoding(HttpURLConnection connection) throws IOException {
        String encoding = connection.getHeaderField("Content-Encoding");
        if (encoding != null && !encoding.trim().isEmpty()
                && !"identity".equalsIgnoreCase(encoding.trim())) {
            throw new IOException("El servidor transformó la respuesta de forma no permitida.");
        }
    }

    private static long responseTotalLength(HttpURLConnection connection, int status) {
        if (status == HttpURLConnection.HTTP_OK) {
            return connection.getContentLengthLong();
        }
        String range = connection.getHeaderField("Content-Range");
        Matcher matcher = range == null ? null : CONTENT_RANGE_TOTAL.matcher(range.trim());
        if (matcher != null && matcher.matches() && !"*".equals(matcher.group(1))) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    static RefreshInstruction findRefresh(PageDocument page) {
        RefreshInstruction fromHeader = parseRefresh(page.refreshHeader);
        if (fromHeader != null) {
            return fromHeader;
        }
        if (page.html == null) {
            return null;
        }
        String safeMarkup = maskRefreshInertMarkup(page.html);
        Matcher tags = META_TAG.matcher(safeMarkup);
        while (tags.find()) {
            String tag = tags.group();
            String httpEquiv = attribute(tag, "http-equiv");
            if (httpEquiv != null && "refresh".equalsIgnoreCase(httpEquiv.trim())) {
                RefreshInstruction result = parseRefresh(attribute(tag, "content"));
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    static RefreshInstruction parseRefresh(String value) {
        if (value == null || value.length() > 4096) {
            return null;
        }
        Matcher matcher = REFRESH.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        int delay;
        try {
            delay = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException invalid) {
            return null;
        }
        String target = matcher.group(2);
        if (target != null) {
            target = decodeRefreshAttribute(target.trim());
            if (target.length() >= 2
                    && ((target.startsWith("\"") && target.endsWith("\""))
                    || (target.startsWith("'") && target.endsWith("'")))) {
                target = target.substring(1, target.length() - 1);
            }
            if (target.isEmpty()) {
                target = null;
            }
        }
        return new RefreshInstruction(delay, target);
    }

    static boolean canFollowRefresh(
            GenericExtractor.Result parsed,
            RefreshInstruction instruction,
            int refreshes,
            int waitedSeconds) {
        if (instruction == null || instruction.delaySeconds < 0
                || refreshes >= MAX_REFRESHES
                || instruction.delaySeconds > MAX_REFRESH_DELAY_SECONDS
                || waitedSeconds + instruction.delaySeconds > MAX_TOTAL_REFRESH_DELAY_SECONDS) {
            return false;
        }
        return parsed == null || parsed.isFallbackSelection()
                || !hasIndependentMainEvidence(parsed.getMainMedia());
    }

    private static boolean hasIndependentMainEvidence(GenericExtractor.Media media) {
        if (media == null || media.isLikelyAuxiliary()) {
            return false;
        }
        long pixels = (long) media.getWidth() * (long) media.getHeight();
        if (media.getDurationSeconds() >= 10.0d
                || media.getContentLength() >= 2L * 1024L * 1024L
                || pixels >= 640L * 360L) {
            return true;
        }
        String source = media.getDiscoverySource();
        return "opengraph".equals(source) || "json-ld".equals(source)
                || "json-ld-encoding".equals(source);
    }

    private static String maskRefreshInertMarkup(String html) {
        char[] masked = html.toCharArray();
        maskMatches(masked, html, HTML_COMMENT);
        maskMatches(masked, html, REFRESH_INERT_BLOCK);
        return new String(masked);
    }

    private static void maskMatches(char[] target, String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            for (int index = matcher.start(); index < matcher.end(); index++) {
                target[index] = ' ';
            }
        }
    }

    private static String decodeRefreshAttribute(String value) {
        return value.replaceAll("(?i)&quot;|&#34;|&#x22;", "\"")
                .replaceAll("(?i)&apos;|&#39;|&#x27;", "'")
                .replaceAll("(?i)&lt;|&#60;|&#x3c;", "<")
                .replaceAll("(?i)&gt;|&#62;|&#x3e;", ">")
                .replaceAll("(?i)&amp;|&#38;|&#x26;", "&");
    }

    private static String attribute(String tag, String wanted) {
        Matcher matcher = ATTRIBUTE.matcher(tag);
        while (matcher.find()) {
            if (wanted.equalsIgnoreCase(matcher.group(1))) {
                return matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            }
        }
        return null;
    }

    private static ValidatedCandidate selectDataSaverVariant(
            ValidatedCandidate principal, List<ValidatedCandidate> all) {
        ValidatedCandidate lightest = principal;
        for (ValidatedCandidate candidate : all) {
            if (candidate.rank < principal.rank - 90_000_000L
                    || !sameContent(principal, candidate)) {
                continue;
            }
            if (candidate.estimatedWeight() < lightest.estimatedWeight()) {
                lightest = candidate;
            }
        }
        return lightest;
    }

    private static boolean sameContent(ValidatedCandidate first, ValidatedCandidate second) {
        if (first == second) {
            return true;
        }
        if (first.posterUrl != null && first.posterUrl.equals(second.posterUrl)) {
            return true;
        }
        if (first.durationSeconds > 0 && second.durationSeconds > 0) {
            double difference = Math.abs(first.durationSeconds - second.durationSeconds);
            if (difference <= Math.max(3.0d, first.durationSeconds * 0.05d)) {
                return true;
            }
        }
        return qualityFamily(first.url).equals(qualityFamily(second.url));
    }

    private static String qualityFamily(String url) {
        int query = url.indexOf('?');
        String withoutQuery = query < 0 ? url : url.substring(0, query);
        return withoutQuery.toLowerCase(Locale.US)
                .replaceAll("(?:4320|2160|1440|1080|720|576|540|480|360|240|144)p?", "{q}")
                .replaceAll("(?:width|height)[=_-]?\\d+", "size");
    }

    static long candidateRank(
            GenericExtractor.Media media,
            int width,
            int height,
            double duration,
            long size) {
        // The static source hint is useful, but the facts learned from the actual media
        // response must be able to outweigh it. This is what keeps a small OpenGraph teaser
        // from beating a much heavier principal video discovered in a script.
        long result = (long) media.getScore() * 50_000L;
        long pixels = (long) width * height;
        result += Math.min(25_000_000L, pixels * 5L);
        result += Math.min(180_000_000L, Math.round(duration * 50_000.0d));
        if (size > 0) {
            result += Math.min(120_000_000L, size / 8L);
        }
        return result;
    }

    private static String contentId(URI uri) {
        String query = uri.getRawQuery();
        if (query != null) {
            Matcher matcher = QUERY_ID.matcher(query);
            if (matcher.find()) {
                String id = safeId(matcher.group(1));
                if (!id.isEmpty()) {
                    return id;
                }
            }
        }
        String path = uri.getPath();
        if (path != null) {
            String[] segments = path.split("/");
            for (int index = segments.length - 1; index >= 0; index--) {
                String id = safeId(segments[index]);
                if (!id.isEmpty() && !id.equalsIgnoreCase("video")
                        && !id.equalsIgnoreCase("videos")
                        && !id.equalsIgnoreCase("view_video.php")) {
                    return id;
                }
            }
        }
        return safeId(uri.getHost());
    }

    private static String safeId(String value) {
        if (value == null) {
            return "";
        }
        String result = value.replaceAll("[^A-Za-z0-9_-]", "");
        return result.length() > 64 ? result.substring(0, 64) : result;
    }

    private static boolean looksLikeMediaMimeOrPath(String mime, String path) {
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.US);
        return mime.startsWith("video/")
                || mime.contains("mpegurl")
                || mime.equals("application/octet-stream")
                || mime.equals("binary/octet-stream")
                || lowerPath.endsWith(".mp4") || lowerPath.endsWith(".webm")
                || lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".ts");
    }

    private static boolean isYouTubeHost(String host) {
        String value = host == null ? "" : host.toLowerCase(Locale.US);
        return value.equals("youtu.be") || value.endsWith(".youtu.be")
                || value.equals("youtube.com") || value.endsWith(".youtube.com")
                || value.equals("youtube-nocookie.com")
                || value.endsWith(".youtube-nocookie.com");
    }

    static boolean sameOrigin(URI first, URI second) {
        return first != null && second != null
                && "https".equalsIgnoreCase(first.getScheme())
                && "https".equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private static String requestOriginForPage(URI page) throws Exception {
        String host = page == null || page.getHost() == null
                ? "" : page.getHost().toLowerCase(Locale.US);
        if (host.equals("pornhub.com") || host.endsWith(".pornhub.com")) {
            return PublicWebUrlPolicy.origin(
                    PublicWebUrlPolicy.requirePublicHttps("https://www.pornhub.com"));
        }
        return PublicWebUrlPolicy.origin(page);
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307 || status == 308;
    }

    private static Charset responseCharset(String contentType) {
        Matcher matcher = contentType == null ? null : CHARSET.matcher(contentType);
        if (matcher != null && matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (Exception ignored) {
                // UTF-8 is the safe web default for this parser.
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static byte[] readBounded(InputStream input, int limit, String error) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkInterrupted();
            total += count;
            if (total > limit) {
                throw new IOException(error);
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] readAtMost(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
        byte[] buffer = new byte[8192];
        while (output.size() < limit) {
            checkInterrupted();
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
            if (count == -1) {
                break;
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] copyPrefix(byte[] source, int length) {
        byte[] result = new byte[Math.min(source.length, length)];
        System.arraycopy(source, 0, result, 0, result.length);
        return result;
    }

    private static boolean isSafeImage(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        String mime = MediaValidator.normalizeMime(contentType);
        if (!mime.isEmpty() && !mime.equals("image/jpeg") && !mime.equals("image/png")
                && !mime.equals("image/webp") && !mime.equals("application/octet-stream")) {
            return false;
        }
        boolean jpeg = (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff;
        boolean png = (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xff) == 0x0d && (bytes[5] & 0xff) == 0x0a;
        boolean webp = bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
                && bytes[10] == 'B' && bytes[11] == 'P';
        return jpeg || png || webp;
    }

    private static String requiredOrigin(MediaItem item) throws Exception {
        String value = item.getRequestOrigin();
        if (value == null) {
            throw new IOException("Falta el origen seguro de esta descarga; analiza de nuevo.");
        }
        return PublicWebUrlPolicy.origin(PublicWebUrlPolicy.requirePublicHttps(value));
    }

    private static void requireGenericVideo(MediaItem item, OutputStream output) {
        if (item == null || item.getPlatform() != SocialPlatform.GENERIC || !item.isVideo()) {
            throw new IllegalArgumentException("El archivo no pertenece al extractor web.");
        }
        if (output == null) {
            throw new IllegalArgumentException("El destino de descarga no puede estar vacío.");
        }
    }

    private static void waitInterruptibly(int seconds) throws IOException {
        if (seconds <= 0) {
            return;
        }
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("El análisis fue cancelado.", interrupted);
        }
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("La operación fue cancelada.");
        }
    }

    private static String htmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "" : error.getMessage();
    }

    static final class PageDocument {
        final URI finalUri;
        final String html;
        final boolean directMedia;
        final String refreshHeader;

        PageDocument(URI finalUri, String html, boolean directMedia, String refreshHeader) {
            this.finalUri = finalUri;
            this.html = html;
            this.directMedia = directMedia;
            this.refreshHeader = refreshHeader;
        }
    }

    static final class RefreshInstruction {
        final int delaySeconds;
        final String target;

        RefreshInstruction(int delaySeconds, String target) {
            this.delaySeconds = delaySeconds;
            this.target = target;
        }
    }

    private static final class Probe {
        final URI finalUri;
        final MediaValidator.Format format;
        final long totalLength;
        final boolean metadataDocument;

        Probe(
                URI finalUri,
                MediaValidator.Format format,
                long totalLength,
                boolean metadataDocument) {
            this.finalUri = finalUri;
            this.format = format;
            this.totalLength = totalLength;
            this.metadataDocument = metadataDocument;
        }
    }

    private static final class MetadataDocument {
        final URI finalUri;
        final String body;

        MetadataDocument(URI finalUri, String body) {
            this.finalUri = finalUri;
            this.body = body;
        }
    }

    private static final class ValidatedCandidate {
        static final Comparator<ValidatedCandidate> BEST_FIRST = new Comparator<ValidatedCandidate>() {
            @Override
            public int compare(ValidatedCandidate left, ValidatedCandidate right) {
                int byRank = Long.compare(right.rank, left.rank);
                return byRank != 0 ? byRank : left.url.compareTo(right.url);
            }
        };

        final GenericExtractor.Media source;
        final String url;
        final String posterUrl;
        final int width;
        final int height;
        final double durationSeconds;
        final long size;
        final String mimeType;
        final String extension;
        final boolean manifest;
        final long rank;

        ValidatedCandidate(
                GenericExtractor.Media source,
                String url,
                String posterUrl,
                int width,
                int height,
                double durationSeconds,
                long size,
                String mimeType,
                String extension,
                boolean manifest) {
            this.source = source;
            this.url = url;
            this.posterUrl = posterUrl;
            this.width = width;
            this.height = height;
            this.durationSeconds = durationSeconds;
            this.size = size;
            this.mimeType = mimeType;
            this.extension = extension;
            this.manifest = manifest;
            this.rank = candidateRank(source, width, height, durationSeconds, size);
        }

        long estimatedWeight() {
            if (size > 0) {
                return size;
            }
            long pixels = (long) Math.max(1, width) * Math.max(1, height);
            return pixels * Math.max(1L, Math.round(durationSeconds));
        }

        boolean isStrongAfterProbe() {
            return !source.isLikelyAuxiliary()
                    && (size >= 2L * 1024L * 1024L
                    || durationSeconds >= 10.0d
                    || (long) width * height >= 640L * 360L);
        }

        MediaItem toMediaItem(String origin) {
            return new MediaItem(
                    url,
                    true,
                    posterUrl,
                    width,
                    height,
                    SocialPlatform.GENERIC,
                    mimeType,
                    extension,
                    origin,
                    manifest);
        }
    }
}
