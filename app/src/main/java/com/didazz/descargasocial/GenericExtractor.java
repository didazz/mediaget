// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Descarga Social contributors
package com.didazz.descargasocial;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the most likely principal video from an arbitrary public HTTPS page.
 *
 * <p>This extractor intentionally does not execute JavaScript, use cookies, log in, bypass DRM or
 * contact an application server. It recognises media declared in HTML video/source elements,
 * OpenGraph, JSON-LD {@code VideoObject} data and direct MP4/WebM/HLS URLs exposed in scripts. A
 * deterministic scoring pass favours large, long and visible media and demotes advertising, GIF-like
 * loops, teasers and small clips. If every valid candidate is weak, the best one is kept as a clearly
 * marked fallback instead of silently returning an unrelated file.</p>
 *
 * <p>The nested result classes keep this module independent from the social-platform model. The app
 * can map {@link Media} to its normal UI model when the generic route is enabled.</p>
 */
public final class GenericExtractor {
    private static final int MAX_PAGE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_URL_CHARS = PublicWebUrlPolicy.MAX_URL_LENGTH;
    private static final int MAX_CANDIDATES = 32;
    private static final int MAX_SCRIPT_CHARS = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_SCRIPT_CHARS = 5 * 1024 * 1024;
    private static final int MAX_JSON_LD_CHARS = 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 48;
    private static final int MAX_JSON_NODES = 20_000;

    private static final Pattern TAG_PATTERN = Pattern.compile(
            "<\\s*(/?)\\s*(video|source)\\b([^>]*)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:([\"'])(.*?)\\2|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_PATTERN = Pattern.compile(
            "<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BASE_PATTERN = Pattern.compile(
            "<base\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_PATTERN = Pattern.compile(
            "<script\\b([^>]*)>(.*?)</script\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_COMMENT_PATTERN = Pattern.compile(
            "<!--.*?(?:-->|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MARKUP_INERT_BLOCK_PATTERN = Pattern.compile(
            "<(script|style|template|textarea|xmp)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_INERT_BLOCK_PATTERN = Pattern.compile(
            "<(style|template|textarea|xmp)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DIRECT_MEDIA_URL_PATTERN = Pattern.compile(
            "(?i)(?:https:)?//[^\\s\\\"'<>`\\\\]+?\\.(?:mp4|webm|m3u8)"
                    + "(?:\\?[^\\s\\\"'<>`\\\\]*)?");
    private static final Pattern RELATIVE_MEDIA_URL_PATTERN = Pattern.compile(
            "(?i)(?:^|[\\s\\\"'=:,(])((?:/|\\.\\.?/)[^\\s\\\"'<>`\\\\]*?"
                    + "\\.(?:mp4|webm|m3u8)(?:\\?[^\\s\\\"'<>`\\\\]*)?)");
    private static final Pattern ENCODED_MEDIA_URL_PATTERN = Pattern.compile(
            "(?i)https%3a%2f%2f[^\\s\\\"'<>`]+?%(?:2e|252e)(?:mp4|webm|m3u8)"
                    + "(?:%3f[^\\s\\\"'<>`]*)?");
    private static final Pattern OPAQUE_VIDEO_VALUE_PATTERN = Pattern.compile(
            "(?i)[\\\"']?(video[_-]?url(?:high|low)?|video[_-]?file|stream[_-]?url|"
                    + "hls[_-]?url|mp4[_-]?url)[\\\"']?\\s*[:=]\\s*[\\\"']"
                    + "([^\\\"']{1,8192})[\\\"']");
    private static final Pattern XPLAYER_SOURCE_VALUE_PATTERN = Pattern.compile(
            "(?i)[\\\"'](?:url|fallback)[\\\"']\\s*:\\s*[\\\"']([^\\\"']{12,16384})[\\\"']");
    private static final Pattern POSITIVE_CONTEXT_PATTERN = Pattern.compile(
            "(?i)(?:\\bmain(?:[-_ ]?video|[-_ ]?player)?\\b|\\bprimary\\b|\\bhero\\b|"
                    + "\\bfeatured\\b|\\bcontent[-_ ]?video\\b|\\bmovie[-_ ]?player\\b|"
                    + "\\bcontrols\\b)");
    private static final Pattern NEGATIVE_CONTEXT_PATTERN = Pattern.compile(
            "(?i)(?:\\bad(?:s|vert|vertisement)?\\b|\\bsponsor(?:ed)?\\b|\\bpre[-_ ]?roll\\b|"
                    + "\\bpost[-_ ]?roll\\b|\\bbanner\\b|\\bpromo(?:tion)?\\b|"
                    + "\\bvast\\b|\\bvpaid\\b|\\bad[-_ ]?url\\b|\\bvideo[-_ ]?ad\\b|"
                    + "\\brecommend(?:ed|ation)?\\b|\\brelated\\b|\\bteaser\\b|"
                    + "\\bpreview\\b|\\bthumbnail\\b|\\btracking\\b|\\bpixel\\b|"
                    + "\\bsprite\\b|\\bgif\\b|\\baria[-_ ]?hidden\\b|\\bhidden\\b|"
                    + "display\\s*:\\s*none|visibility\\s*:\\s*hidden|opacity\\s*:\\s*0(?:\\D|$))");
    private static final Pattern DRM_CONTEXT_PATTERN = Pattern.compile(
            "(?i)(?:\\bwidevine\\b|\\bfairplay\\b|\\bplayready\\b|"
                    + "\\bdrm(?:ad|video|stream|url)?\\b|"
                    + "license[_-]?url|license[_-]?server|keysystem)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile(
            "(?i)(?:quality|height|resolution|label)?[\\\"' _:=/-]{0,12}"
                    + "(144|240|360|480|540|576|720|1080|1440|2160|4320)p?\\b");
    private static final Pattern DIMENSIONS_PATTERN = Pattern.compile(
            "(?i)\\b(\\d{2,5})\\s*[x×]\\s*(\\d{2,5})\\b");
    private static final Pattern DURATION_CONTEXT_PATTERN = Pattern.compile(
            "(?i)(?:duration|length)[\\\"' _:=]{0,12}[\\\"']?([0-9]{1,7}(?:\\.[0-9]+)?)");
    private static final Pattern SIZE_CONTEXT_PATTERN = Pattern.compile(
            "(?i)(?:content[-_ ]?length|content[-_ ]?size|filesize|file[-_ ]?size|bytes)"
                    + "[\\\"' _:=]{0,12}[\\\"']?([0-9]{3,15}(?:\\.[0-9]+)?"
                    + "(?:\\s*(?:kb|kib|mb|mib|gb|gib|bytes?))?)");
    private static final Pattern FORMAT_CONTEXT_PATTERN = Pattern.compile(
            "(?i)(?:format|type|mime)[\\\"' _:=]{0,12}[\\\"']?"
                    + "(hls|m3u8|mp4|webm)\\b");
    private static final Pattern ISO_DURATION_PATTERN = Pattern.compile(
            "(?i)^P(?:(\\d+(?:\\.\\d+)?)D)?T(?:(\\d+(?:\\.\\d+)?)H)?"
                    + "(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?$");

