// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Descarga Social contributors
package com.didazz.descargasocial;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves and downloads finite, public and unencrypted HLS streams.
 *
 * <p>The downloader intentionally supports only two layouts that can be joined without a media
 * transcoder: MPEG-TS segments, or fragmented MP4 with one stable {@code EXT-X-MAP}. Encrypted,
 * live, multi-audio and otherwise ambiguous playlists are rejected instead of producing a corrupt
 * file.</p>
 */
public final class GenericHlsDownloader {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 25_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_PLAYLIST_DEPTH = 3;
    private static final int MAX_VARIANTS = 100;
    private static final int MAX_SEGMENTS = 20_000;
    private static final int MAX_LINE_LENGTH = 64 * 1024;
    private static final long MAX_PLAYLIST_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_RESOURCE_BYTES = 768L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = MediaValidator.MAX_VIDEO_BYTES;
    private static final double MAX_TOTAL_DURATION_SECONDS = 24.0d * 60.0d * 60.0d;
    private static final int SIGNATURE_PREFIX_BYTES = 4096;

    private GenericHlsDownloader() {
    }

    /** Selects either the highest available rendition or the lightest compatible rendition. */
    public enum QualityPreference {
        BEST,
        DATA_SAVER
    }

    /** Immutable description of a media playlist that is safe to concatenate locally. */
    public static final class ResolvedStream {
        private final String sourceUrl;
        private final String mediaPlaylistUrl;
        private final Container container;
        private final List<Resource> resources;
        private final int segmentCount;
        private final long durationMillis;
        private final long bandwidth;
        private final int width;
        private final int height;
        private final String refererOrigin;

        private ResolvedStream(
                String sourceUrl,
                String mediaPlaylistUrl,
                Container container,
                List<Resource> resources,
                int segmentCount,
                long durationMillis,
                long bandwidth,
                int width,
                int height,
                String refererOrigin) {
            this.sourceUrl = sourceUrl;
            this.mediaPlaylistUrl = mediaPlaylistUrl;
            this.container = container;
            this.resources = Collections.unmodifiableList(new ArrayList<>(resources));
            this.segmentCount = segmentCount;
            this.durationMillis = durationMillis;
            this.bandwidth = bandwidth;
            this.width = width;
            this.height = height;
            this.refererOrigin = refererOrigin;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public String getMediaPlaylistUrl() {
            return mediaPlaylistUrl;
        }

        public int getSegmentCount() {
            return segmentCount;
        }

        public long getDurationMillis() {
            return durationMillis;
        }

        public long getBandwidth() {
            return bandwidth;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public boolean isFragmentedMp4() {
            return container == Container.FRAGMENTED_MP4;
        }

        public String getMimeType() {
            return container == Container.FRAGMENTED_MP4 ? "video/mp4" : "video/mp2t";
        }

        public String getSuggestedExtension() {
            return container == Container.FRAGMENTED_MP4 ? "mp4" : "ts";
        }
    }

    /** Resolves an HLS URL, selecting the best or the data-saving rendition. */
    public static ResolvedStream resolve(String playlistUrl, boolean dataSaver) throws IOException {
        return resolve(
                playlistUrl,
                dataSaver ? QualityPreference.DATA_SAVER : QualityPreference.BEST);
    }

    /** Resolves an HLS URL into one finite media playlist. */
    public static ResolvedStream resolve(String playlistUrl, QualityPreference preference)
            throws IOException {
        if (preference == null) {
            throw new IllegalArgumentException("La preferencia de calidad no puede estar vacía.");
        }
        String normalized = normalizeHttpsUrl(playlistUrl, true);
        return resolveInternal(normalized, preference, new NetworkFetcher(null), true, null);
    }

    /**
     * Resolves an HLS URL while sending only the public page origin as Referer.
     *
     * <p>This overload is useful when the playlist was discovered in a normal web page and the
     * CDN checks the embedding origin. No page path, query, cookie or credential is forwarded.</p>
     */
    public static ResolvedStream resolve(
            String playlistUrl,
            boolean dataSaver,
            String publicPageUrl) throws IOException {
        String normalized = normalizeHttpsUrl(playlistUrl, true);
        URI page = PublicWebUrlPolicy.requirePublicHttps(publicPageUrl);
        String origin = PublicWebUrlPolicy.origin(page);
        return resolveInternal(
                normalized,
                dataSaver ? QualityPreference.DATA_SAVER : QualityPreference.BEST,
                new NetworkFetcher(origin),
                true,
                origin);
    }

    /** Downloads an already resolved HLS stream without closing the destination stream. */
    public static long downloadToStream(ResolvedStream stream, OutputStream output)
            throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("La lista HLS resuelta no puede estar vacía.");
        }
        if (output == null) {
            throw new IllegalArgumentException("El destino de descarga no puede estar vacío.");
        }
        return downloadInternal(stream, output, new NetworkFetcher(stream.refererOrigin), true);
    }

    /** Resolves and downloads an HLS URL in one operation. */
    public static ResolvedStream downloadToStream(
            String playlistUrl,
            boolean dataSaver,
            OutputStream output) throws IOException {
        ResolvedStream stream = resolve(playlistUrl, dataSaver);
        downloadToStream(stream, output);
        return stream;
    }

    static ResolvedStream resolveForTests(
            String playlistUrl,
            boolean dataSaver,
            ResourceFetcher fetcher) throws IOException {
        String normalized = normalizeHttpsUrl(playlistUrl, false);
        return resolveInternal(
                normalized,
                dataSaver ? QualityPreference.DATA_SAVER : QualityPreference.BEST,
                fetcher,
                false,
                null);
    }

    static long downloadToStreamForTests(
            ResolvedStream stream,
            OutputStream output,
            ResourceFetcher fetcher) throws IOException {
        return downloadInternal(stream, output, fetcher, false);
    }

