// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts and validates supported HTTPS links from pasted or shared text. */
public final class SocialLinkParser {
    private static final Pattern WEB_URL = Pattern.compile(
            "(?i)https?://[^\\s<>\\\"']+");

    private SocialLinkParser() {
    }

    public static String extractSupportedUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = WEB_URL.matcher(text.trim());
        while (matcher.find()) {
            String candidate = trimTrailingPunctuation(matcher.group());
            if (detectPlatform(candidate) != null && isSupportedPath(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static SocialPlatform detectPlatform(String value) {
        URI uri = parseHttps(value);
        if (uri == null) {
            return null;
        }
        String host = lower(uri.getHost());
        if (isInstagramHost(host)) {
            return SocialPlatform.INSTAGRAM;
        }
        if (isFacebookHost(host)) {
            return SocialPlatform.FACEBOOK;
        }
        if (YoutubeLink.isHost(host)) { return SocialPlatform.YOUTUBE; }
        if (looksLikeSocialDomainImpersonation(host)) {
            return null;
        }
        return SocialPlatform.GENERIC;
    }

    public static boolean isSupportedPath(String value) {
        URI uri = parseHttps(value);
        SocialPlatform platform = detectPlatform(value);
        if (uri == null || platform == null) {
            return false;
        }
        List<String> segments = pathSegments(uri.getPath());
        if (platform == SocialPlatform.YOUTUBE) { return YoutubeLink.videoId(value) != null; }
        if (platform == SocialPlatform.INSTAGRAM) {
            if (segments.isEmpty()) {
                return false;
            }
            String first = lower(segments.get(0));
            return (first.equals("p")
                    || first.equals("reel")
                    || first.equals("reels")
                    || first.equals("tv")
                    || first.equals("share"))
                    && segments.size() >= 2;
        }

        if (platform == SocialPlatform.GENERIC) {
            return isPlausiblePublicWebPage(uri);
        }

        String host = lower(uri.getHost());
        if (host.equals("fb.watch") || host.endsWith(".fb.watch")) {
            return !segments.isEmpty() && isToken(segments.get(0));
        }
        if (segments.isEmpty()) {
            return false;
        }
        String first = lower(segments.get(0));
        if (first.equals("share")) {
            if (segments.size() < 2) {
                return false;
            }
            String second = lower(segments.get(1));
            if (second.equals("r") || second.equals("v") || second.equals("p")
                    || second.equals("s")) {
                return segments.size() >= 3 && isToken(segments.get(2));
            }
            return isToken(segments.get(1));
        }
        if (first.equals("reel") || first.equals("reels") || first.equals("videos")
                || first.equals("stories")) {
            return segments.size() >= 2 && isToken(segments.get(1));
        }
        if (first.equals("watch")) {
            return (segments.size() >= 2 && isToken(segments.get(1)))
                    || hasIdQuery(uri, "v");
        }
        if (first.equals("video.php")) {
            return hasIdQuery(uri, "v");
        }
        if (first.equals("photo")) {
            return (segments.size() >= 2 && isToken(segments.get(1)))
                    || hasIdQuery(uri, "fbid");
        }
        if (first.equals("photo.php")) {
            return hasIdQuery(uri, "fbid");
        }
        if (first.equals("permalink.php") || first.equals("story.php")) {
            return hasIdQuery(uri, "story_fbid") || hasIdQuery(uri, "fbid");
        }
        for (int index = 0; index < segments.size() - 1; index++) {
            String lowerSegment = lower(segments.get(index));
            if (lowerSegment.equals("posts")
                    || lowerSegment.equals("videos")
                    || lowerSegment.equals("reel")
                    || lowerSegment.equals("permalink")) {
                return isToken(segments.get(index + 1));
            }
        }
        return false;
    }

    public static boolean isInstagramHost(String host) {
        String value = lower(host);
        return value.equals("instagram.com")
                || value.endsWith(".instagram.com")
                || value.equals("instagr.am")
                || value.endsWith(".instagr.am");
    }

    public static boolean isFacebookHost(String host) {
        String value = lower(host);
        return value.equals("facebook.com")
                || value.endsWith(".facebook.com")
                || value.equals("fb.watch")
                || value.endsWith(".fb.watch");
    }

    private static boolean looksLikeSocialDomainImpersonation(String host) {
        String value = lower(host);
        for (String protectedDomain : new String[]{
                "instagram.com", "instagr.am", "facebook.com", "fb.watch", "youtube.com", "youtu.be", "youtube-nocookie.com"
        }) {
            if (value.startsWith(protectedDomain + ".")
                    || value.contains("." + protectedDomain + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlausiblePublicWebPage(URI uri) {
        String host = lower(uri.getHost());
        if (host.isEmpty()
                || uri.getRawUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            return false;
        }
        if (host.equals("localhost")
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".lan")
                || host.endsWith(".internal")
                || host.endsWith(".onion")
                || host.indexOf('.') < 0) {
            return false;
        }
        // La comprobación DNS completa se repite justo antes de cada petición.
        return !host.matches("[0-9.]+") && !host.contains(":");
    }

    private static URI parseHttps(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? uri
                    : null;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static List<String> pathSegments(String path) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (path == null) {
            return result;
        }
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result;
    }

    private static boolean hasIdQuery(URI uri, String expectedName) {
        String query = uri.getRawQuery();
        if (query == null) {
            return false;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || !expectedName.equalsIgnoreCase(pair.substring(0, equals))) {
                continue;
            }
            if (isToken(pair.substring(equals + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isToken(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{5,80}");
    }

    private static String trimTrailingPunctuation(String value) {
        String result = value;
        while (!result.isEmpty()) {
            char tail = result.charAt(result.length() - 1);
            if (tail == '.' || tail == ',' || tail == ')' || tail == ']'
                    || tail == '}' || tail == ';' || tail == '!' || tail == '>') {
                result = result.substring(0, result.length() - 1);
            } else {
                break;
            }
        }
        return result;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }
}
