// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Descarga Social contributors
package com.didazz.descargasocial;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic host-side tests for the generic HLS resolver and downloader. */
public final class GenericHlsDownloaderTest {
    private static int passed;

    private GenericHlsDownloaderTest() {
    }

    public static void main(String[] arguments) throws Exception {
        testBestVariantAndFragmentedMp4Join();
        testBestVariantWithMissingResolution();
        testDataSaverAndTransportStreamOrder();
        testRedirectedPlaylistControlsRelativeBase();
        testByteRanges();
        testExternalAudioVariantIsSkipped();
        testEncryptionAndDrmAreRejected();
        testLiveAndGapPlaylistsAreRejected();
        testPartialEndedWindowIsRejected();
        testUnsafeUrlsAndRedirectsAreRejected();
        testContainerAndSignatureChecks();
        testMapChangesAndCyclesAreRejected();
        testContentEncodingIsRejected();
        testStrictPlaylistAndSegmentLimits();
        System.out.println("GenericHlsDownloaderTest: " + passed + " PASS");
    }

    private static void testBestVariantAndFragmentedMp4Join() throws Exception {
        String master = "https://video.example.com/path/master.m3u8";
        String low = "https://video.example.com/path/low/list.m3u8";
        String high = "https://cdn.example.com/high/index.m3u8?token=abc";
        FakeFetcher fetcher = new FakeFetcher()
                .text(master, "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=426x240\n"
                        + "low/list.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080\n"
                        + "https://cdn.example.com/high/index.m3u8?token=abc\n")
                .text(low, tsPlaylist("low.ts"))
                .text(high, "#EXTM3U\n"
                        + "#EXT-X-VERSION:7\n"
                        + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                        + "#EXTINF:4.5,\nseg-1.m4s\n"
                        + "#EXTINF:5.5,\n../seg-2.m4s?q=1\n"
                        + "#EXT-X-ENDLIST\n");
        byte[] map = concat(box("ftyp", bytes("isom0000isomiso6")), box("moov", new byte[0]));
        byte[] first = concat(box("moof", new byte[0]), box("mdat", bytes("one")));
        byte[] second = concat(box("styp", bytes("msdh0000")), box("moof", new byte[0]),
                box("mdat", bytes("two")));
        fetcher.bytes("https://cdn.example.com/high/init.mp4", map)
                .bytes("https://cdn.example.com/high/seg-1.m4s", first)
                .bytes("https://cdn.example.com/seg-2.m4s?q=1", second);

        GenericHlsDownloader.ResolvedStream stream =
                GenericHlsDownloader.resolveForTests(master, false, fetcher);
        equal(true, stream.isFragmentedMp4(), "la variante fMP4 se identifica");
        equal("mp4", stream.getSuggestedExtension(), "extensión fMP4");
        equal("video/mp4", stream.getMimeType(), "MIME fMP4");
        equal(2, stream.getSegmentCount(), "número de fragmentos");
        equal(10_000L, stream.getDurationMillis(), "duración acumulada");
        equal(4_500_000L, stream.getBandwidth(), "ancho de banda elegido");
        equal(1920, stream.getWidth(), "anchura elegida");
        equal(1080, stream.getHeight(), "altura elegida");
        equal(high, stream.getMediaPlaylistUrl(), "se elige la mejor variante");
        equal(Arrays.asList(master, high), fetcher.requestedUrls(), "solo se lee la variante elegida");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long written = GenericHlsDownloader.downloadToStreamForTests(stream, output, fetcher);
        byte[] expected = concat(map, first, second);
        equal((long) expected.length, written, "bytes fMP4 escritos");
        bytesEqual(expected, output.toByteArray(), "mapa y fragmentos conservan su orden");
    }