    private GenericExtractor() {
    }

    /** Parses supplied HTML without network access. Useful for fixtures and deterministic tests. */
    public static Result parseHtml(String pageUrl, String html) throws Exception {
        return parse(html, new URI(pageUrl), false);
    }

    /** Static, network-free parser used by the generic HTTPS transport. */
    public static Result parse(String html, URI page) throws Exception {
        return parse(html, page, false);
    }

    /**
     * Static, network-free parser with an optional saver ordering among equally relevant renditions.
     */
    public static Result parse(String html, URI page, boolean dataSaver) throws Exception {
        if (page == null) {
            throw new IllegalArgumentException("La URL de la página no puede estar vacía.");
        }
        return finish(parseInternal(page.toASCIIString(), html), dataSaver);
    }

    private static ParseState parseInternal(String pageUrl, String html) throws Exception {
        URI page = safeSyntaxUri(pageUrl, "La página");
        if (html == null || html.trim().isEmpty()) {
            throw new IOException("La página no contiene HTML analizable.");
        }
        if (html.getBytes(StandardCharsets.UTF_8).length > MAX_PAGE_BYTES) {
            throw new IOException("La página es demasiado grande para analizarla con seguridad.");
        }

        String markup = maskInertMarkup(html, true);
        String scriptMarkup = maskInertMarkup(html, false);
        URI base = findBaseUri(page, markup);
        ParseState state = new ParseState(page.toString());
        parseVideoElements(state, base, markup);
        parseOpenGraph(state, base, markup);
        parseScripts(state, base, scriptMarkup);
        if (state.candidates.isEmpty()) {
            if (state.drmRejected > 0) {
                throw new IOException("El vídeo utiliza protección DRM y no se puede descargar.");
            }
            throw new IOException("No se encontró ningún vídeo público descargable en esta página.");
        }
        return state;
    }