    private static ResolvedStream resolveInternal(
            String sourceUrl,
            QualityPreference preference,
            ResourceFetcher fetcher,
            boolean validateDns,
            String refererOrigin) throws IOException {
        if (fetcher == null) {
            throw new IllegalArgumentException("El lector de recursos no puede estar vacío.");
        }

        String current = sourceUrl;
        Set<String> visited = new LinkedHashSet<>();
        Variant inheritedVariant = null;
        for (int depth = 0; depth < MAX_PLAYLIST_DEPTH; depth++) {
            checkInterrupted();
            String requested = current;
            if (!visited.add(requested)) {
                throw new IOException("La lista HLS contiene un ciclo de variantes.");
            }

            PlaylistDocument document = fetchPlaylist(requested, fetcher, validateDns);
            current = document.finalUrl;
            if (!current.equals(requested) && !visited.add(current)) {
                throw new IOException("La lista HLS redirige a una variante ya visitada.");
            }
            PlaylistKind kind = classify(document.text);
            rejectEncryption(document.text);
            if (kind == PlaylistKind.MASTER) {
                // Every advertised URL passes the shared syntax policy here. DNS is deliberately
                // revalidated immediately before the selected URL is actually requested.
                MasterPlaylist master = parseMaster(document.finalUrl, document.text, false);
                Variant selected = selectVariant(master.variants, master.externalAudioGroups, preference);
                current = selected.url;
                inheritedVariant = selected;
                continue;
            }
            if (kind == PlaylistKind.MEDIA) {
                MediaPlaylist media = parseMedia(document.finalUrl, document.text, false);
                long bandwidth = inheritedVariant == null ? 0L : inheritedVariant.bandwidth;
                int width = inheritedVariant == null ? 0 : inheritedVariant.width;
                int height = inheritedVariant == null ? 0 : inheritedVariant.height;
                return new ResolvedStream(
                        sourceUrl,
                        document.finalUrl,
                        media.container,
                        media.resources,
                        media.segmentCount,
                        Math.round(media.durationSeconds * 1000.0d),
                        bandwidth,
                        width,
                        height,
                        refererOrigin);
            }
            throw new IOException("El archivo no contiene una lista HLS compatible.");
        }
        throw new IOException("La lista HLS tiene demasiados niveles de variantes.");
    }

    private static PlaylistDocument fetchPlaylist(
            String url,
            ResourceFetcher fetcher,
            boolean validateDns) throws IOException {
        FetchResponse response = null;
        try {
            response = fetcher.fetch(url, null, true);
            if (response == null) {
                throw new IOException("El servidor no devolvió la lista HLS.");
            }
            String finalUrl = normalizeHttpsUrl(response.finalUrl, validateDns);
            if (response.statusCode != HttpURLConnection.HTTP_OK) {
                throw new IOException(
                        "No se pudo abrir la lista HLS (HTTP " + response.statusCode + ").");
            }
            requireIdentityEncoding(response.contentEncoding);
            if (response.contentLength > MAX_PLAYLIST_BYTES) {
                throw new IOException("La lista HLS supera el límite de seguridad.");
            }
            byte[] bytes = readFullyLimited(response.body, MAX_PLAYLIST_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
                text = text.substring(1);
            }
            validatePlaylistText(text);
            return new PlaylistDocument(finalUrl, text);
        } finally {
            closeQuietly(response);
        }
    }

    private static PlaylistKind classify(String text) throws IOException {
        boolean master = false;
        boolean media = false;
        for (String line : lines(text)) {
            String trimmed = line.trim();
            if (startsWithTag(trimmed, "#EXT-X-STREAM-INF")) {
                master = true;
            } else if (startsWithTag(trimmed, "#EXTINF")) {
                media = true;
            }
        }
        if (master && media) {
            throw new IOException("La lista HLS mezcla variantes y segmentos de forma ambigua.");
        }
        return master ? PlaylistKind.MASTER : (media ? PlaylistKind.MEDIA : PlaylistKind.UNKNOWN);
    }

    private static MasterPlaylist parseMaster(
            String baseUrl,
            String text,
            boolean validateDns) throws IOException {
        List<String> sourceLines = lines(text);
        Set<String> externalMediaGroups = new HashSet<>();
        for (String rawLine : sourceLines) {
            String line = rawLine.trim();
            if (!startsWithTag(line, "#EXT-X-MEDIA")) {
                continue;
            }
            Map<String, String> attributes = parseAttributeList(afterColon(line));
            if (("AUDIO".equalsIgnoreCase(attributes.get("TYPE"))
                    || "VIDEO".equalsIgnoreCase(attributes.get("TYPE")))
                    && !isBlank(attributes.get("GROUP-ID"))
                    && !isBlank(attributes.get("URI"))) {
                externalMediaGroups.add(attributes.get("GROUP-ID"));
            }
        }

        List<Variant> variants = new ArrayList<>();
        for (int index = 0; index < sourceLines.size(); index++) {
            String line = sourceLines.get(index).trim();
            if (!startsWithTag(line, "#EXT-X-STREAM-INF")) {
                continue;
            }
            if (variants.size() >= MAX_VARIANTS) {
                throw new IOException("La lista HLS contiene demasiadas variantes.");
            }
            Map<String, String> attributes = parseAttributeList(afterColon(line));
            String uriLine = null;
            for (int candidate = index + 1; candidate < sourceLines.size(); candidate++) {
                String next = sourceLines.get(candidate).trim();
                if (next.isEmpty()) {
                    continue;
                }
                if (next.startsWith("#")) {
                    if (startsWithTag(next, "#EXT-X-STREAM-INF")) {
                        break;
                    }
                    continue;
                }
                uriLine = next;
                index = candidate;
                break;
            }
            if (isBlank(uriLine)) {
                throw new IOException("Una variante HLS no tiene dirección.");
            }
            String variantUrl = resolveHttpsReference(baseUrl, uriLine, validateDns);
            long declaredBandwidth = positiveLong(attributes.get("BANDWIDTH"), -1L);
            if (declaredBandwidth <= 0L) {
                throw new IOException("Una variante HLS no declara BANDWIDTH.");
            }
            long bandwidth = positiveLong(
                    attributes.get("AVERAGE-BANDWIDTH"), declaredBandwidth);
            int[] resolution = parseResolution(attributes.get("RESOLUTION"));
            variants.add(new Variant(
                    variantUrl,
                    bandwidth,
                    resolution[0],
                    resolution[1],
                    attributes.get("AUDIO"),
                    attributes.get("VIDEO"),
                    isKnownAudioOnly(attributes.get("CODECS"), resolution)));
        }
        if (variants.isEmpty()) {
            throw new IOException("La lista maestra HLS no contiene variantes reproducibles.");
        }
        return new MasterPlaylist(variants, externalMediaGroups);
    }

