// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;

/** Only a single YouTube video ID; playlist/channel links never silently start bulk downloads. */
public final class YoutubeLink {
    private YoutubeLink() { }
    public static boolean isHost(String host) {
        if (host == null) { return false; }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("youtube.com") || h.equals("www.youtube.com") || h.equals("m.youtube.com")
                || h.equals("music.youtube.com") || h.equals("youtu.be")
                || h.equals("youtube-nocookie.com") || h.equals("www.youtube-nocookie.com");
    }
    public static String videoId(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !isHost(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) { return null; }
            String path = uri.getRawPath(), id = null;
            String[] parts = path == null ? new String[0] : path.split("/");
            if ("youtu.be".equalsIgnoreCase(uri.getHost())) {
                if (parts.length == 2) { id = parts[1]; }
            } else if ("/watch".equals(path) || "/watch/".equals(path)) {
                String query = uri.getRawQuery();
                if (query != null) for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && "v".equals(URLDecoder.decode(pair.substring(0, eq), "UTF-8"))) {
                        String next = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                        if (id != null && !id.equals(next)) { return null; }
                        id = next;
                    }
                }
            } else if (parts.length == 3 && ("shorts".equals(parts[1])
                    || "embed".equals(parts[1]) || "live".equals(parts[1]))) { id = parts[2]; }
            return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
        } catch (Exception invalid) { return null; }
    }
    public static String canonical(String value) {
        String id = videoId(value);
        if (id == null) { throw new IllegalArgumentException(Messages.ref("error_367")); }
        return "https://www.youtube.com/watch?v=" + id;
    }
}