    private static void parseVideoElements(ParseState state, URI base, String html) {
        Matcher matcher = TAG_PATTERN.matcher(html);
        VideoContext current = null;
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String tag = matcher.group(2).toLowerCase(Locale.ROOT);
            if (closing) {
                if ("video".equals(tag)) {
                    current = null;
                }
                continue;
            }
            Map<String, String> attrs = attributes(matcher.group(3));
            String nearby = precedingTagContext(html, matcher.start(), matcher.end());
            if ("video".equals(tag)) {
                current = VideoContext.from(attrs, base, nearby);
                for (String key : new String[] {"src", "data-src", "data-video-src", "data-url", "data-hls"}) {
                    addCandidate(
                            state,
                            base,
                            attrs.get(key),
                            attrs.get("type"),
                            current.poster,
                            current.width,
                            current.height,
                            current.durationSeconds,
                            parseSize(firstNonBlank(attrs.get("data-filesize"), attrs.get("data-size"))),
                            135,
                            "video",
                            current.context,
                            true);
                }
            } else {
                VideoContext owner = current == null ? VideoContext.empty(nearby) : current;
                for (String key : new String[] {"src", "data-src", "data-video-src", "data-url"}) {
                    addCandidate(
                            state,
                            base,
                            attrs.get(key),
                            firstNonBlank(attrs.get("type"), owner.mimeType),
                            firstNonBlank(resolveImage(base, attrs.get("poster")), owner.poster),
                            positiveInt(firstNonBlank(attrs.get("width"), attrs.get("data-width")), owner.width),
                            positiveInt(firstNonBlank(attrs.get("height"), attrs.get("data-height")), owner.height),
                            positiveDouble(firstNonBlank(attrs.get("duration"), attrs.get("data-duration")), owner.durationSeconds),
                            parseSize(firstNonBlank(attrs.get("data-filesize"), attrs.get("data-size"))),
                            130,
                            "source",
                            owner.context + " " + nearby + " " + matcher.group(3),
                            true);
                }
            }
        }
    }

    private static void parseOpenGraph(ParseState state, URI base, String html) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        Matcher matcher = META_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attrs = attributes(matcher.group());
            String key = firstNonBlank(attrs.get("property"), attrs.get("name"));
            String content = attrs.get("content");
            if (isBlank(key) || isBlank(content)) {
                continue;
            }
            key = key.trim().toLowerCase(Locale.ROOT);
            List<String> values = metadata.get(key);
            if (values == null) {
                values = new ArrayList<>();
                metadata.put(key, values);
            }
            if (values.size() < 16) {
                values.add(decodeHtml(content.trim()));
            }
        }

        String poster = firstMeta(metadata, "og:image:secure_url", "og:image", "twitter:image");
        poster = resolveImage(base, poster);
        int width = positiveInt(firstMeta(metadata, "og:video:width"), 0);
        int height = positiveInt(firstMeta(metadata, "og:video:height"), 0);
        double duration = positiveDouble(firstMeta(metadata, "video:duration", "og:video:duration"), 0);
        String mime = firstMeta(metadata, "og:video:type", "twitter:player:stream:content_type");
        for (String key : new String[] {
                "og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream"
        }) {
            List<String> values = metadata.get(key);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                addCandidate(
                        state, base, value, mime, poster, width, height, duration, 0,
                        165, "opengraph", key, true);
            }
        }
    }

    private static void parseScripts(ParseState state, URI base, String html) {
        Matcher matcher = SCRIPT_PATTERN.matcher(html);
        int total = 0;
        while (matcher.find() && total < MAX_TOTAL_SCRIPT_CHARS) {
            String attrsText = matcher.group(1);
            String body = matcher.group(2);
            if (body.length() > MAX_SCRIPT_CHARS) {
                body = body.substring(0, MAX_SCRIPT_CHARS);
            }
            int remaining = MAX_TOTAL_SCRIPT_CHARS - total;
            if (body.length() > remaining) {
                body = body.substring(0, remaining);
            }
            total += body.length();
            Map<String, String> attrs = attributes(attrsText);
            String type = lower(attrs.get("type"));
            if (type.contains("ld+json") && body.length() <= MAX_JSON_LD_CHARS) {
                parseJsonLd(state, base, body);
                continue;
            }
            parseXplayerSources(state, base, body);
            parseVisibleScriptUrls(state, base, body);
        }
    }

    private static void parseJsonLd(ParseState state, URI base, String source) {
        String json = decodeHtml(source.trim());
        if (json.isEmpty() || !hasAcceptableJsonDepth(json)) {
            return;
        }
        try {
            Object root = new MiniJsonParser(json, MAX_JSON_DEPTH, MAX_JSON_NODES).parse();
            walkJsonLd(state, base, root, 0, new int[] {0});
        } catch (RuntimeException ignored) {
            // Broken structured data must not prevent the other deterministic extraction routes.
        }
    }

    @SuppressWarnings("unchecked")
    private static void walkJsonLd(
            ParseState state, URI base, Object node, int depth, int[] visited) {
        if (node == null || depth > MAX_JSON_DEPTH || visited[0]++ > MAX_JSON_NODES
                ) {
            return;
        }
        if (node instanceof Map) {
            Map<String, Object> object = (Map<String, Object>) node;
            if (isVideoObject(object)) {
                addJsonLdVideo(state, base, object);
            }
            for (Object child : object.values()) {
                if (child instanceof Map || child instanceof List) {
                    walkJsonLd(state, base, child, depth + 1, visited);
                }
            }
        } else if (node instanceof List) {
            for (Object child : (List<Object>) node) {
                walkJsonLd(state, base, child, depth + 1, visited);
            }
        }
    }

    private static void addJsonLdVideo(ParseState state, URI base, Map<String, Object> object) {
        String objectText = object.toString();
        if (DRM_CONTEXT_PATTERN.matcher(objectText).find()) {
            state.drmRejected++;
            return;
        }
        String poster = jsonString(object, "thumbnailUrl", "thumbnail", "image");
        poster = resolveImage(base, poster);
        int width = jsonInt(object, "width");
        int height = jsonInt(object, "height");
        double duration = parseDuration(jsonString(object, "duration"));
        long size = parseSize(jsonString(object, "contentSize", "contentLength"));
        String mime = jsonString(object, "encodingFormat", "fileFormat");

        List<String> urls = new ArrayList<>();
        collectJsonStrings(object.get("contentUrl"), urls, 8);
        collectJsonStrings(object.get("contentURL"), urls, 8);
        if (urls.isEmpty()) {
            String semanticUrl = jsonString(object, "url", "embedUrl");
            if (looksLikeDirectMedia(semanticUrl, mime)) {
                urls.add(semanticUrl);
            }
        }
        for (String url : urls) {
            addCandidate(
                    state, base, url, mime, poster, width, height, duration, size,
                    175, "json-ld", objectText, true);
        }
        addJsonLdEncodings(
                state, base, object.get("encoding"), poster,
                width, height, duration, size, objectText, 0);
    }

    @SuppressWarnings("unchecked")
    private static void addJsonLdEncodings(
            ParseState state,
            URI base,
            Object node,
            String parentPoster,
            int parentWidth,
            int parentHeight,
            double parentDuration,
            long parentSize,
            String parentContext,
            int depth) {
        if (node == null || depth > 8) {
            return;
        }
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            String context = parentContext + " " + map.toString();
            if (DRM_CONTEXT_PATTERN.matcher(context).find()) {
                state.drmRejected++;
                return;
            }
            String mime = jsonString(map, "encodingFormat", "fileFormat");
            String poster = firstNonBlank(
                    resolveImage(base, jsonString(map, "thumbnailUrl", "thumbnail", "image")),
                    parentPoster);
            int width = jsonInt(map, "width");
            int height = jsonInt(map, "height");
            double duration = parseDuration(jsonString(map, "duration"));
            long size = parseSize(jsonString(map, "contentSize", "contentLength"));
            if (width == 0) width = parentWidth;
            if (height == 0) height = parentHeight;
            if (duration == 0) duration = parentDuration;
            if (size == 0) size = parentSize;

            List<String> urls = new ArrayList<>();
            collectJsonStrings(map.get("contentUrl"), urls, 8);
            collectJsonStrings(map.get("contentURL"), urls, 8);
            String genericUrl = firstString(map.get("url"), 0);
            if (looksLikeDirectMedia(genericUrl, mime)) urls.add(genericUrl);
            for (String url : urls) {
                addCandidate(
                        state, base, url, mime, poster, width, height, duration, size,
                        175, "json-ld-encoding", context, true);
            }
            for (Object value : map.values()) {
                if (value instanceof Map || value instanceof List) {
                    addJsonLdEncodings(
                            state, base, value, poster, width, height, duration, size,
                            context, depth + 1);
                }
            }
        } else if (node instanceof List) {
            for (Object value : (List<Object>) node) {
                addJsonLdEncodings(
                        state, base, value, parentPoster, parentWidth, parentHeight,
                        parentDuration, parentSize, parentContext, depth + 1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectJsonStrings(Object node, List<String> out, int limit) {
        if (node == null || out.size() >= limit) {
            return;
        }
        if (node instanceof String) {
            out.add((String) node);
        } else if (node instanceof List) {
            for (Object value : (List<Object>) node) {
                collectJsonStrings(value, out, limit);
            }
        } else if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            collectJsonStrings(map.get("url"), out, limit);
            collectJsonStrings(map.get("contentUrl"), out, limit);
        }
    }

    private static void parseVisibleScriptUrls(ParseState state, URI base, String original) {
        String script = normalizeScript(original);
        Matcher direct = DIRECT_MEDIA_URL_PATTERN.matcher(script);
        while (direct.find()) {
            String context = nearbyContext(script, direct.start(), direct.end());
            MetadataHint hint = metadataFromContext(context, direct.group());
            addCandidate(
                    state, base, direct.group(), null, null,
                    hint.width, hint.height, hint.durationSeconds, hint.contentLength,
                    80, "script", context, false);
        }
        Matcher relative = RELATIVE_MEDIA_URL_PATTERN.matcher(script);
        while (relative.find()) {
            int prefixStart = Math.max(0, relative.start(1) - 6);
            String prefix = lower(script.substring(prefixStart, relative.start(1)));
            if (prefix.endsWith("https:")) {
                continue;
            }
            String context = nearbyContext(script, relative.start(1), relative.end(1));
            MetadataHint hint = metadataFromContext(context, relative.group(1));
            addCandidate(
                    state, base, relative.group(1), null, null,
                    hint.width, hint.height, hint.durationSeconds, hint.contentLength,
                    75, "script-relative", context, false);
        }
        Matcher encoded = ENCODED_MEDIA_URL_PATTERN.matcher(script);
        while (encoded.find()) {
            String decoded = percentDecode(encoded.group());
            String context = nearbyContext(script, encoded.start(), encoded.end());
            MetadataHint hint = metadataFromContext(context, encoded.group());
            addCandidate(
                    state, base, decoded, null, null,
                    hint.width, hint.height, hint.durationSeconds, hint.contentLength,
                    70, "script-encoded", context, false);
        }
        Matcher opaque = OPAQUE_VIDEO_VALUE_PATTERN.matcher(script);
        while (opaque.find()) {
            String key = lower(opaque.group(1));
            String value = decodeJavaScriptString(opaque.group(2));
            String context = nearbyContext(script, opaque.start(), opaque.end());
            MetadataHint hint = metadataFromContext(context, opaque.group(2));
            String mime = mimeFromScriptContext(key, context);
            boolean semanticallyVideo = key.startsWith("video") || key.startsWith("stream")
                    || key.startsWith("hls") || key.startsWith("mp4");
            addCandidate(
                    state, base, value, mime, null,
                    hint.width, hint.height, hint.durationSeconds, hint.contentLength,
                    90, "script-field", context, semanticallyVideo);
        }
    }

    private static void parseXplayerSources(ParseState state, URI base, String original) {
        String host = lower(base.getHost());
        if (!(host.equals("xhamster.com") || host.endsWith(".xhamster.com"))) {
            return;
        }
        String script = normalizeScript(original);
        if (!lower(script).contains("xplayersettings")) {
            return;
        }
        Matcher matcher = XPLAYER_SOURCE_VALUE_PATTERN.matcher(script);
        while (matcher.find()) {
            String decoded = XhamsterUrlDecoder.decode(matcher.group(1));
            if (decoded == null) {
                continue;
            }
            String context = nearbyContext(script, matcher.start(), matcher.end());
            MetadataHint hint = metadataFromContext(context, matcher.group(1));
            addCandidate(
                    state, base, decoded, null, null,
                    hint.width, hint.height, hint.durationSeconds, hint.contentLength,
                    105, "script-xplayer", context, true);
        }
    }

    private static void addCandidate(
            ParseState state,
            URI base,
            String rawUrl,
            String mime,
            String poster,
            int width,
            int height,
            double durationSeconds,
            long contentLength,
            int sourceScore,
            String source,
            String context,
            boolean acceptsOpaqueVideoUrl) {
        if (isBlank(rawUrl)) {
            return;
        }
        String decoded = decodeJavaScriptString(decodeHtml(rawUrl.trim()));
        URI uri = resolveSafeMediaUri(base, decoded);
        if (uri == null) {
            return;
        }
        String normalizedMime = normalizeMime(mime);
        String extension = extensionFor(uri, normalizedMime);
        boolean recognised = isVideoMime(normalizedMime)
                || "mp4".equals(extension)
                || "webm".equals(extension)
                || "m3u8".equals(extension);
        if (!recognised && !acceptsOpaqueVideoUrl) {
            return;
        }
        String signalContext = source.startsWith("script")
                ? focusedScriptContext(context, uri)
                : context;
        String evidence = lower(signalContext + " " + uri.getPath() + " " + normalizedMime);
        if (isGifLike(uri, normalizedMime, evidence)) {
            return;
        }
        if (DRM_CONTEXT_PATTERN.matcher(evidence).find()) {
            state.drmRejected++;
            return;
        }
        if (extension.isEmpty()) {
            extension = mimeExtension(normalizedMime);
        }
        if (extension.isEmpty()) {
            extension = "mp4";
        }
        if (normalizedMime.isEmpty()) {
            normalizedMime = mimeForExtension(extension);
        }
        if ("m3u8".equals(extension)) {
            // A master playlist declares its own variant dimensions. Nearby quality labels
            // often belong to an adjacent MP4 property and must not be inherited here.
            width = 0;
            height = 0;
        }

        String key = uri.toString();
        CandidateBuilder candidate = state.candidates.get(key);
        boolean newCandidate = candidate == null;
        if (candidate == null) {
            candidate = new CandidateBuilder(key, state.nextDocumentOrder++);
        }
        candidate.mimeType = preferMime(candidate.mimeType, normalizedMime);
        candidate.extension = preferExtension(candidate.extension, extension);
        candidate.posterUrl = firstNonBlank(candidate.posterUrl, resolveImage(base, poster));
        candidate.width = Math.max(candidate.width, sanitiseDimension(width));
        candidate.height = Math.max(candidate.height, sanitiseDimension(height));
        candidate.durationSeconds = Math.max(candidate.durationSeconds, sanitiseDuration(durationSeconds));
        candidate.contentLength = Math.max(candidate.contentLength, sanitiseSize(contentLength));
        if (sourceScore > candidate.sourceScore) {
            candidate.sourceScore = sourceScore;
            candidate.discoverySource = source;
        }
        candidate.positiveSignal |= POSITIVE_CONTEXT_PATTERN.matcher(evidence).find();
        candidate.negativeSignal |= NEGATIVE_CONTEXT_PATTERN.matcher(evidence).find();
        candidate.loopLike |= evidence.contains(" loop") || evidence.contains("loop=")
                || evidence.contains("looping") || evidence.contains("animated");
        candidate.playlist |= "m3u8".equals(extension)
                || normalizedMime.contains("mpegurl") || normalizedMime.contains("x-mpegurl");
        candidate.recomputeScore();
        if (newCandidate) {
            state.offer(candidate);
        }
    }

    private static Result finish(ParseState state, boolean dataSaver) throws IOException {
        ArrayList<CandidateBuilder> ranked = new ArrayList<>(state.candidates.values());
        for (CandidateBuilder candidate : ranked) {
            candidate.recomputeScore();
        }
        Collections.sort(ranked, CandidateBuilder.ORDER);
        if (ranked.isEmpty()) {
            throw new IOException("No se encontró ningún vídeo público descargable en esta página.");
        }

        boolean hasStrong = false;
        for (CandidateBuilder candidate : ranked) {
            if (candidate.isStrongPrincipal()) {
                hasStrong = true;
                break;
            }
        }
        ArrayList<CandidateBuilder> kept = new ArrayList<>();
        for (CandidateBuilder candidate : ranked) {
            if (hasStrong && candidate.isLikelyAuxiliary()) {
                continue;
            }
            kept.add(candidate);
        }
        boolean fallback = !hasStrong;
        if (kept.isEmpty()) {
            kept.add(ranked.get(0));
            fallback = true;
        }
        if (dataSaver && kept.size() > 1) {
            CandidateBuilder saver = selectSaverCandidate(kept);
            kept.remove(saver);
            kept.add(0, saver);
        }
        ArrayList<Media> filtered = new ArrayList<>();
        for (CandidateBuilder candidate : kept) {
            filtered.add(candidate.toMedia());
        }
        return new Result(state.pageUrl, filtered, fallback, state.drmRejected);
    }

    private static CandidateBuilder selectSaverCandidate(List<CandidateBuilder> candidates) {
        CandidateBuilder normal = candidates.get(0);
        int bestRelevance = normal.relevanceScore();
        CandidateBuilder selected = normal;
        for (CandidateBuilder candidate : candidates) {
            if (candidate.relevanceScore() < bestRelevance - 24
                    || candidate.isLikelyAuxiliary()
                    || !sameLikelyContent(normal, candidate)) {
                continue;
            }
            if (candidate.saverCost() < selected.saverCost()) {
                selected = candidate;
            }
        }
        return selected;
    }

    private static boolean sameLikelyContent(CandidateBuilder first, CandidateBuilder second) {
        if (first == second) return true;
        if (qualityFamily(first.url).equals(qualityFamily(second.url))) return true;
        if (!isBlank(first.posterUrl) && first.posterUrl.equals(second.posterUrl)
                && first.durationSeconds > 0 && second.durationSeconds > 0) {
            double tolerance = Math.max(3.0d, first.durationSeconds * 0.05d);
            return Math.abs(first.durationSeconds - second.durationSeconds) <= tolerance;
        }
        return false;
    }

    private static String qualityFamily(String url) {
        String lower = lower(url);
        int query = lower.indexOf('?');
        String path = query < 0 ? lower : lower.substring(0, query);
        return path
                .replaceAll("(?:4320|2160|1440|1080|720|576|540|480|360|240|144)p?", "{q}")
                .replaceAll("(?:width|height)[=_-]?\\d+", "size");
    }

    private static URI safeSyntaxUri(String value, String label) throws IOException {
        try {
            URI safe = PublicWebUrlPolicy.requirePublicHttpsSyntax(value, true);
            if (safe.getFragment() == null) {
                return safe;
            }
            String ascii = safe.toASCIIString();
            int hash = ascii.indexOf('#');
            return PublicWebUrlPolicy.requirePublicHttpsSyntax(
                    hash < 0 ? ascii : ascii.substring(0, hash), false);
        } catch (IOException exception) {
            throw new IOException(label + " debe ser una URL HTTPS pública.", exception);
        }
    }

    private static URI resolveSafeMediaUri(URI base, String raw) {
        if (isBlank(raw) || raw.length() > MAX_URL_CHARS) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("//")) {
            value = "https:" + value;
        }
        try {
            URI resolved = base.resolve(value).normalize();
            return safeSyntaxUri(resolved.toASCIIString(), "El vídeo");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static URI findBaseUri(URI page, String html) {
        Matcher matcher = BASE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return page;
        }
        String href = attributes(matcher.group()).get("href");
        URI resolved = resolveSafeMediaUri(page, href);
        return resolved == null ? page : resolved;
    }

    private static String resolveImage(URI base, String raw) {
        URI resolved = resolveSafeMediaUri(base, decodeJavaScriptString(decodeHtml(raw)));
        return resolved == null ? null : resolved.toString();
    }

    private static Map<String, String> attributes(String tag) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (tag == null || tag.length() > 65_536) {
            return result;
        }
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(tag);
        while (matcher.find() && result.size() < 96) {
            String name = lower(matcher.group(1));
            String value = firstNonBlank(matcher.group(3), matcher.group(4));
            if (!isBlank(name) && value != null) {
                result.put(name, decodeHtml(value));
            }
        }
        return result;
    }

    private static Map<String, Object> lowerKeyMap(Map<String, Object> object) {
        LinkedHashMap<String, Object> lowered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            lowered.put(lower(entry.getKey()), entry.getValue());
        }
        return lowered;
    }

    private static boolean isVideoObject(Map<String, Object> object) {
        Map<String, Object> lowered = lowerKeyMap(object);
        Object type = lowered.get("@type");
        if (type instanceof String) {
            return "videoobject".equalsIgnoreCase(((String) type).trim());
        }
        if (type instanceof List) {
            for (Object value : (List<?>) type) {
                if (value instanceof String && "videoobject".equalsIgnoreCase(((String) value).trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String jsonString(Map<String, Object> object, String... keys) {
        Map<String, Object> lowered = lowerKeyMap(object);
        for (String key : keys) {
            Object value = lowered.get(lower(key));
            String found = firstString(value, 0);
            if (!isBlank(found)) {
                return found;
            }
        }
        return null;
    }

    private static String firstString(Object value, int depth) {
        if (value == null || depth > 6) {
            return null;
        }
        if (value instanceof String || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof List) {
            for (Object child : (List<?>) value) {
                String found = firstString(child, depth + 1);
                if (!isBlank(found)) {
                    return found;
                }
            }
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (String key : new String[] {"url", "contentUrl", "@id"}) {
                Object child = map.get(key);
                String found = firstString(child, depth + 1);
                if (!isBlank(found)) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int jsonInt(Map<String, Object> object, String key) {
        String value = jsonString(object, key);
        return positiveInt(value, 0);
    }

    private static MetadataHint metadataFromContext(String context, String focus) {
        MetadataHint result = new MetadataHint();
        int focusStart = isBlank(focus) ? -1 : context.indexOf(focus);
        int focusEnd = focusStart < 0 ? -1 : focusStart + focus.length();
        int anchor = focusStart < 0 ? context.length() / 2 : focusStart + focus.length() / 2;
        Matcher dimensions = DIMENSIONS_PATTERN.matcher(context);
        int nearest = Integer.MAX_VALUE;
        while (dimensions.find()) {
            int distance = distanceToFocus(
                    (dimensions.start() + dimensions.end()) / 2, focusStart, focusEnd, anchor);
            if (distance < nearest && distance <= 48) {
                nearest = distance;
                result.width = positiveInt(dimensions.group(1), 0);
                result.height = positiveInt(dimensions.group(2), 0);
            }
        }
        Matcher resolution = RESOLUTION_PATTERN.matcher(context);
        nearest = Integer.MAX_VALUE;
        while (resolution.find()) {
            int distance = distanceToFocus(
                    (resolution.start() + resolution.end()) / 2, focusStart, focusEnd, anchor);
            if (distance < nearest && distance <= 96) {
                nearest = distance;
                int height = positiveInt(resolution.group(1), 0);
                if (height > 0) {
                    result.height = height;
                    result.width = height * 16 / 9;
                }
            }
        }
        Matcher duration = DURATION_CONTEXT_PATTERN.matcher(context);
        nearest = Integer.MAX_VALUE;
        while (duration.find()) {
            int distance = distanceToFocus(
                    (duration.start() + duration.end()) / 2, focusStart, focusEnd, anchor);
            if (distance < nearest) {
                nearest = distance;
                result.durationSeconds = positiveDouble(duration.group(1), 0);
            }
        }
        Matcher size = SIZE_CONTEXT_PATTERN.matcher(context);
        nearest = Integer.MAX_VALUE;
        while (size.find()) {
            int distance = distanceToFocus(
                    (size.start() + size.end()) / 2, focusStart, focusEnd, anchor);
            if (distance < nearest) {
                nearest = distance;
                result.contentLength = parseSize(size.group(1));
            }
        }
        return result;
    }

    private static int distanceToFocus(int position, int start, int end, int fallbackAnchor) {
        if (start < 0 || end < start) {
            return Math.abs(position - fallbackAnchor);
        }
        if (position < start) {
            return start - position;
        }
        if (position > end) {
            return position - end;
        }
        return 0;
    }

    private static String mimeFromScriptContext(String key, String context) {
        if (key.contains("hls")) return "application/vnd.apple.mpegurl";
        if (key.contains("mp4")) return "video/mp4";
        Matcher matcher = FORMAT_CONTEXT_PATTERN.matcher(context);
        if (!matcher.find()) return null;
        String format = lower(matcher.group(1));
        if ("hls".equals(format) || "m3u8".equals(format)) {
            return "application/vnd.apple.mpegurl";
        }
        if ("webm".equals(format)) return "video/webm";
        if ("mp4".equals(format)) return "video/mp4";
        return null;
    }

    private static String normalizeScript(String value) {
        String result = decodeHtml(value);
        for (int i = 0; i < 3; i++) {
            String next = result
                    .replace("\\\\/", "/")
                    .replace("\\/", "/")
                    .replace("\\u002F", "/").replace("\\u002f", "/")
                    .replace("\\u003A", ":").replace("\\u003a", ":")
                    .replace("\\u0026", "&").replace("\\u003F", "?")
                    .replace("\\x2F", "/").replace("\\x2f", "/")
                    .replace("\\x3A", ":").replace("\\x3a", ":")
                    .replace("\\x26", "&");
            if (next.equals(result)) {
                break;
            }
            result = next;
        }
        return result;
    }

    private static String decodeJavaScriptString(String value) {
        if (value == null) {
            return null;
        }
        String result = normalizeScript(value)
                .replace("\\u0025", "%")
                .replace("\\u003d", "=").replace("\\u003D", "=")
                .replace("\\u0022", "\"").replace("\\u0027", "'");
        if (lower(result).startsWith("https%3a%2f%2f")) {
            result = percentDecode(result);
        }
        return result;
    }

    private static String percentDecode(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder output = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            if (value.charAt(i) == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    output.append((char) ((high << 4) | low));
                    i += 3;
                    continue;
                }
            }
            output.append(value.charAt(i++));
        }
        return output.toString();
    }

    private static String decodeHtml(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value;
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            char character = value.charAt(i);
            if (character != '&') {
                result.append(character);
                i++;
                continue;
            }
            int semi = value.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                result.append(character);
                i++;
                continue;
            }
            String entity = value.substring(i + 1, semi);
            String replacement = null;
            if ("amp".equalsIgnoreCase(entity)) replacement = "&";
            else if ("quot".equalsIgnoreCase(entity)) replacement = "\"";
            else if ("apos".equalsIgnoreCase(entity) || "#39".equals(entity)) replacement = "'";
            else if ("lt".equalsIgnoreCase(entity)) replacement = "<";
            else if ("gt".equalsIgnoreCase(entity)) replacement = ">";
            else if (entity.startsWith("#x") || entity.startsWith("#X")) {
                replacement = codePoint(entity.substring(2), 16);
            } else if (entity.startsWith("#")) {
                replacement = codePoint(entity.substring(1), 10);
            }
            if (replacement == null) {
                result.append('&');
                i++;
            } else {
                result.append(replacement);
                i = semi + 1;
            }
        }
        return result.toString();
    }

    private static String codePoint(String value, int radix) {
        try {
            int point = Integer.parseInt(value, radix);
            if (!Character.isValidCodePoint(point) || point == 0) {
                return null;
            }
            return new String(Character.toChars(point));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasAcceptableJsonDepth(String json) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') {
                quoted = true;
            } else if (c == '{' || c == '[') {
                if (++depth > MAX_JSON_DEPTH) return false;
            } else if (c == '}' || c == ']') {
                if (--depth < 0) return false;
            }
        }
        return depth == 0 && !quoted;
    }

    private static boolean looksLikeDirectMedia(String value, String mime) {
        if (isBlank(value)) return false;
        String lower = lower(value);
        return lower.matches(".*\\.(?:mp4|webm|m3u8)(?:[?#].*)?$") || isVideoMime(normalizeMime(mime));
    }

    private static boolean isGifLike(URI uri, String mime, String evidence) {
        String path = lower(uri.getPath());
        return path.endsWith(".gif") || path.endsWith(".gifv") || mime.contains("image/gif")
                || evidence.contains("gif-preview") || evidence.contains("animated-gif");
    }

    private static boolean isVideoMime(String mime) {
        return mime.startsWith("video/") || mime.contains("mpegurl")
                || mime.contains("x-mpegurl") || mime.contains("application/mp4");
    }

    private static String extensionFor(URI uri, String mime) {
        String path = lower(uri.getPath());
        if (path.endsWith(".mp4")) return "mp4";
        if (path.endsWith(".webm")) return "webm";
        if (path.endsWith(".m3u8")) return "m3u8";
        return mimeExtension(mime);
    }

    private static String mimeExtension(String mime) {
        String lower = lower(mime);
        if (lower.contains("webm")) return "webm";
        if (lower.contains("mpegurl")) return "m3u8";
        if (lower.startsWith("video/") || lower.contains("application/mp4")) return "mp4";
        return "";
    }

    private static String mimeForExtension(String extension) {
        if ("webm".equals(extension)) return "video/webm";
        if ("m3u8".equals(extension)) return "application/vnd.apple.mpegurl";
        return "video/mp4";
    }

    private static String preferMime(String current, String offered) {
        if (isBlank(current) || "application/octet-stream".equals(current)) return offered;
        return current;
    }

    private static String preferExtension(String current, String offered) {
        if (isBlank(current) || ("mp4".equals(current) && !isBlank(offered))) return offered;
        return current;
    }

    private static String normalizeMime(String value) {
        if (value == null) return "";
        int semicolon = value.indexOf(';');
        return lower((semicolon >= 0 ? value.substring(0, semicolon) : value).trim());
    }

    private static int sanitiseDimension(int value) {
        return value > 0 && value <= 16_384 ? value : 0;
    }

    private static double sanitiseDuration(double value) {
        return value > 0 && value <= 7 * 24 * 3600 ? value : 0;
    }

    private static long sanitiseSize(long value) {
        return value > 0 && value <= 16L * 1024 * 1024 * 1024 ? value : 0;
    }

    private static int positiveInt(String value, int fallback) {
        if (isBlank(value)) return fallback;
        try {
            int parsed = (int) Double.parseDouble(value.replaceAll("[^0-9.]", ""));
            return sanitiseDimension(parsed) == 0 ? fallback : parsed;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double positiveDouble(String value, double fallback) {
        if (isBlank(value)) return fallback;
        double parsed = parseDuration(value);
        return parsed > 0 ? parsed : fallback;
    }

    private static double parseDuration(String value) {
        if (isBlank(value)) return 0;
        String trimmed = value.trim();
        try {
            double numeric = Double.parseDouble(trimmed);
            return sanitiseDuration(numeric);
        } catch (Exception ignored) {
            Matcher iso = ISO_DURATION_PATTERN.matcher(trimmed);
            if (!iso.matches()) return 0;
            double days = number(iso.group(1));
            double hours = number(iso.group(2));
            double minutes = number(iso.group(3));
            double seconds = number(iso.group(4));
            return sanitiseDuration(days * 86400 + hours * 3600 + minutes * 60 + seconds);
        }
    }

    private static double number(String value) {
        try {
            return value == null ? 0 : Double.parseDouble(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long parseSize(String value) {
        if (isBlank(value)) return 0;
        String normal = value.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([kmgt]?i?b|bytes?)?").matcher(normal);
        if (!matcher.find()) return 0;
        try {
            double amount = Double.parseDouble(matcher.group(1));
            String unit = lower(matcher.group(2));
            long multiplier = 1;
            if (unit.startsWith("k")) multiplier = 1024L;
            else if (unit.startsWith("m")) multiplier = 1024L * 1024;
            else if (unit.startsWith("g")) multiplier = 1024L * 1024 * 1024;
            else if (unit.startsWith("t")) return 0;
            return sanitiseSize((long) (amount * multiplier));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String firstMeta(Map<String, List<String>> metadata, String... keys) {
        for (String key : keys) {
            List<String> values = metadata.get(lower(key));
            if (values != null) {
                for (String value : values) {
                    if (!isBlank(value)) return value;
                }
            }
        }
        return null;
    }

    private static String nearbyContext(String text, int start, int end) {
        int from = Math.max(0, start - 320);
        int to = Math.min(text.length(), end + 320);
        return text.substring(from, to);
    }

    private static String focusedScriptContext(String context, URI media) {
        if (isBlank(context)) return "";
        String path = media == null ? null : media.getPath();
        String focus = path == null ? null : path.substring(path.lastIndexOf('/') + 1);
        int anchor = isBlank(focus) ? -1 : context.indexOf(focus);
        if (anchor < 0) anchor = context.length() / 2;
        else anchor += focus.length() / 2;
        int from = Math.max(0, anchor - 80);
        for (char delimiter : new char[] {',', ';', '{', '['}) {
            int found = context.lastIndexOf(delimiter, anchor);
            if (found >= from) from = found + 1;
        }
        int to = Math.min(context.length(), anchor + 80);
        for (char delimiter : new char[] {',', ';', '}', ']'}) {
            int found = context.indexOf(delimiter, anchor);
            if (found >= 0 && found < to) to = found;
        }
        return context.substring(from, to);
    }

    private static String maskInertMarkup(String html, boolean includeScripts) {
        char[] masked = html.toCharArray();
        maskMatches(masked, html, HTML_COMMENT_PATTERN);
        maskMatches(masked, html, includeScripts
                ? MARKUP_INERT_BLOCK_PATTERN
                : SCRIPT_INERT_BLOCK_PATTERN);
        return new String(masked);
    }

    private static void maskMatches(char[] target, String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            for (int index = matcher.start(); index < matcher.end(); index++) {
                if (target[index] != '\n' && target[index] != '\r') target[index] = ' ';
            }
        }
    }

    private static String precedingTagContext(String text, int start, int end) {
        int from = Math.max(0, start - 240);
        String prefix = lower(text.substring(from, start));
        int previousVideoEnd = prefix.lastIndexOf("</video");
        if (previousVideoEnd >= 0) {
            int closingBracket = prefix.indexOf('>', previousVideoEnd);
            if (closingBracket >= 0) {
                from += closingBracket + 1;
            }
        }
        return text.substring(from, Math.min(text.length(), end));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** Immutable extraction result, ordered from the most likely main video to weaker alternatives. */
    public static final class Result {
        private final String pageUrl;
        private final List<Media> candidates;
        private final boolean fallbackSelection;
        private final int rejectedDrmCandidates;

        private Result(String pageUrl, List<Media> candidates, boolean fallback, int rejectedDrm) {
            this.pageUrl = pageUrl;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.fallbackSelection = fallback;
            this.rejectedDrmCandidates = rejectedDrm;
        }

        public String getPageUrl() { return pageUrl; }
        public Media getMainMedia() { return candidates.get(0); }
        public List<Media> getCandidates() { return candidates; }
        public boolean isFallbackSelection() { return fallbackSelection; }
        public int getRejectedDrmCandidates() { return rejectedDrmCandidates; }
    }

    /** Immutable generic-video candidate. No body is fetched while this object is constructed. */
    public static final class Media {
        private final String url;
        private final String mimeType;
        private final String extension;
        private final String posterUrl;
        private final int width;
        private final int height;
        private final double durationSeconds;
        private final long contentLength;
        private final int score;
        private final String discoverySource;
        private final boolean playlist;
        private final boolean likelyAuxiliary;

        private Media(CandidateBuilder value) {
            url = value.url;
            mimeType = value.mimeType;
            extension = value.extension;
            posterUrl = value.posterUrl;
            width = value.width;
            height = value.height;
            durationSeconds = value.durationSeconds;
            contentLength = value.contentLength;
            score = value.score;
            discoverySource = value.discoverySource;
            playlist = value.playlist;
            likelyAuxiliary = value.isLikelyAuxiliary();
        }

        public String getUrl() { return url; }
        public String getMimeType() { return mimeType; }
        public String getSuggestedExtension() { return extension; }
        public String getPosterUrl() { return posterUrl; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public double getDurationSeconds() { return durationSeconds; }
        public long getContentLength() { return contentLength; }
        public int getScore() { return score; }
        public String getDiscoverySource() { return discoverySource; }
        public String getQualityLabel() {
            return height > 0 ? height + "p" : (playlist ? "HLS" : "automática");
        }
        public boolean isPlaylist() { return playlist; }
        public boolean isLikelyAuxiliary() { return likelyAuxiliary; }
    }

    private static final class CandidateBuilder {
        private static final Comparator<CandidateBuilder> ORDER = new Comparator<CandidateBuilder>() {
            @Override
            public int compare(CandidateBuilder left, CandidateBuilder right) {
                int comparison = Integer.compare(right.score, left.score);
                if (comparison != 0) return comparison;
                comparison = Long.compare(right.contentLength, left.contentLength);
                if (comparison != 0) return comparison;
                comparison = Double.compare(right.durationSeconds, left.durationSeconds);
                if (comparison != 0) return comparison;
                comparison = Long.compare(
                        (long) right.width * right.height, (long) left.width * left.height);
                if (comparison != 0) return comparison;
                comparison = Integer.compare(left.documentOrder, right.documentOrder);
                if (comparison != 0) return comparison;
                return left.url.compareTo(right.url);
            }
        };

        private final String url;
        private final int documentOrder;
        private String mimeType = "";
        private String extension = "";
        private String posterUrl;
        private int width;
        private int height;
        private double durationSeconds;
        private long contentLength;
        private int sourceScore;
        private String discoverySource = "desconocido";
        private boolean positiveSignal;
        private boolean negativeSignal;
        private boolean loopLike;
        private boolean playlist;
        private int score;

        private CandidateBuilder(String url, int documentOrder) {
            this.url = url;
            this.documentOrder = documentOrder;
        }

        private void recomputeScore() {
            int value = sourceScore;
            if (positiveSignal) value += 90;
            if (negativeSignal) value -= 135;
            long pixels = (long) width * height;
            if (pixels >= 1920L * 1080) value += 80;
            else if (pixels >= 1280L * 720) value += 62;
            else if (pixels >= 854L * 480) value += 42;
            else if (pixels > 0 && pixels < 320L * 240) value -= 100;
            if (durationSeconds >= 120) value += 95;
            else if (durationSeconds >= 30) value += 65;
            else if (durationSeconds >= 10) value += 30;
            else if (durationSeconds > 0 && durationSeconds < 8) value -= 125;
            if (contentLength >= 50L * 1024 * 1024) value += 105;
            else if (contentLength >= 10L * 1024 * 1024) value += 75;
            else if (contentLength >= 2L * 1024 * 1024) value += 38;
            else if (contentLength > 0 && contentLength < 512L * 1024) value -= 105;
            if (loopLike && !positiveSignal) value -= 70;
            if (playlist) value += 5;
            String lowerUrl = lower(url);
            if (lowerUrl.contains("main") || lowerUrl.contains("movie") || lowerUrl.contains("full")) value += 18;
            if (NEGATIVE_CONTEXT_PATTERN.matcher(lowerUrl).find()) value -= 90;
            score = value;
        }

        private boolean isLikelyAuxiliary() {
            long pixels = (long) width * height;
            boolean tiny = pixels > 0 && pixels < 320L * 240;
            boolean shortClip = durationSeconds > 0 && durationSeconds < 8;
            boolean light = contentLength > 0 && contentLength < 512L * 1024;
            return negativeSignal || tiny || shortClip || light || (loopLike && !positiveSignal);
        }

        private boolean isStrongPrincipal() {
            if (isLikelyAuxiliary()) return false;
            long pixels = (long) width * height;
            return positiveSignal || sourceScore >= 165 || durationSeconds >= 10
                    || contentLength >= 2L * 1024 * 1024 || pixels >= 640L * 360 || score >= 170;
        }

        private int relevanceScore() {
            int value = sourceScore;
            if (positiveSignal) value += 90;
            if (negativeSignal) value -= 135;
            if (durationSeconds >= 120) value += 75;
            else if (durationSeconds >= 30) value += 50;
            else if (durationSeconds >= 10) value += 25;
            else if (durationSeconds > 0 && durationSeconds < 8) value -= 125;
            if (loopLike && !positiveSignal) value -= 70;
            return value;
        }

        private long saverCost() {
            if (contentLength > 0) {
                return contentLength;
            }
            long pixels = (long) width * height;
            if (pixels > 0) {
                // Prefer a useful SD rendition over a tiny thumbnail-like clip.
                return pixels < 640L * 360 ? Long.MAX_VALUE / 4 + pixels : pixels;
            }
            return Long.MAX_VALUE / 2 + documentOrder;
        }

        private Media toMedia() { return new Media(this); }
    }

    private static final class ParseState {
        private final String pageUrl;
        private final LinkedHashMap<String, CandidateBuilder> candidates = new LinkedHashMap<>();
        private int drmRejected;
        private int nextDocumentOrder;
        private ParseState(String pageUrl) { this.pageUrl = pageUrl; }

        private void offer(CandidateBuilder candidate) {
            if (candidates.size() < MAX_CANDIDATES) {
                candidates.put(candidate.url, candidate);
                return;
            }
            CandidateBuilder worst = null;
            for (CandidateBuilder existing : candidates.values()) {
                if (worst == null || CandidateBuilder.ORDER.compare(existing, worst) > 0) {
                    worst = existing;
                }
            }
            if (worst != null && CandidateBuilder.ORDER.compare(candidate, worst) < 0) {
                candidates.remove(worst.url);
                candidates.put(candidate.url, candidate);
            }
        }
    }

    private static final class VideoContext {
        private final String poster;
        private final String mimeType;
        private final int width;
        private final int height;
        private final double durationSeconds;
        private final String context;

        private VideoContext(
                String poster, String mimeType, int width, int height, double duration, String context) {
            this.poster = poster;
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
            this.durationSeconds = duration;
            this.context = context;
        }

        private static VideoContext from(Map<String, String> attrs, URI base, String nearby) {
            String context = nearby + " " + attrs.toString();
            return new VideoContext(
                    resolveImage(base, attrs.get("poster")),
                    attrs.get("type"),
                    positiveInt(firstNonBlank(attrs.get("width"), attrs.get("data-width")), 0),
                    positiveInt(firstNonBlank(attrs.get("height"), attrs.get("data-height")), 0),
                    positiveDouble(firstNonBlank(attrs.get("duration"), attrs.get("data-duration")), 0),
                    context);
        }

        private static VideoContext empty(String nearby) {
            return new VideoContext(null, null, 0, 0, 0, nearby);
        }
    }

    private static final class MetadataHint {
        private int width;
        private int height;
        private double durationSeconds;
        private long contentLength;
    }

    /** Small, bounded JSON reader used only for public JSON-LD; avoids an additional dependency. */
    private static final class MiniJsonParser {
        private final String text;
        private final int depthLimit;
        private final int nodeLimit;
        private int index;
        private int nodes;

        private MiniJsonParser(String text, int depthLimit, int nodeLimit) {
            this.text = text;
            this.depthLimit = depthLimit;
            this.nodeLimit = nodeLimit;
        }

        private Object parse() {
            Object value = readValue(0);
            whitespace();
            if (index != text.length()) throw new IllegalArgumentException("JSON sobrante");
            return value;
        }

        private Object readValue(int depth) {
            if (depth > depthLimit || ++nodes > nodeLimit) throw new IllegalArgumentException("JSON límite");
            whitespace();
            if (index >= text.length()) throw new IllegalArgumentException("JSON incompleto");
            char c = text.charAt(index);
            if (c == '{') return readObject(depth + 1);
            if (c == '[') return readArray(depth + 1);
            if (c == '"') return readString();
            if (c == 't' && literal("true")) return Boolean.TRUE;
            if (c == 'f' && literal("false")) return Boolean.FALSE;
            if (c == 'n' && literal("null")) return null;
            return readNumber();
        }

        private Map<String, Object> readObject(int depth) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            index++;
            whitespace();
            if (consume('}')) return result;
            while (result.size() < MAX_JSON_NODES) {
                whitespace();
                String key = readString();
                whitespace();
                require(':');
                Object value = readValue(depth);
                result.put(key, value);
                whitespace();
                if (consume('}')) return result;
                require(',');
            }
            throw new IllegalArgumentException("JSON objeto grande");
        }

        private List<Object> readArray(int depth) {
            ArrayList<Object> result = new ArrayList<>();
            index++;
            whitespace();
            if (consume(']')) return result;
            while (result.size() < MAX_JSON_NODES) {
                result.add(readValue(depth));
                whitespace();
                if (consume(']')) return result;
                require(',');
            }
            throw new IllegalArgumentException("JSON lista grande");
        }

        private String readString() {
            require('"');
            StringBuilder value = new StringBuilder();
            while (index < text.length() && value.length() <= MAX_URL_CHARS * 8) {
                char c = text.charAt(index++);
                if (c == '"') return value.toString();
                if (c != '\\') {
                    if (c < 0x20) throw new IllegalArgumentException("JSON carácter");
                    value.append(c);
                    continue;
                }
                if (index >= text.length()) throw new IllegalArgumentException("JSON escape");
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u': value.append(readUnicode()); break;
                    default: throw new IllegalArgumentException("JSON escape");
                }
            }
            throw new IllegalArgumentException("JSON cadena");
        }

        private char readUnicode() {
            if (index + 4 > text.length()) throw new IllegalArgumentException("JSON unicode");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) throw new IllegalArgumentException("JSON unicode");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Number readNumber() {
            int start = index;
            if (consume('-')) { /* sign */ }
            while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            if (consume('.')) while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            }
            if (start == index) throw new IllegalArgumentException("JSON número");
            String number = text.substring(start, index);
            try {
                return number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0
                        ? Double.valueOf(number) : Long.valueOf(number);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("JSON número", exception);
            }
        }

        private boolean literal(String value) {
            if (!text.regionMatches(index, value, 0, value.length())) return false;
            index += value.length();
            return true;
        }

        private void whitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        private boolean consume(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) throw new IllegalArgumentException("JSON esperaba " + expected);
        }
    }
}