    private static Variant selectVariant(
            List<Variant> variants,
            Set<String> externalMediaGroups,
            QualityPreference preference) throws IOException {
        List<Variant> compatible = new ArrayList<>();
        for (Variant variant : variants) {
            boolean externalAudio = !isBlank(variant.audioGroup)
                    && externalMediaGroups.contains(variant.audioGroup);
            boolean externalVideo = !isBlank(variant.videoGroup)
                    && externalMediaGroups.contains(variant.videoGroup);
            if (!externalAudio && !externalVideo && !variant.knownAudioOnly) {
                compatible.add(variant);
            }
        }
        if (compatible.isEmpty()) {
            throw new IOException(
                    "Las variantes HLS separan el audio y el vídeo y necesitan un mezclador multimedia.");
        }
        boolean everyVariantDeclaresResolution = true;
        for (Variant variant : compatible) {
            if ((long) variant.width * (long) variant.height <= 0L) {
                everyVariantDeclaresResolution = false;
                break;
            }
        }
        final boolean rankBestByResolution = everyVariantDeclaresResolution;
        Comparator<Variant> comparator = new Comparator<Variant>() {
            @Override
            public int compare(Variant left, Variant right) {
                long leftPixels = (long) left.width * (long) left.height;
                long rightPixels = (long) right.width * (long) right.height;
                if (rankBestByResolution) {
                    int pixels = Long.compare(leftPixels, rightPixels);
                    if (pixels != 0) {
                        return pixels;
                    }
                }
                int bandwidth = Long.compare(left.bandwidth, right.bandwidth);
                if (bandwidth != 0) {
                    return bandwidth;
                }
                // Use one ordering for the complete candidate set. Mixing a pairwise
                // resolution rule with a bandwidth rule makes the comparator non-transitive
                // when only some renditions omit RESOLUTION.
                int pixels = Long.compare(leftPixels, rightPixels);
                if (pixels != 0) {
                    return pixels;
                }
                return left.url.compareTo(right.url);
            }
        };
        if (preference == QualityPreference.DATA_SAVER) {
            comparator = new Comparator<Variant>() {
                @Override
                public int compare(Variant left, Variant right) {
                    long leftBandwidth = left.bandwidth > 0 ? left.bandwidth : Long.MAX_VALUE;
                    long rightBandwidth = right.bandwidth > 0 ? right.bandwidth : Long.MAX_VALUE;
                    int bandwidth = Long.compare(leftBandwidth, rightBandwidth);
                    if (bandwidth != 0) {
                        return bandwidth;
                    }
                    long leftPixels = (long) left.width * (long) left.height;
                    long rightPixels = (long) right.width * (long) right.height;
                    int pixels = Long.compare(leftPixels, rightPixels);
                    return pixels != 0 ? pixels : left.url.compareTo(right.url);
                }
            };
        }
        return preference == QualityPreference.DATA_SAVER
                ? Collections.min(compatible, comparator)
                : Collections.max(compatible, comparator);
    }

