// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Deterministic tests for generic media signatures and private-network blocking. */
public final class MediaValidatorTest {
    public static void main(String[] args) throws Exception {
        byte[] mp4 = new byte[]{
                0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '2'};
        equal(MediaValidator.Format.MP4, MediaValidator.detect(mp4, "application/octet-stream"), "MP4 real");

        byte[] avif = mp4.clone();
        avif[8] = 'a'; avif[9] = 'v'; avif[10] = 'i'; avif[11] = 'f';
        rejects(avif, "video/mp4", "AVIF disfrazado");
        rejects("<html>login</html>".getBytes(StandardCharsets.UTF_8), "text/html", "HTML disfrazado");
        rejects(new byte[]{'P', 'K', 3, 4, 0, 0}, "video/mp4", "ZIP disfrazado");

        byte[] hls = "#EXTM3U\n#EXT-X-VERSION:3\n".getBytes(StandardCharsets.US_ASCII);
        equal(MediaValidator.Format.HLS, MediaValidator.detect(hls, "application/vnd.apple.mpegurl"), "HLS");

        equal(true, PublicWebUrlPolicy.isForbiddenAddress(InetAddress.getByName("127.0.0.1")), "loopback");
        equal(true, PublicWebUrlPolicy.isForbiddenAddress(InetAddress.getByName("100.64.0.1")), "CGNAT");
        equal(true, PublicWebUrlPolicy.isForbiddenAddress(InetAddress.getByName("192.168.1.1")), "LAN");
        equal(true, PublicWebUrlPolicy.isForbiddenAddress(InetAddress.getByName("2001:db8::1")), "IPv6 docs");
        equal(true, PublicWebUrlPolicy.isForbiddenAddress(InetAddress.getByName("2002:a00:1::")), "6to4 hacia LAN");
        equal(false, PublicWebUrlPolicy.isForbiddenAddress(InetAddress.getByName("93.184.216.34")), "IPv4 pública");
        equal(false, PublicWebUrlPolicy.isForbiddenAddress(InetAddress.getByName("2606:4700:4700::1111")), "IPv6 pública");
        rejectsUrl("https://usuario:clave@example.com/video", "credenciales");
        rejectsUrl("https://example.com:8443/video", "puerto");
        rejectsUrl("https://router.local/video", "mDNS");
        rejectsUrl("https://example.com/video#secreto", "fragmento");
        rejectsUrl("http://example.com/video", "HTTP");
        System.out.println("MediaValidatorTest: 17 PASS");
    }

    private static void rejects(byte[] bytes, String mime, String label) {
        try {
            MediaValidator.detect(bytes, mime);
            throw new AssertionError(label + ": debía rechazarse");
        } catch (Exception expected) {
            // esperado
        }
    }

    private static void rejectsUrl(String value, String label) {
        try {
            PublicWebUrlPolicy.requirePublicHttpsSyntax(value, false);
            throw new AssertionError(label + ": debía rechazarse");
        } catch (Exception expected) {
            // esperado
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": esperado=" + expected + ", real=" + actual);
        }
    }
}