    private static void testDataSaverAndTransportStreamOrder() throws Exception {
        String master = "https://media.example.com/master.m3u8";
        String low = "https://media.example.com/low/index.m3u8";
        FakeFetcher fetcher = new FakeFetcher()
                .text(master, "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=320x180\n"
                        + "low/index.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\n"
                        + "high/index.m3u8\n")
                .text(low, "#EXTM3U\n"
                        + "#EXTINF:3,\nsegments/a.ts?part=1\n"
                        + "#EXTINF:3,\nsegments/b.ts?part=2\n"
                        + "#EXT-X-ENDLIST\n");
        byte[] first = transportStream(3, 0x11);
        byte[] second = transportStream(3, 0x22);
        fetcher.bytes("https://media.example.com/low/segments/a.ts?part=1", first)
                .bytes("https://media.example.com/low/segments/b.ts?part=2", second);

        GenericHlsDownloader.ResolvedStream stream =
                GenericHlsDownloader.resolveForTests(master, true, fetcher);
        equal(false, stream.isFragmentedMp4(), "MPEG-TS no se marca como fMP4");
        equal("ts", stream.getSuggestedExtension(), "extensión TS");
        equal("video/mp2t", stream.getMimeType(), "MIME TS");
        equal(low, stream.getMediaPlaylistUrl(), "ahorro elige el menor bitrate");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GenericHlsDownloader.downloadToStreamForTests(stream, output, fetcher);
        bytesEqual(concat(first, second), output.toByteArray(), "segmentos TS en orden");
        pass();
    }

    private static void testBestVariantWithMissingResolution() throws Exception {
        String master = "https://quality.example.com/master.m3u8";
        String unknownHigh = "https://quality.example.com/unknown-high.m3u8";
        FakeFetcher fetcher = new FakeFetcher()
                .text(master, "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=426x240\n"
                        + "known-low.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=5000000\n"
                        + "unknown-high.m3u8\n")
                .text(unknownHigh, tsPlaylist("segment.ts"));
        GenericHlsDownloader.ResolvedStream selected =
                GenericHlsDownloader.resolveForTests(master, false, fetcher);
        equal(unknownHigh, selected.getMediaPlaylistUrl(),
                "BEST usa bitrate cuando una resolución no está declarada");
        equal(5_000_000L, selected.getBandwidth(),
                "BEST conserva el bitrate de la variante sin resolución");

        String bothUnknown = "https://quality.example.com/all-unknown.m3u8";
        String highest = "https://quality.example.com/highest.m3u8";
        FakeFetcher noDimensions = new FakeFetcher()
                .text(bothUnknown, "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=900000\nmedium.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2200000\nhighest.m3u8\n")
                .text(highest, tsPlaylist("segment.ts"));
        GenericHlsDownloader.ResolvedStream selectedByBandwidth =
                GenericHlsDownloader.resolveForTests(bothUnknown, false, noDimensions);
        equal(highest, selectedByBandwidth.getMediaPlaylistUrl(),
                "BEST elige mayor bitrate cuando ninguna variante declara resolución");

        String mixedFirst = "https://quality.example.com/mixed-first.m3u8";
        String mixedSecond = "https://quality.example.com/mixed-second.m3u8";
        String highestBandwidth = "https://quality.example.com/known-360-highest.m3u8";
        String mixedVariantsA = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080\nknown-1080.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=640x360\nknown-360-highest.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\nunknown-medium.m3u8\n";
        String mixedVariantsB = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\nunknown-medium.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080\nknown-1080.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=640x360\nknown-360-highest.m3u8\n";
        FakeFetcher mixedOrderA = new FakeFetcher()
                .text(mixedFirst, mixedVariantsA)
                .text(highestBandwidth, tsPlaylist("segment.ts"));
        FakeFetcher mixedOrderB = new FakeFetcher()
                .text(mixedSecond, mixedVariantsB)
                .text(highestBandwidth, tsPlaylist("segment.ts"));
        equal(highestBandwidth,
                GenericHlsDownloader.resolveForTests(mixedFirst, false, mixedOrderA)
                        .getMediaPlaylistUrl(),
                "BEST mixto usa una comparación transitiva por bitrate");
        equal(highestBandwidth,
                GenericHlsDownloader.resolveForTests(mixedSecond, false, mixedOrderB)
                        .getMediaPlaylistUrl(),
                "BEST mixto no depende del orden de la lista maestra");
    }