    private static MediaPlaylist parseMedia(
            String baseUrl,
            String text,
            boolean validateDns) throws IOException {
        List<Segment> segments = new ArrayList<>();
        Resource currentMap = null;
        Resource firstMap = null;
        ByteRange pendingRange = null;
        ByteRange previousSegmentRange = null;
        String previousSegmentUrl = null;
        double pendingDuration = -1.0d;
        double totalDuration = 0.0d;
        boolean endList = false;
        boolean gap = false;
        long mediaSequence = 0L;
        boolean stablePlaylistType = false;

        for (String rawLine : lines(text)) {
            checkInterrupted();
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (startsWithTag(line, "#EXT-X-ENDLIST")) {
                endList = true;
                continue;
            }
            if (startsWithTag(line, "#EXT-X-MEDIA-SEQUENCE")) {
                try {
                    mediaSequence = Long.parseLong(afterColon(line));
                } catch (NumberFormatException invalid) {
                    throw new IOException("EXT-X-MEDIA-SEQUENCE no es válido.", invalid);
                }
                if (mediaSequence < 0L) {
                    throw new IOException("EXT-X-MEDIA-SEQUENCE no es válido.");
                }
                continue;
            }
            if (startsWithTag(line, "#EXT-X-PLAYLIST-TYPE")) {
                String type = afterColon(line);
                if (!"VOD".equalsIgnoreCase(type) && !"EVENT".equalsIgnoreCase(type)) {
                    throw new IOException("EXT-X-PLAYLIST-TYPE no es compatible.");
                }
                stablePlaylistType = true;
                continue;
            }
            if (startsWithTag(line, "#EXT-X-MAP")) {
                Map<String, String> attributes = parseAttributeList(afterColon(line));
                String uri = attributes.get("URI");
                if (isBlank(uri)) {
                    throw new IOException("EXT-X-MAP no contiene una dirección válida.");
                }
                String mapUrl = resolveHttpsReference(baseUrl, uri, validateDns);
                ByteRange range = parseMapByteRange(attributes.get("BYTERANGE"));
                currentMap = new Resource(mapUrl, range, true);
                if (firstMap == null) {
                    firstMap = currentMap;
                }
                continue;
            }
            if (startsWithTag(line, "#EXT-X-BYTERANGE")) {
                if (pendingRange != null) {
                    throw new IOException("Un segmento HLS repite EXT-X-BYTERANGE.");
                }
                pendingRange = parseByteRange(afterColon(line), false);
                continue;
            }
            if (startsWithTag(line, "#EXT-X-GAP")) {
                gap = true;
                continue;
            }
            if (startsWithTag(line, "#EXTINF")) {
                if (pendingDuration >= 0.0d) {
                    throw new IOException("Un segmento HLS repite EXTINF.");
                }
                String value = afterColon(line);
                int comma = value.indexOf(',');
                if (comma >= 0) {
                    value = value.substring(0, comma);
                }
                pendingDuration = finiteDouble(value, "duración de segmento");
                if (pendingDuration < 0.0d || pendingDuration > MAX_TOTAL_DURATION_SECONDS) {
                    throw new IOException("La duración de un segmento HLS no es válida.");
                }
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (pendingDuration < 0.0d) {
                throw new IOException("Un segmento HLS no tiene EXTINF.");
            }
            if (gap) {
                throw new IOException("La lista HLS contiene segmentos ausentes (EXT-X-GAP).");
            }
            if (segments.size() >= MAX_SEGMENTS) {
                throw new IOException("La lista HLS contiene demasiados segmentos.");
            }

            String segmentUrl = resolveHttpsReference(baseUrl, line, validateDns);
            ByteRange resolvedRange = pendingRange;
            if (resolvedRange != null && resolvedRange.offset < 0L) {
                if (previousSegmentRange == null || !segmentUrl.equals(previousSegmentUrl)) {
                    throw new IOException(
                            "Un rango HLS implícito no continúa el mismo recurso.");
                }
                resolvedRange = new ByteRange(
                        resolvedRange.length,
                        safeAdd(previousSegmentRange.offset, previousSegmentRange.length));
            }
            Segment segment = new Segment(
                    new Resource(segmentUrl, resolvedRange, false),
                    currentMap,
                    pendingDuration);
            segments.add(segment);
            previousSegmentRange = resolvedRange;
            previousSegmentUrl = segmentUrl;
            totalDuration += pendingDuration;
            if (!Double.isFinite(totalDuration) || totalDuration > MAX_TOTAL_DURATION_SECONDS) {
                throw new IOException("La duración total HLS supera el límite de seguridad.");
            }
            pendingDuration = -1.0d;
            pendingRange = null;
            gap = false;
        }

        if (!endList) {
            throw new IOException(
                    "La emisión HLS sigue en directo; solo se descargan vídeos finalizados.");
        }
        if (mediaSequence != 0L && !stablePlaylistType) {
            throw new IOException(
                    "La lista HLS puede ser solo la parte final de una emisión; se ha rechazado.");
        }
        if (segments.isEmpty()) {
            throw new IOException("La lista HLS finalizada no contiene segmentos.");
        }
        if (pendingDuration >= 0.0d || pendingRange != null || gap) {
            throw new IOException("La lista HLS termina con un segmento incompleto.");
        }

        Container container;
        List<Resource> resources = new ArrayList<>();
        boolean everySegmentLooksTs = true;
        for (Segment segment : segments) {
            everySegmentLooksTs &= looksLikeTransportStream(segment.resource.url);
        }
        if (firstMap != null && everySegmentLooksTs) {
            // RFC 8216 also permits EXT-X-MAP with MPEG-TS (normally PAT/PMT data). Insert each
            // changed initialization section at the exact point where it starts applying.
            container = Container.MPEG_TS;
            Resource lastWrittenMap = null;
            for (Segment segment : segments) {
                if (segment.map != null
                        && (lastWrittenMap == null || !lastWrittenMap.sameLocation(segment.map))) {
                    resources.add(segment.map);
                    lastWrittenMap = segment.map;
                }
                resources.add(segment.resource);
            }
        } else if (firstMap != null) {
            container = Container.FRAGMENTED_MP4;
            resources.add(firstMap);
            for (Segment segment : segments) {
                if (segment.map == null || !firstMap.sameLocation(segment.map)) {
                    throw new IOException(
                            "Todos los fragmentos fMP4 deben compartir el mismo EXT-X-MAP.");
                }
                if (looksLikeTransportStream(segment.resource.url)) {
                    throw new IOException("La lista mezcla un mapa fMP4 con segmentos MPEG-TS.");
                }
                resources.add(segment.resource);
            }
        } else {
            container = Container.MPEG_TS;
            for (Segment segment : segments) {
                if (looksLikeFragmentedMp4(segment.resource.url)
                        || looksLikeUnsupportedMedia(segment.resource.url)) {
                    throw new IOException(
                            "Los fragmentos MP4 necesitan EXT-X-MAP para formar un archivo válido.");
                }
                resources.add(segment.resource);
            }
        }
        return new MediaPlaylist(container, resources, segments.size(), totalDuration);
    }

    private static void rejectEncryption(String text) throws IOException {
        for (String rawLine : lines(text)) {
            String line = rawLine.trim();
            if (startsWithTag(line, "#EXT-X-SESSION-KEY")) {
                throw new IOException("La lista HLS usa cifrado o DRM y no puede descargarse.");
            }
            if (startsWithTag(line, "#EXT-X-I-FRAMES-ONLY")
                    || startsWithTag(line, "#EXT-X-SKIP")
                    || startsWithTag(line, "#EXT-X-DISCONTINUITY")) {
                throw new IOException(
                        "La lista HLS necesita remultiplexado y no se puede unir de forma segura.");
            }
            if (startsWithTag(line, "#EXT-X-KEY")) {
                Map<String, String> attributes = parseAttributeList(afterColon(line));
                String method = attributes.get("METHOD");
                if (!"NONE".equalsIgnoreCase(method) || attributes.size() != 1) {
                    throw new IOException("La lista HLS usa cifrado o DRM y no puede descargarse.");
                }
            }
            if (line.indexOf("{$") >= 0) {
                throw new IOException("La lista HLS usa variables de URL no compatibles.");
            }
        }
    }

    private static long downloadInternal(
            ResolvedStream stream,
            OutputStream output,
            ResourceFetcher fetcher,
            boolean validateDns) throws IOException {
        if (stream.resources.isEmpty()) {
            throw new IOException("La lista HLS no contiene recursos descargables.");
        }
        long total = 0L;
        for (int index = 0; index < stream.resources.size(); index++) {
            checkInterrupted();
            Resource resource = stream.resources.get(index);
            FetchResponse response = null;
            try {
                response = fetcher.fetch(resource.url, resource.range, false);
                if (response == null) {
                    throw new IOException("El servidor no devolvió un segmento HLS.");
                }
                normalizeHttpsUrl(response.finalUrl, validateDns);
                validateMediaResponse(response, resource);
                long declared = response.contentLength;
                if (declared > MAX_RESOURCE_BYTES) {
                    throw new IOException("Un segmento HLS supera el límite de seguridad.");
                }
                if (declared >= 0L && safeAdd(total, declared) > MAX_TOTAL_BYTES) {
                    throw new IOException("La descarga HLS supera el límite total de seguridad.");
                }

                byte[] prefix = readPrefix(response.body, SIGNATURE_PREFIX_BYTES);
                if (prefix.length == 0) {
                    throw new IOException("El servidor devolvió un segmento HLS vacío.");
                }
                if (stream.container == Container.MPEG_TS) {
                    validateTransportStreamPrefix(prefix);
                } else if (resource.map) {
                    validateFragmentedMp4Map(prefix);
                } else {
                    validateFragmentedMp4Segment(prefix);
                }
                long resourceBytes = prefix.length;
                long totalAfterPrefix = safeAdd(total, prefix.length);
                if (resourceBytes > MAX_RESOURCE_BYTES || totalAfterPrefix > MAX_TOTAL_BYTES) {
                    throw new IOException("La descarga HLS supera el límite de seguridad.");
                }
                output.write(prefix);
                total = totalAfterPrefix;

                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = response.body.read(buffer)) != -1) {
                    checkInterrupted();
                    resourceBytes = safeAdd(resourceBytes, read);
                    total = safeAdd(total, read);
                    if (resourceBytes > MAX_RESOURCE_BYTES || total > MAX_TOTAL_BYTES) {
                        throw new IOException("La descarga HLS supera el límite de seguridad.");
                    }
                    output.write(buffer, 0, read);
                }
                long expected = resource.range == null ? declared : resource.range.length;
                if (expected >= 0L && resourceBytes != expected) {
                    throw new IOException("Un segmento HLS llegó incompleto.");
                }
            } finally {
                closeQuietly(response);
            }
        }
        output.flush();
        return total;
    }

    private static void validateMediaResponse(FetchResponse response, Resource resource)
            throws IOException {
        requireIdentityEncoding(response.contentEncoding);
        if (resource.range == null) {
            if (response.statusCode != HttpURLConnection.HTTP_OK) {
                throw new IOException(
                        "No se pudo descargar un segmento HLS (HTTP "
                                + response.statusCode + ").");
            }
            return;
        }
        if (response.statusCode != HttpURLConnection.HTTP_PARTIAL) {
            throw new IOException("El servidor no respetó un rango HLS necesario.");
        }
        ContentRange parsed = parseContentRange(response.contentRange);
        long expectedEnd = safeAdd(resource.range.offset, resource.range.length - 1L);
        if (parsed.start != resource.range.offset || parsed.end != expectedEnd) {
            throw new IOException("El servidor devolvió un rango HLS distinto del solicitado.");
        }
        if (response.contentLength >= 0L && response.contentLength != resource.range.length) {
            throw new IOException("El tamaño del rango HLS no coincide con la respuesta.");
        }
    }

    private static void requireIdentityEncoding(String contentEncoding) throws IOException {
        if (!isBlank(contentEncoding)
                && !"identity".equalsIgnoreCase(contentEncoding.trim())) {
            throw new IOException(
                    "El servidor comprimió la respuesta de una forma no compatible.");
        }
    }

    private static ContentRange parseContentRange(String value) throws IOException {
        if (isBlank(value)) {
            throw new IOException("La respuesta parcial no contiene Content-Range.");
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        if (!normalized.startsWith("bytes ")) {
            throw new IOException("Content-Range no es válido.");
        }
        int dash = normalized.indexOf('-', 6);
        int slash = normalized.indexOf('/', dash + 1);
        if (dash < 7 || slash < dash + 2) {
            throw new IOException("Content-Range no es válido.");
        }
        try {
            long start = Long.parseLong(normalized.substring(6, dash).trim());
            long end = Long.parseLong(normalized.substring(dash + 1, slash).trim());
            if (start < 0L || end < start) {
                throw new NumberFormatException();
            }
            return new ContentRange(start, end);
        } catch (NumberFormatException error) {
            throw new IOException("Content-Range no es válido.", error);
        }
    }

    private static void validateTransportStreamPrefix(byte[] prefix) throws IOException {
        if (prefix.length < 188 || (prefix[0] & 0xff) != 0x47) {
            throw new IOException("Un segmento no contiene transporte MPEG-TS válido.");
        }
        if (prefix.length > 188 && (prefix[188] & 0xff) != 0x47) {
            throw new IOException("Un segmento MPEG-TS perdió la sincronización.");
        }
        if (prefix.length > 376 && (prefix[376] & 0xff) != 0x47) {
            throw new IOException("Un segmento MPEG-TS perdió la sincronización.");
        }
    }

    private static void validateFragmentedMp4Map(byte[] prefix) throws IOException {
        if (!MediaValidator.looksLikeIsoVideo(prefix) || !hasIsoBox(prefix, "moov")) {
            throw new IOException("EXT-X-MAP no contiene una cabecera MP4 válida.");
        }
    }

    private static void validateFragmentedMp4Segment(byte[] prefix) throws IOException {
        if (!hasIsoBox(prefix, "moof") || !hasIsoBox(prefix, "mdat")) {
            throw new IOException("Un fragmento HLS no contiene cajas MP4 moof/mdat válidas.");
        }
    }

    private static boolean hasIsoBox(byte[] data, String wanted) {
        int offset = 0;
        int inspected = 0;
        while (offset + 8 <= data.length && inspected < 32) {
            long size = unsignedInt(data, offset);
            String type = new String(data, offset + 4, 4, StandardCharsets.US_ASCII);
            int headerSize = 8;
            if (size == 1L) {
                if (offset + 16 > data.length) {
                    return false;
                }
                size = unsignedLongWithinSignedRange(data, offset + 8);
                headerSize = 16;
            } else if (size == 0L) {
                size = data.length - offset;
            }
            if (size < headerSize || size > Integer.MAX_VALUE) {
                return false;
            }
            if (wanted.equals(type)) {
                return true;
            }
            if (offset + size > data.length) {
                return false;
            }
            offset += (int) size;
            inspected++;
        }
        return false;
    }

    private static long unsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (long) (data[offset + 3] & 0xff);
    }

    private static long unsignedLongWithinSignedRange(byte[] data, int offset) {
        if ((data[offset] & 0x80) != 0) {
            return -1L;
        }
        long value = 0L;
        for (int index = 0; index < 8; index++) {
            value = (value << 8) | (long) (data[offset + index] & 0xff);
        }
        return value;
    }

    private static byte[] readPrefix(InputStream input, int maximum) throws IOException {
        if (input == null) {
            throw new IOException("El servidor no entregó datos multimedia.");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(maximum);
        byte[] buffer = new byte[Math.min(8192, maximum)];
        while (output.size() < maximum) {
            int requested = Math.min(buffer.length, maximum - output.size());
            int read = input.read(buffer, 0, requested);
            if (read == -1) {
                break;
            }
            if (read == 0) {
                continue;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] readFullyLimited(InputStream input, long maximum) throws IOException {
        if (input == null) {
            throw new IOException("El servidor no entregó la lista HLS.");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total = safeAdd(total, read);
            if (total > maximum) {
                throw new IOException("La lista HLS supera el límite de seguridad.");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validatePlaylistText(String text) throws IOException {
        if (isBlank(text)) {
            throw new IOException("La lista HLS está vacía.");
        }
        List<String> sourceLines = lines(text);
        String first = null;
        for (String rawLine : sourceLines) {
            String line = rawLine.trim();
            if (!line.isEmpty()) {
                first = line;
                break;
            }
        }
        if (!"#EXTM3U".equals(first)) {
            throw new IOException("El archivo no empieza con #EXTM3U.");
        }
        for (String line : sourceLines) {
            if (line.length() > MAX_LINE_LENGTH) {
                throw new IOException("La lista HLS contiene una línea demasiado larga.");
            }
            for (int index = 0; index < line.length(); index++) {
                char character = line.charAt(index);
                if ((character < 0x20 && character != '\t') || character == 0x7f) {
                    throw new IOException("La lista HLS contiene caracteres de control.");
                }
            }
        }
    }

    private static List<String> lines(String text) {
        String[] split = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> result = new ArrayList<>(split.length);
        Collections.addAll(result, split);
        return result;
    }

    private static Map<String, String> parseAttributeList(String text) throws IOException {
        Map<String, String> result = new HashMap<>();
        int index = 0;
        while (index < text.length()) {
            while (index < text.length()
                    && (text.charAt(index) == ',' || Character.isWhitespace(text.charAt(index)))) {
                index++;
            }
            if (index >= text.length()) {
                break;
            }
            int equals = text.indexOf('=', index);
            if (equals <= index) {
                throw new IOException("Una etiqueta HLS contiene atributos no válidos.");
            }
            String key = text.substring(index, equals).trim().toUpperCase(Locale.US);
            if (key.isEmpty()) {
                throw new IOException("Una etiqueta HLS contiene un atributo vacío.");
            }
            index = equals + 1;
            String value;
            if (index < text.length() && text.charAt(index) == '"') {
                index++;
                StringBuilder quoted = new StringBuilder();
                boolean closed = false;
                while (index < text.length()) {
                    char character = text.charAt(index++);
                    if (character == '"') {
                        closed = true;
                        break;
                    }
                    if (character == '\r' || character == '\n') {
                        throw new IOException("Un atributo HLS entrecomillado no es válido.");
                    }
                    quoted.append(character);
                }
                if (!closed) {
                    throw new IOException("Un atributo HLS no cierra sus comillas.");
                }
                value = quoted.toString();
            } else {
                int comma = text.indexOf(',', index);
                if (comma < 0) {
                    comma = text.length();
                }
                value = text.substring(index, comma).trim();
                index = comma;
            }
            if (result.put(key, value) != null) {
                throw new IOException("Una etiqueta HLS repite el atributo " + key + ".");
            }
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index < text.length()) {
                if (text.charAt(index) != ',') {
                    throw new IOException("Una etiqueta HLS contiene atributos no válidos.");
                }
                index++;
            }
        }
        return result;
    }

    private static ByteRange parseMapByteRange(String value) throws IOException {
        if (isBlank(value)) {
            return null;
        }
        ByteRange range = parseByteRange(value, true);
        if (range.offset < 0L) {
            throw new IOException(
                    "EXT-X-MAP necesita un desplazamiento BYTERANGE explícito.");
        }
        return range;
    }

    private static ByteRange parseByteRange(String value, boolean quotedValue)
            throws IOException {
        if (isBlank(value)) {
            throw new IOException("Un rango HLS está vacío.");
        }
        String normalized = value.trim();
        if (quotedValue && normalized.length() >= 2
                && normalized.charAt(0) == '"'
                && normalized.charAt(normalized.length() - 1) == '"') {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        int at = normalized.indexOf('@');
        String lengthText = at < 0 ? normalized : normalized.substring(0, at);
        String offsetText = at < 0 ? null : normalized.substring(at + 1);
        try {
            long length = Long.parseLong(lengthText.trim());
            long offset = offsetText == null ? -1L : Long.parseLong(offsetText.trim());
            if (length <= 0L || length > MAX_RESOURCE_BYTES || offset < -1L) {
                throw new NumberFormatException();
            }
            if (offset >= 0L) {
                safeAdd(offset, length - 1L);
            }
            return new ByteRange(length, offset);
        } catch (NumberFormatException error) {
            throw new IOException("Un rango HLS no es válido.", error);
        }
    }

    private static int[] parseResolution(String value) throws IOException {
        if (isBlank(value)) {
            return new int[]{0, 0};
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        int separator = normalized.indexOf('x');
        if (separator <= 0 || separator >= normalized.length() - 1) {
            throw new IOException("Una resolución HLS no es válida.");
        }
        try {
            int width = Integer.parseInt(normalized.substring(0, separator));
            int height = Integer.parseInt(normalized.substring(separator + 1));
            if (width <= 0 || height <= 0 || width > 16_384 || height > 16_384) {
                throw new NumberFormatException();
            }
            return new int[]{width, height};
        } catch (NumberFormatException error) {
            throw new IOException("Una resolución HLS no es válida.", error);
        }
    }

    private static boolean isKnownAudioOnly(String codecs, int[] resolution) {
        if (isBlank(codecs) || resolution[0] > 0 || resolution[1] > 0) {
            return false;
        }
        String normalized = codecs.toLowerCase(Locale.US);
        String[] videoMarkers = {
                "avc1", "avc3", "hev1", "hvc1", "dvh1", "dvhe",
                "vp8", "vp9", "vp09", "av01", "theora"
        };
        for (String marker : videoMarkers) {
            if (normalized.contains(marker)) {
                return false;
            }
        }
        return normalized.contains("mp4a")
                || normalized.contains("aac")
                || normalized.contains("ac-3")
                || normalized.contains("ec-3")
                || normalized.contains("opus")
                || normalized.contains("vorbis");
    }

    private static long positiveLong(String value, long fallback) throws IOException {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0L) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException("Un ancho de banda HLS no es válido.", error);
        }
    }

    private static double finiteDouble(String value, String label) throws IOException {
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (RuntimeException error) {
            throw new IOException("La " + label + " HLS no es válida.", error);
        }
    }

    private static String resolveHttpsReference(
            String baseUrl,
            String reference,
            boolean validateDns) throws IOException {
        if (isBlank(reference)) {
            throw new IOException("Una dirección HLS está vacía.");
        }
        try {
            URI base = new URI(baseUrl);
            URI resolved = base.resolve(new URI(reference.trim()));
            return normalizeHttpsUrl(resolved.toASCIIString(), validateDns);
        } catch (URISyntaxException error) {
            throw new IOException("Una dirección HLS no es válida.", error);
        }
    }

    private static String normalizeHttpsUrl(String raw, boolean validateDns) throws IOException {
        URI safe = validateDns
                ? PublicWebUrlPolicy.requirePublicHttps(raw)
                : PublicWebUrlPolicy.requirePublicHttpsSyntax(raw, false);
        return safe.toASCIIString();
    }

    private static boolean looksLikeFragmentedMp4(String url) {
        String path = lowerPath(url);
        return path.endsWith(".m4s") || path.endsWith(".mp4")
                || path.endsWith(".cmfv") || path.endsWith(".cmfa");
    }

    private static boolean looksLikeTransportStream(String url) {
        return lowerPath(url).endsWith(".ts");
    }

    private static boolean looksLikeUnsupportedMedia(String url) {
        String path = lowerPath(url);
        return path.endsWith(".aac") || path.endsWith(".m4a")
                || path.endsWith(".mp3") || path.endsWith(".vtt")
                || path.endsWith(".webvtt") || path.endsWith(".jpg")
                || path.endsWith(".jpeg") || path.endsWith(".png");
    }

    private static String lowerPath(String url) {
        try {
            String path = new URI(url).getPath();
            return path == null ? "" : path.toLowerCase(Locale.US);
        } catch (URISyntaxException error) {
            return "";
        }
    }

    private static String afterColon(String line) throws IOException {
        int colon = line.indexOf(':');
        if (colon < 0 || colon == line.length() - 1) {
            throw new IOException("Una etiqueta HLS no contiene atributos.");
        }
        return line.substring(colon + 1).trim();
    }

    private static boolean startsWithTag(String line, String tag) {
        return line.equalsIgnoreCase(tag)
                || (line.length() > tag.length()
                && line.regionMatches(true, 0, tag, 0, tag.length())
                && line.charAt(tag.length()) == ':');
    }

    private static long safeAdd(long left, long right) throws IOException {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            throw new IOException("Un tamaño HLS desborda el límite admitido.");
        }
        return left + right;
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("La descarga HLS fue cancelada.");
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Preserve the original operation result.
        }
    }

    interface ResourceFetcher {
        FetchResponse fetch(String url, ByteRange range, boolean playlist) throws IOException;
    }

    static final class FetchResponse implements Closeable {
        final String finalUrl;
        final int statusCode;
        final long contentLength;
        final String contentRange;
        final String contentEncoding;
        final InputStream body;
        private final HttpURLConnection connection;

        FetchResponse(
                String finalUrl,
                int statusCode,
                long contentLength,
                String contentRange,
                InputStream body) {
            this(finalUrl, statusCode, contentLength, contentRange, null, body, null);
        }

        FetchResponse(
                String finalUrl,
                int statusCode,
                long contentLength,
                String contentRange,
                String contentEncoding,
                InputStream body) {
            this(
                    finalUrl,
                    statusCode,
                    contentLength,
                    contentRange,
                    contentEncoding,
                    body,
                    null);
        }

        private FetchResponse(
                String finalUrl,
                int statusCode,
                long contentLength,
                String contentRange,
                String contentEncoding,
                InputStream body,
                HttpURLConnection connection) {
            this.finalUrl = finalUrl;
            this.statusCode = statusCode;
            this.contentLength = contentLength;
            this.contentRange = contentRange;
            this.contentEncoding = contentEncoding;
            this.body = body;
            this.connection = connection;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            if (body != null) {
                try {
                    body.close();
                } catch (IOException error) {
                    failure = error;
                }
            }
            if (connection != null) {
                DownloadControl.release(connection);
                connection.disconnect();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    static final class ByteRange {
        final long length;
        final long offset;

        ByteRange(long length, long offset) {
            this.length = length;
            this.offset = offset;
        }
    }

    private static final class NetworkFetcher implements ResourceFetcher {
        private final String refererOrigin;

        NetworkFetcher(String refererOrigin) {
            this.refererOrigin = refererOrigin;
        }

        @Override
        public FetchResponse fetch(String url, ByteRange range, boolean playlist)
                throws IOException {
            String current = normalizeHttpsUrl(url, true);
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                checkInterrupted();
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(current).openConnection(Proxy.NO_PROXY);
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);
                    DownloadControl.track(connection);
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("User-Agent", USER_AGENT);
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    // Do not inherit a process-wide CookieHandler: generic public downloads are
                    // deliberately anonymous and never reuse browser or account state.
                    connection.setRequestProperty("Cookie", "");
                    connection.setRequestProperty("Cookie2", "");
                    if (!isBlank(refererOrigin)) {
                        connection.setRequestProperty("Origin", refererOrigin);
                        connection.setRequestProperty("Referer", refererOrigin + "/");
                    }
                    connection.setRequestProperty(
                            "Accept",
                            playlist
                                    ? "application/vnd.apple.mpegurl,application/x-mpegURL,text/plain,*/*;q=0.5"
                                    : "video/*,application/octet-stream,*/*;q=0.5");
                    if (range != null) {
                        long end = safeAdd(range.offset, range.length - 1L);
                        connection.setRequestProperty("Range", "bytes=" + range.offset + "-" + end);
                    }
                    int status = connection.getResponseCode();
                    if (isRedirect(status)) {
                        String location = connection.getHeaderField("Location");
                        if (isBlank(location)) {
                            throw new IOException("El servidor HLS redirigió sin destino.");
                        }
                        if (redirect == MAX_REDIRECTS) {
                            throw new IOException("El servidor HLS hizo demasiadas redirecciones.");
                        }
                        current = resolveHttpsReference(current, location, true);
                        DownloadControl.release(connection);
                        connection.disconnect();
                        connection = null;
                        continue;
                    }
                    InputStream body = status >= 200 && status < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream();
                    return new FetchResponse(
                            current,
                            status,
                            connection.getHeaderFieldLong("Content-Length", -1L),
                            connection.getHeaderField("Content-Range"),
                            connection.getHeaderField("Content-Encoding"),
                            body,
                            connection);
                } catch (IOException error) {
                    if (connection != null) {
                        DownloadControl.release(connection);
                        connection.disconnect();
                    }
                    throw error;
                }
            }
            throw new IOException("El servidor HLS hizo demasiadas redirecciones.");
        }

        private boolean isRedirect(int status) {
            return status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_SEE_OTHER
                    || status == 307
                    || status == 308;
        }
    }

    private enum Container {
        MPEG_TS,
        FRAGMENTED_MP4
    }

    private enum PlaylistKind {
        MASTER,
        MEDIA,
        UNKNOWN
    }

    private static final class PlaylistDocument {
        final String finalUrl;
        final String text;

        PlaylistDocument(String finalUrl, String text) {
            this.finalUrl = finalUrl;
            this.text = text;
        }
    }

    private static final class MasterPlaylist {
        final List<Variant> variants;
        final Set<String> externalAudioGroups;

        MasterPlaylist(List<Variant> variants, Set<String> externalAudioGroups) {
            this.variants = variants;
            this.externalAudioGroups = externalAudioGroups;
        }
    }

    private static final class Variant {
        final String url;
        final long bandwidth;
        final int width;
        final int height;
        final String audioGroup;
        final String videoGroup;
        final boolean knownAudioOnly;

        Variant(
                String url,
                long bandwidth,
                int width,
                int height,
                String audioGroup,
                String videoGroup,
                boolean knownAudioOnly) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.width = width;
            this.height = height;
            this.audioGroup = audioGroup;
            this.videoGroup = videoGroup;
            this.knownAudioOnly = knownAudioOnly;
        }
    }

    private static final class Segment {
        final Resource resource;
        final Resource map;
        final double duration;

        Segment(Resource resource, Resource map, double duration) {
            this.resource = resource;
            this.map = map;
            this.duration = duration;
        }
    }

    private static final class Resource {
        final String url;
        final ByteRange range;
        final boolean map;

        Resource(String url, ByteRange range, boolean map) {
            this.url = url;
            this.range = range;
            this.map = map;
        }

        boolean sameLocation(Resource other) {
            if (other == null || map != other.map || !url.equals(other.url)) {
                return false;
            }
            if (range == null || other.range == null) {
                return range == other.range;
            }
            return range.length == other.range.length && range.offset == other.range.offset;
        }
    }

    private static final class MediaPlaylist {
        final Container container;
        final List<Resource> resources;
        final int segmentCount;
        final double durationSeconds;

        MediaPlaylist(
                Container container,
                List<Resource> resources,
                int segmentCount,
                double durationSeconds) {
            this.container = container;
            this.resources = resources;
            this.segmentCount = segmentCount;
            this.durationSeconds = durationSeconds;
        }
    }

    private static final class ContentRange {
        final long start;
        final long end;

        ContentRange(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }
}
