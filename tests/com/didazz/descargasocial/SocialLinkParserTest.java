// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

/** Dependency-free URL-routing regression tests. */
public final class SocialLinkParserTest {
    public static void main(String[] args) {
        equal(
                "https://www.instagram.com/reel/DcXuA9TsWUi/?igsi=x",
                SocialLinkParser.extractSupportedUrl(
                        "Mira esto: https://www.instagram.com/reel/DcXuA9TsWUi/?igsi=x)."),
                "Instagram compartido");
        equal(
                "https://www.facebook.com/share/r/1ERNpz4hxB/",
                SocialLinkParser.extractSupportedUrl(
                        "Diego te envió https://www.facebook.com/share/r/1ERNpz4hxB/"),
                "Facebook compartido");
        equal(
                SocialPlatform.FACEBOOK,
                SocialLinkParser.detectPlatform("https://fb.watch/example/"),
                "fb.watch");
        equal(
                null,
                SocialLinkParser.detectPlatform("https://facebook.com.evil.example/reel/123456/"),
                "dominio falso");
        equal(
                null,
                SocialLinkParser.extractSupportedUrl("https://www.facebook.com/"),
                "inicio sin publicación");
        equal(
                false,
                SocialLinkParser.isSupportedPath("http://www.facebook.com/reel/123456/"),
                "HTTPS obligatorio");
        equal(
                false,
                SocialLinkParser.isSupportedPath("https://www.facebook.com/watch/"),
                "landing watch sin ID");
        equal(
                false,
                SocialLinkParser.isSupportedPath("https://www.facebook.com/share/r/"),
                "share Reel sin token");
        equal(
                true,
                SocialLinkParser.isSupportedPath(
                        "https://www.facebook.com/watch/?v=903624092745975"),
                "watch con ID");
        equal(
                true,
                SocialLinkParser.isSupportedPath(
                        "https://www.facebook.com/person/posts/10164142248141664/"),
                "post de perfil con ID");
        equal(
                SocialPlatform.GENERIC,
                SocialLinkParser.detectPlatform(
                        "https://es.xhamster.com/videos/example-12345678?utm_source=shared"),
                "web genérica");
        equal(
                "https://es.pornhub.com/view_video.php?viewkey=example123",
                SocialLinkParser.extractSupportedUrl(
                        "Vídeo: https://es.pornhub.com/view_video.php?viewkey=example123"),
                "web genérica compartida");
        equal(
                null,
                SocialLinkParser.extractSupportedUrl("https://127.0.0.1/video.mp4"),
                "IP local rechazada");
        equal(
                null,
                SocialLinkParser.extractSupportedUrl("https://router.lan/video"),
                "dominio LAN rechazado");
        equal(
                null,
                SocialLinkParser.extractSupportedUrl("https://usuario:clave@example.com/video"),
                "credenciales en URL rechazadas");
        equal(
                null,
                SocialLinkParser.extractSupportedUrl("https://example.com:8443/video"),
                "puerto alternativo rechazado");
        System.out.println("SocialLinkParserTest: 16 PASS");
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": esperado=" + expected + ", real=" + actual);
        }
    }
}