    private static void testRedirectedPlaylistControlsRelativeBase() throws Exception {
        String shared = "https://share.example.com/watch/list.m3u8";
        String redirected = "https://edge.example.com/vod/final.m3u8";
        String segment = "https://edge.example.com/vod/chunk.ts";
        FakeFetcher fetcher = new FakeFetcher()
                .textRedirect(shared, redirected, tsPlaylist("chunk.ts"))
                .bytes(segment, transportStream(3, 0x31));
        GenericHlsDownloader.ResolvedStream stream =
                GenericHlsDownloader.resolveForTests(shared, false, fetcher);
        equal(redirected, stream.getMediaPlaylistUrl(), "se conserva el destino HTTPS final");
        GenericHlsDownloader.downloadToStreamForTests(
                stream, new ByteArrayOutputStream(), fetcher);
        equal(segment, fetcher.requestedUrls().get(1), "las rutas relativas usan el destino final");
        pass();
    }

    private static void testByteRanges() throws Exception {
        String playlist = "https://range.example.com/vod/list.m3u8";
        String blob = "https://range.example.com/vod/all.ts";
        byte[] source = concat(
                transportStream(1, 0x41),
                transportStream(1, 0x42),
                transportStream(1, 0x43));
        FakeFetcher fetcher = new FakeFetcher()
                .text(playlist, "#EXTM3U\n"
                        + "#EXTINF:2,\n#EXT-X-BYTERANGE:188@0\nall.ts\n"
                        + "#EXTINF:2,\n#EXT-X-BYTERANGE:188\nall.ts\n"
                        + "#EXT-X-ENDLIST\n")
                .bytes(blob, source);
        GenericHlsDownloader.ResolvedStream stream =
                GenericHlsDownloader.resolveForTests(playlist, false, fetcher);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GenericHlsDownloader.downloadToStreamForTests(stream, output, fetcher);
        bytesEqual(Arrays.copyOfRange(source, 0, 376), output.toByteArray(),
                "los rangos explícito e implícito se concatenan");
        equal(Arrays.asList("0:188", "188:188"), fetcher.requestedRanges(),
                "se solicitan rangos exactos");

        String fmp4Playlist = "https://range.example.com/vod/fmp4.m3u8";
        String fmp4Blob = "https://range.example.com/vod/all.mp4";
        byte[] map = concat(box("ftyp", bytes("isom0000isomiso6")), box("moov", new byte[0]));
        byte[] fragment = concat(box("moof", new byte[0]), box("mdat", bytes("fragment")));
        byte[] combined = concat(map, fragment);
        FakeFetcher rangedFmp4 = new FakeFetcher()
                .text(fmp4Playlist, "#EXTM3U\n"
                        + "#EXT-X-MAP:URI=\"all.mp4\",BYTERANGE=\""
                        + map.length + "@0\"\n"
                        + "#EXTINF:2,\n#EXT-X-BYTERANGE:"
                        + fragment.length + "@" + map.length + "\nall.mp4\n"
                        + "#EXT-X-ENDLIST\n")
                .bytes(fmp4Blob, combined);
        GenericHlsDownloader.ResolvedStream fmp4 =
                GenericHlsDownloader.resolveForTests(fmp4Playlist, false, rangedFmp4);
        ByteArrayOutputStream fmp4Output = new ByteArrayOutputStream();
        GenericHlsDownloader.downloadToStreamForTests(fmp4, fmp4Output, rangedFmp4);
        bytesEqual(combined, fmp4Output.toByteArray(), "EXT-X-MAP y fragmento con rangos");

        FakeFetcher broken = new FakeFetcher()
                .text(playlist, "#EXTM3U\n#EXTINF:2,\n"
                        + "#EXT-X-BYTERANGE:188\nall.ts\n#EXT-X-ENDLIST\n");
        expectIOException("rango implícito sin predecesor", new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                GenericHlsDownloader.resolveForTests(playlist, false, broken);
            }
        });
    }

    private static void testExternalAudioVariantIsSkipped() throws Exception {
        String master = "https://audio.example.com/master.m3u8";
        String muxed = "https://audio.example.com/muxed.m3u8";
        FakeFetcher fetcher = new FakeFetcher()
                .text(master, "#EXTM3U\n"
                        + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"separate\",URI=\"audio.m3u8\"\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=7000000,RESOLUTION=1920x1080,AUDIO=\"separate\"\n"
                        + "silent-high.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=854x480\n"
                        + "muxed.m3u8\n")
                .text(muxed, tsPlaylist("segment.ts"));
        GenericHlsDownloader.ResolvedStream stream =
                GenericHlsDownloader.resolveForTests(master, false, fetcher);
        equal(muxed, stream.getMediaPlaylistUrl(), "se evita vídeo con audio externo");

        FakeFetcher onlySeparate = new FakeFetcher().text(master, "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"a.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"a\"\nvideo.m3u8\n");
        expectIOException("audio separado sin muxer", new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                GenericHlsDownloader.resolveForTests(master, false, onlySeparate);
            }
        });

        String audioChoice = "https://audio.example.com/codecs.m3u8";
        String video = "https://audio.example.com/video.m3u8";
        FakeFetcher codecs = new FakeFetcher()
                .text(audioChoice, "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.2\"\n"
                        + "audio-only.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,"
                        + "CODECS=\"avc1.4d401e,mp4a.40.2\"\nvideo.m3u8\n")
                .text(video, tsPlaylist("segment.ts"));
        GenericHlsDownloader.ResolvedStream saver =
                GenericHlsDownloader.resolveForTests(audioChoice, true, codecs);
        equal(video, saver.getMediaPlaylistUrl(), "ahorro no elige una variante solo de audio");
    }

    private static void testEncryptionAndDrmAreRejected() throws Exception {
        String url = "https://secure.example.com/list.m3u8";
        FakeFetcher aes = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXTINF:2,\nseg.ts\n#EXT-X-ENDLIST\n");
        expectIOException("AES-128", resolveAction(url, aes));

        FakeFetcher sampleAes = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.apple.streamingkeydelivery\"\n"
                + "#EXTINF:2,\nseg.ts\n#EXT-X-ENDLIST\n");
        expectIOException("SAMPLE-AES/DRM", resolveAction(url, sampleAes));

        FakeFetcher session = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=100\nchild.m3u8\n");
        expectIOException("SESSION-KEY", resolveAction(url, session));

        FakeFetcher none = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=NONE\n"
                + "#EXTINF:2,\nseg.ts\n#EXT-X-ENDLIST\n");
        GenericHlsDownloader.resolveForTests(url, false, none);
        pass();
    }

    private static void testLiveAndGapPlaylistsAreRejected() throws Exception {
        String url = "https://live.example.com/list.m3u8";
        FakeFetcher live = new FakeFetcher().text(url,
                "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nseg.ts\n");
        expectIOException("directo sin ENDLIST", resolveAction(url, live));

        FakeFetcher gap = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXTINF:4,\n#EXT-X-GAP\nseg.ts\n#EXT-X-ENDLIST\n");
        expectIOException("segmento ausente", resolveAction(url, gap));

        FakeFetcher discontinuity = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXTINF:4,\na.ts\n#EXT-X-DISCONTINUITY\n"
                + "#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n");
        expectIOException("discontinuidad que necesita remux", resolveAction(url, discontinuity));

        FakeFetcher endListAnywhere = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXT-X-ENDLIST\n#EXTINF:4,\nseg.ts\n");
        GenericHlsDownloader.resolveForTests(url, false, endListAnywhere);
        pass();
    }

    private static void testUnsafeUrlsAndRedirectsAreRejected() throws Exception {
        FakeFetcher unused = new FakeFetcher();
        expectIOException("HTTP", resolveAction("http://video.example.com/list.m3u8", unused));
        expectIOException("loopback", resolveAction("https://127.0.0.1/list.m3u8", unused));
        expectIOException("userinfo", resolveAction("https://name@video.example.com/list.m3u8", unused));
        expectIOException("puerto", resolveAction("https://video.example.com:8443/list.m3u8", unused));

        String source = "https://share.example.com/list.m3u8";
        FakeFetcher downgrade = new FakeFetcher().textRedirect(
                source,
                "http://cdn.example.com/list.m3u8",
                tsPlaylist("seg.ts"));
        expectIOException("redirección con downgrade", resolveAction(source, downgrade));

        FakeFetcher privateRedirect = new FakeFetcher().textRedirect(
                source,
                "https://192.168.1.5/list.m3u8",
                tsPlaylist("seg.ts"));
        expectIOException("redirección privada", resolveAction(source, privateRedirect));
    }

    private static void testPartialEndedWindowIsRejected() throws Exception {
        String url = "https://window.example.com/list.m3u8";
        FakeFetcher partial = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n");
        expectIOException("ventana final incompleta", resolveAction(url, partial));

        FakeFetcher declaredVod = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n");
        GenericHlsDownloader.resolveForTests(url, false, declaredVod);

        FakeFetcher sequenceMetadata = new FakeFetcher().text(url, "#EXTM3U\n"
                + "#EXT-X-DISCONTINUITY-SEQUENCE:0\n"
                + "#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n");
        GenericHlsDownloader.resolveForTests(url, false, sequenceMetadata);
        pass();
    }

    private static void testContainerAndSignatureChecks() throws Exception {
        String mp4NoMap = "https://format.example.com/mp4.m3u8";
        FakeFetcher missingMap = new FakeFetcher().text(mp4NoMap,
                "#EXTM3U\n#EXTINF:3,\nseg.m4s\n#EXT-X-ENDLIST\n");
        expectIOException("fMP4 sin mapa", resolveAction(mp4NoMap, missingMap));

        String tsUrl = "https://format.example.com/ts.m3u8";
        String segment = "https://format.example.com/seg.ts";
        FakeFetcher invalidBytes = new FakeFetcher()
                .text(tsUrl, tsPlaylist("seg.ts"))
                .bytes(segment, bytes("esto no es MPEG-TS"));
        GenericHlsDownloader.ResolvedStream stream =
                GenericHlsDownloader.resolveForTests(tsUrl, false, invalidBytes);
        expectIOException("firma TS falsa", new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                GenericHlsDownloader.downloadToStreamForTests(
                        stream, new ByteArrayOutputStream(), invalidBytes);
            }
        });

        FakeFetcher partialForWhole = new FakeFetcher()
                .text(tsUrl, tsPlaylist("seg.ts"))
                .partialBytes(segment, transportStream(3, 0x55));
        GenericHlsDownloader.ResolvedStream partialStream =
                GenericHlsDownloader.resolveForTests(tsUrl, false, partialForWhole);
        expectIOException("HTTP 206 sin rango solicitado", new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                GenericHlsDownloader.downloadToStreamForTests(
                        partialStream, new ByteArrayOutputStream(), partialForWhole);
            }
        });
    }

    private static void testMapChangesAndCyclesAreRejected() throws Exception {
        String maps = "https://maps.example.com/list.m3u8";
        FakeFetcher changed = new FakeFetcher().text(maps, "#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"init-a.mp4\"\n#EXTINF:2,\na.m4s\n"
                + "#EXT-X-MAP:URI=\"init-b.mp4\"\n#EXTINF:2,\nb.m4s\n"
                + "#EXT-X-ENDLIST\n");
        expectIOException("cambio de EXT-X-MAP", resolveAction(maps, changed));

        String tsMaps = "https://maps.example.com/transport.m3u8";
        FakeFetcher changedTs = new FakeFetcher().text(tsMaps, "#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"pat-a.ts\"\n#EXTINF:2,\na.ts\n"
                + "#EXT-X-MAP:URI=\"pat-b.ts\"\n#EXTINF:2,\nb.ts\n"
                + "#EXT-X-ENDLIST\n")
                .bytes("https://maps.example.com/pat-a.ts", transportStream(1, 0x61))
                .bytes("https://maps.example.com/a.ts", transportStream(1, 0x62))
                .bytes("https://maps.example.com/pat-b.ts", transportStream(1, 0x63))
                .bytes("https://maps.example.com/b.ts", transportStream(1, 0x64));
        GenericHlsDownloader.ResolvedStream ts =
                GenericHlsDownloader.resolveForTests(tsMaps, false, changedTs);
        equal("ts", ts.getSuggestedExtension(), "EXT-X-MAP también admite MPEG-TS");
        GenericHlsDownloader.downloadToStreamForTests(
                ts, new ByteArrayOutputStream(), changedTs);
        equal(Arrays.asList(
                        tsMaps,
                        "https://maps.example.com/pat-a.ts",
                        "https://maps.example.com/a.ts",
                        "https://maps.example.com/pat-b.ts",
                        "https://maps.example.com/b.ts"),
                changedTs.requestedUrls(),
                "los mapas TS cambiantes se insertan en orden");

        String implicitMap = "https://maps.example.com/implicit.m3u8";
        FakeFetcher implicit = new FakeFetcher().text(implicitMap, "#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"blob.mp4\",BYTERANGE=\"32\"\n"
                + "#EXTINF:2,\nseg.m4s\n#EXT-X-ENDLIST\n");
        expectIOException("mapa con rango sin offset", resolveAction(implicitMap, implicit));

        String cycle = "https://cycle.example.com/master.m3u8";
        FakeFetcher loop = new FakeFetcher().text(cycle, "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=100\nmaster.m3u8\n");
        expectIOException("ciclo de master", resolveAction(cycle, loop));

        String malformed = "https://cycle.example.com/no-bandwidth.m3u8";
        FakeFetcher noBandwidth = new FakeFetcher().text(malformed,
                "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=640x360\nchild.m3u8\n");
        expectIOException("variante sin BANDWIDTH", resolveAction(malformed, noBandwidth));
    }

    private static void testContentEncodingIsRejected() throws Exception {
        String playlist = "https://encoding.example.com/list.m3u8";
        FakeFetcher compressedManifest = new FakeFetcher().encodedText(
                playlist, tsPlaylist("seg.ts"), "gzip");
        expectIOException("playlist comprimida pese a identity",
                resolveAction(playlist, compressedManifest));

        String segment = "https://encoding.example.com/seg.ts";
        FakeFetcher compressedSegment = new FakeFetcher()
                .text(playlist, tsPlaylist("seg.ts"))
                .encodedBytes(segment, transportStream(3, 0x71), "br");
        GenericHlsDownloader.ResolvedStream stream =
                GenericHlsDownloader.resolveForTests(playlist, false, compressedSegment);
        expectIOException("segmento comprimido pese a identity", new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                GenericHlsDownloader.downloadToStreamForTests(
                        stream, new ByteArrayOutputStream(), compressedSegment);
            }
        });
    }

    private static void testStrictPlaylistAndSegmentLimits() throws Exception {
        String url = "https://limits.example.com/list.m3u8";
        StringBuilder tooMany = new StringBuilder("#EXTM3U\n");
        for (int index = 0; index <= 20_000; index++) {
            tooMany.append("#EXTINF:1,\ns").append(index).append(".ts\n");
        }
        tooMany.append("#EXT-X-ENDLIST\n");
        FakeFetcher many = new FakeFetcher().text(url, tooMany.toString());
        expectIOException("límite de segmentos", resolveAction(url, many));

        char[] huge = new char[2 * 1024 * 1024 + 1];
        Arrays.fill(huge, 'x');
        FakeFetcher oversized = new FakeFetcher().text(url, "#EXTM3U\n" + new String(huge));
        expectIOException("límite de playlist", resolveAction(url, oversized));
    }

    private static ThrowingRunnable resolveAction(
            final String url,
            final FakeFetcher fetcher) {
        return new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                GenericHlsDownloader.resolveForTests(url, false, fetcher);
            }
        };
    }

    private static String tsPlaylist(String segment) {
        return "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4,\n"
                + segment + "\n#EXT-X-ENDLIST\n";
    }

    private static byte[] transportStream(int packets, int fill) {
        byte[] result = new byte[188 * packets];
        Arrays.fill(result, (byte) fill);
        for (int packet = 0; packet < packets; packet++) {
            result[packet * 188] = 0x47;
        }
        return result;
    }

    private static byte[] box(String type, byte[] body) {
        if (type.length() != 4) {
            throw new IllegalArgumentException("ISO box type");
        }
        int size = 8 + body.length;
        byte[] result = new byte[size];
        result[0] = (byte) (size >>> 24);
        result[1] = (byte) (size >>> 16);
        result[2] = (byte) (size >>> 8);
        result[3] = (byte) size;
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, result, 4, 4);
        System.arraycopy(body, 0, result, 8, body.length);
        return result;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) {
            size += value.length;
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static void expectIOException(String label, ThrowingRunnable operation)
            throws Exception {
        try {
            operation.run();
            throw new AssertionError("Se esperaba rechazo: " + label);
        } catch (IOException expected) {
            pass();
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": esperado=" + expected + ", actual=" + actual);
        }
        pass();
    }

    private static void bytesEqual(byte[] expected, byte[] actual, String label) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(label + ": los bytes no coinciden");
        }
        pass();
    }

    private static void pass() {
        passed++;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FakeFetcher implements GenericHlsDownloader.ResourceFetcher {
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final List<String> urls = new ArrayList<>();
        private final List<String> ranges = new ArrayList<>();

        FakeFetcher text(String url, String value) {
            return textRedirect(url, url, value);
        }

        FakeFetcher textRedirect(String url, String finalUrl, String value) {
            entries.put(url, new Entry(
                    finalUrl,
                    value.getBytes(StandardCharsets.UTF_8),
                    200,
                    null,
                    null));
            return this;
        }

        FakeFetcher encodedText(String url, String value, String encoding) {
            entries.put(url, new Entry(
                    url,
                    value.getBytes(StandardCharsets.UTF_8),
                    200,
                    null,
                    encoding));
            return this;
        }

        FakeFetcher bytes(String url, byte[] value) {
            entries.put(url, new Entry(url, value, 200, null, null));
            return this;
        }

        FakeFetcher encodedBytes(String url, byte[] value, String encoding) {
            entries.put(url, new Entry(url, value, 200, null, encoding));
            return this;
        }

        FakeFetcher partialBytes(String url, byte[] value) {
            entries.put(url, new Entry(
                    url,
                    value,
                    206,
                    "bytes 0-" + (value.length - 1) + "/" + value.length,
                    null));
            return this;
        }

        List<String> requestedUrls() {
            return new ArrayList<>(urls);
        }

        List<String> requestedRanges() {
            return new ArrayList<>(ranges);
        }

        @Override
        public GenericHlsDownloader.FetchResponse fetch(
                String url,
                GenericHlsDownloader.ByteRange range,
                boolean playlist) throws IOException {
            urls.add(url);
            Entry entry = entries.get(url);
            if (entry == null) {
                throw new IOException("Fixture ausente para " + url);
            }
            if (range == null) {
                return new GenericHlsDownloader.FetchResponse(
                        entry.finalUrl,
                        entry.status,
                        entry.body.length,
                        entry.contentRange,
                        entry.contentEncoding,
                        new ByteArrayInputStream(entry.body));
            }
            if (range.offset < 0L || range.length > Integer.MAX_VALUE
                    || range.offset > entry.body.length
                    || range.offset + range.length > entry.body.length) {
                throw new IOException("Rango de fixture inválido");
            }
            int start = (int) range.offset;
            int endExclusive = (int) (range.offset + range.length);
            byte[] slice = Arrays.copyOfRange(entry.body, start, endExclusive);
            ranges.add(range.offset + ":" + range.length);
            return new GenericHlsDownloader.FetchResponse(
                    entry.finalUrl,
                    206,
                    slice.length,
                    "bytes " + start + "-" + (endExclusive - 1) + "/" + entry.body.length,
                    entry.contentEncoding,
                    new ByteArrayInputStream(slice));
        }
    }

    private static final class Entry {
        final String finalUrl;
        final byte[] body;
        final int status;
        final String contentRange;
        final String contentEncoding;

        Entry(
                String finalUrl,
                byte[] body,
                int status,
                String contentRange,
                String contentEncoding) {
            this.finalUrl = finalUrl;
            this.body = body;
            this.status = status;
            this.contentRange = contentRange;
            this.contentEncoding = contentEncoding;
        }
    }
}
