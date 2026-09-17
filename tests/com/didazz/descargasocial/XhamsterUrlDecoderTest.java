// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.net.URI;

/** Offline golden-vector tests for the bounded xplayer URL decoder. */
public final class XhamsterUrlDecoderTest {
    private static int passed;

    private XhamsterUrlDecoderTest() {
    }

    public static void main(String[] arguments) throws Exception {
        String[] vectors = {
                "01785634121f1eb55ce804aa0f877d",
                "0278563412cdd7b0e8fb773206df3e",
                "037856341249f5a20ae9ea44b7c795",
                "04785634129ec64958d385727421d3",
                "05785634121a1a183240e3bd33f6cb",
                "06785634120f7f04776ff02531b44a",
                "077856341229f35a2e79a0acd2b714"
        };
        for (int index = 0; index < vectors.length; index++) {
            equal("https://x/", XhamsterUrlDecoder.decode(vectors[index]),
                    "algoritmo " + (index + 1));
        }

        String encryptedPath = "0278563412c0c7a3fda72e7947";
        String input = "https://cdn.example.com/" + encryptedPath
                + "/master.m3u8?token=fake#frag";
        String expected = "https://cdn.example.com/edge/cdn/master.m3u8?token=fake#frag";
        equal(expected, XhamsterUrlDecoder.decode(input), "segmento de ruta cifrado");
        equal(null, XhamsterUrlDecoder.decode("087856341200000000000000"),
                "algoritmo desconocido");
        equal(null, XhamsterUrlDecoder.decode("0178563412"), "texto hexadecimal corto");
        equal(null, XhamsterUrlDecoder.decode("https://cdn.example.com/plain/video.mp4"),
                "URL normal no se altera");

        String html = "<script>window.initials={\"xplayerSettings\":{\"sources\":{"
                + "\"hls\":{\"url\":\"" + input + "\"}}}};</script>";
        GenericExtractor.Media media = GenericExtractor.parse(
                html, URI.create("https://es.xhamster.com/videos/example-12345678")).getMainMedia();
        equal("https://cdn.example.com/edge/cdn/master.m3u8?token=fake",
                media.getUrl(), "integración xplayer");
        equal(true, media.isPlaylist(), "xplayer conserva HLS");
        System.out.println("XhamsterUrlDecoderTest: " + passed + " PASS");
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": esperado=" + expected + ", actual=" + actual);
        }
        passed++;
    }
}
