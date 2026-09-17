// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.IOException;
import java.net.URI;

/** Network-free regression tests for the generic public-page video selector. */
public final class GenericExtractorParserTest {
    private static final URI PAGE = URI.create("https://site.example.com/articles/watch?id=42");

    public static void main(String[] args) throws Exception {
        selectsLargeMainVideoAndFiltersAdvertising();
        choosesQualityAndSupportsDataSaver();
        dataSaverNeverCrossesIntoAnotherVideo();
        parsesOpenGraphAndDeduplicatesSources();
        parsesJsonLdVideoObject();
        ignoresNonMediaJsonLdEmbedUrl();
        parsesEscapedQualityMapsAndHls();
        parsesGenericMediaDefinitions();
        keepsQualityNextToLongSignedUrls();
        recognisesAdditionalAdvertisingMarkers();
        infersOpaqueHlsFieldFromFormat();
        resolvesRelativeAndProtocolRelativeUrls();
        stripsFragmentsWithoutChangingSignedQueries();
        rejectsInsecureAndPrivateMediaUrls();
        rejectsGifAndDrmCandidates();
        treatsHiddenAndLoopingVideoAsAuxiliary();
        ignoresVideoMarkupInsideInertHtml();
        isolatesScriptSignalsBetweenSiblingUrls();
        usesPerEncodingJsonLdMetadata();
        keepsWeakOnlyCandidateAsFallback();
        keepsDocumentOrderForExactTies();
        ignoresBrokenAndOverdeepJsonLd();
        enforcesCandidateLimit();
        retainsLatePrincipalBeyondCandidateLimit();
        rejectsNonHttpsPage();
        System.out.println("GenericExtractorParserTest: 25 PASS");
    }

    private static void selectsLargeMainVideoAndFiltersAdvertising() throws Exception {
        String padding = repeat('x', 400);
        String html = "<div class='advertisement'><video id='ad-preroll' width='300' height='180' "
                + "data-duration='5' data-filesize='300 KB' src='https://ads.cdn-example.com/ad.mp4' "
                + "autoplay muted loop></video></div>" + padding
                + "<main><video id='main-player' width='1280' height='720' data-duration='600' "
                + "data-filesize='120 MB' poster='https://img.cdn-example.com/main.jpg' "
                + "src='https://video.cdn-example.com/full-movie.mp4'></video></main>"
                + "<video src='https://img.cdn-example.com/promo.gif'></video>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal("https://video.cdn-example.com/full-movie.mp4", result.getMainMedia().getUrl(), "vídeo principal");
        equal(1, result.getCandidates().size(), "filtra anuncio y GIF");
        equal(1280, result.getMainMedia().getWidth(), "ancho principal");
        equal(600.0, result.getMainMedia().getDurationSeconds(), "duración principal");
        equal("https://img.cdn-example.com/main.jpg", result.getMainMedia().getPosterUrl(), "poster");
        check(!result.isFallbackSelection(), "la selección principal no es fallback");
    }

    private static void choosesQualityAndSupportsDataSaver() throws Exception {
        String html = "<main><video class='main-player' data-duration='500'>"
                + "<source src='https://cdn.example.com/film-360.mp4' type='video/mp4' "
                + "width='640' height='360' data-filesize='18 MB'>"
                + "<source src='https://cdn.example.com/film-720.mp4' type='video/mp4' "
                + "width='1280' height='720' data-filesize='65 MB'>"
                + "<source src='https://cdn.example.com/film-1080.mp4' type='video/mp4' "
                + "width='1920' height='1080' data-filesize='140 MB'>"
                + "</video></main>";
        GenericExtractor.Result maximum = GenericExtractor.parse(html, PAGE, false);
        GenericExtractor.Result saver = GenericExtractor.parse(html, PAGE, true);
        equal("1080p", maximum.getMainMedia().getQualityLabel(), "máxima calidad");
        equal("https://cdn.example.com/film-1080.mp4", maximum.getMainMedia().getUrl(), "URL 1080p");
        equal("360p", saver.getMainMedia().getQualityLabel(), "calidad ahorro");
        equal("https://cdn.example.com/film-360.mp4", saver.getMainMedia().getUrl(), "URL ahorro");
    }

    private static void dataSaverNeverCrossesIntoAnotherVideo() throws Exception {
        String html = "<main><video class='main-player' poster='/feature.jpg' data-duration='600'>"
                + "<source src='https://cdn.example.com/feature-1080.mp4' width='1920' height='1080' data-size='100 MB'>"
                + "<source src='https://cdn.example.com/feature-360.mp4' width='640' height='360' data-size='18 MB'>"
                + "</video></main>"
                + repeat('x', 300)
                + "<video controls src='https://other.example.com/unrelated-small.mp4' "
                + "width='640' height='360' data-duration='600' data-size='3 MB'></video>";
        GenericExtractor.Result saver = GenericExtractor.parse(html, PAGE, true);
        equal("https://cdn.example.com/feature-360.mp4", saver.getMainMedia().getUrl(), "ahorro dentro del mismo contenido");
    }

    private static void parsesOpenGraphAndDeduplicatesSources() throws Exception {
        String media = "https://cdn.example.com/movie.mp4?token=a&amp;b=2";
        String html = "<meta content='video/mp4' property='og:video:type'>"
                + "<meta property='og:video:width' content='1920'>"
                + "<meta content='1080' property='og:video:height'>"
                + "<meta property='og:image' content='/poster.jpg'>"
                + "<meta property='og:video:secure_url' content='" + media + "'>"
                + "<video src='" + media + "' width='1920' height='1080'></video>"
                + "<script>window.file=\"https://cdn.example.com/movie.mp4?token=a&amp;b=2\";</script>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal(1, result.getCandidates().size(), "deduplicación multifuente");
        equal("https://cdn.example.com/movie.mp4?token=a&b=2", result.getMainMedia().getUrl(), "query HTML");
        equal("opengraph", result.getMainMedia().getDiscoverySource(), "conserva evidencia semántica fuerte");
        equal("https://site.example.com/poster.jpg", result.getMainMedia().getPosterUrl(), "poster relativo");
    }

    private static void parsesJsonLdVideoObject() throws Exception {
        String html = "<script type='application/ld+json'>{"
                + "\"@context\":\"https://schema.org\",\"@graph\":[{"
                + "\"@type\":[\"Thing\",\"VideoObject\"],"
                + "\"contentUrl\":\"https://media.example.com/features/story.webm?sig=abc\","
                + "\"thumbnailUrl\":[\"https://media.example.com/poster.jpg\"],"
                + "\"width\":1920,\"height\":1080,\"duration\":\"PT1H2M3S\","
                + "\"contentSize\":\"700 MB\",\"encodingFormat\":\"video/webm\"}]}"
                + "</script>";
        GenericExtractor.Media media = GenericExtractor.parse(html, PAGE).getMainMedia();
        equal("https://media.example.com/features/story.webm?sig=abc", media.getUrl(), "contentUrl JSON-LD");
        equal("webm", media.getSuggestedExtension(), "extensión JSON-LD");
        equal(3723.0, media.getDurationSeconds(), "duración ISO-8601");
        equal(700L * 1024 * 1024, media.getContentLength(), "tamaño JSON-LD");
    }

    private static void ignoresNonMediaJsonLdEmbedUrl() throws Exception {
        String html = "<script type='application/ld+json'>{\"@type\":\"VideoObject\","
                + "\"embedUrl\":\"https://player.example.com/embed/42\"}</script>";
        expectFailure(html, "un embed no es un archivo directo");
    }

    private static void parsesEscapedQualityMapsAndHls() throws Exception {
        String html = "<script>var sources={"
                + "\"quality_360p\":\"https:\\/\\/cdn.example.com\\/title_360.mp4?token=a&amp;b=1\","
                + "\"quality_1080p\":\"https:\\u002F\\u002Fcdn.example.com\\u002Ftitle_1080.mp4?token=b\","
                + "\"hlsUrl\":\"https:\\/\\/stream.example.com\\/master.m3u8?token=h\"};</script>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal("https://cdn.example.com/title_1080.mp4?token=b", result.getMainMedia().getUrl(), "source map 1080p");
        equal("1080p", result.getMainMedia().getQualityLabel(), "calidad inferida del source map");
        check(find(result, "https://stream.example.com/master.m3u8?token=h").isPlaylist(), "detecta HLS");
        equal(3, result.getCandidates().size(), "tres representaciones sin duplicados");
    }

    private static void parsesGenericMediaDefinitions() throws Exception {
        String html = "<script>window.player={\"mediaDefinitions\":["
                + "{\"format\":\"mp4\",\"quality\":\"480\","
                + "\"videoUrl\":\"https://cdn.example.com/content/scene-480.mp4\"},"
                + "{\"format\":\"mp4\",\"quality\":\"720\","
                + "\"videoUrlHigh\":\"https://cdn.example.com/content/scene-720.mp4\"}]};</script>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal("https://cdn.example.com/content/scene-720.mp4", result.getMainMedia().getUrl(), "mediaDefinitions 720");
        equal("720p", result.getMainMedia().getQualityLabel(), "etiqueta mediaDefinitions");
    }

    private static void keepsQualityNextToLongSignedUrls() throws Exception {
        String padding = repeat('a', 180);
        String html = "<script>window.player={\"mediaDefinitions\":["
                + "{\"quality\":\"360\",\"videoUrl\":\"https://cdn.example.com/title-360.mp4?token="
                + padding + "\"},"
                + "{\"quality\":\"1080\",\"videoUrl\":\"https://cdn.example.com/title-1080.mp4?token="
                + padding + "\"}]};</script>";
        GenericExtractor.Media selected = GenericExtractor.parse(html, PAGE).getMainMedia();
        equal("1080p", selected.getQualityLabel(), "calidad junto a URL firmada larga");
        equal(true, selected.getUrl().contains("title-1080.mp4"), "elige URL firmada 1080p");
    }

    private static void recognisesAdditionalAdvertisingMarkers() throws Exception {
        String html = "<script>var videoAd='https://cdn.example.com/vpaid-clip.mp4';"
                + "var sources={\"quality_1080p\":\"https://cdn.example.com/main-1080.mp4\"};"
                + "</script>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal("https://cdn.example.com/main-1080.mp4", result.getMainMedia().getUrl(),
                "VAST/VPAID/videoAd no desplaza al principal");
        equal(null, find(result, "https://cdn.example.com/vpaid-clip.mp4"),
                "filtra el candidato publicitario adicional");
    }

    private static void infersOpaqueHlsFieldFromFormat() throws Exception {
        String html = "<script>var mediaDefinitions=[{\"format\":\"hls\","
                + "\"videoUrl\":\"https://stream.example.com/manifest?id=42&amp;token=x\"}];</script>";
        GenericExtractor.Media media = GenericExtractor.parse(html, PAGE).getMainMedia();
        equal("https://stream.example.com/manifest?id=42&token=x", media.getUrl(), "HLS sin extensión");
        equal("m3u8", media.getSuggestedExtension(), "extensión HLS inferida");
        check(media.isPlaylist(), "marca playlist HLS sin extensión");
    }

    private static void resolvesRelativeAndProtocolRelativeUrls() throws Exception {
        String html = "<video class='main-player'><source src='/media/main.mp4' width='1280' height='720'>"
                + "<source src='../alternate.webm' width='640' height='360'>"
                + "<source src='//cdn.example.com/cross-host.mp4' width='854' height='480'></video>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        check(find(result, "https://site.example.com/media/main.mp4") != null, "relativa raíz");
        check(find(result, "https://site.example.com/alternate.webm") != null, "relativa padre");
        check(find(result, "https://cdn.example.com/cross-host.mp4") != null, "protocol-relative");
    }

    private static void stripsFragmentsWithoutChangingSignedQueries() throws Exception {
        String html = "<video src='https://cdn.example.com/a%2Fb/movie.mp4?Signature=A%2FB&amp;x=1#chapter'></video>"
                + "<meta property='og:video' content='https://cdn.example.com/a%2Fb/movie.mp4?Signature=A%2FB&amp;x=1'>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal(1, result.getCandidates().size(), "fragmento deduplicado");
        equal("https://cdn.example.com/a%2Fb/movie.mp4?Signature=A%2FB&x=1", result.getMainMedia().getUrl(), "query firmada intacta");
    }

    private static void rejectsInsecureAndPrivateMediaUrls() throws Exception {
        String html = "<video src='http://cdn.example.com/insecure.mp4'></video>"
                + "<video src='https://127.0.0.1/private.mp4'></video>"
                + "<video src='https://localhost/private.mp4'></video>"
                + "<video class='main-player' src='https://cdn.example.com/public.mp4'></video>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal(1, result.getCandidates().size(), "solo URL HTTPS pública");
        equal("https://cdn.example.com/public.mp4", result.getMainMedia().getUrl(), "URL pública");
    }

    private static void rejectsGifAndDrmCandidates() throws Exception {
        String html = "<video src='https://cdn.example.com/animated.gif' type='image/gif'></video>"
                + "<video data-drm='widevine' src='https://cdn.example.com/protected.mp4'></video>"
                + "<video class='main-player' src='https://cdn.example.com/open.mp4'></video>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal(1, result.getCandidates().size(), "filtra GIF y DRM");
        equal(1, result.getRejectedDrmCandidates(), "informa DRM rechazado");
    }

    private static void treatsHiddenAndLoopingVideoAsAuxiliary() throws Exception {
        String html = "<video hidden aria-hidden='true' style='display:none' "
                + "src='https://cdn.example.com/invisible.mp4'></video>"
                + "<video autoplay muted loop src='https://cdn.example.com/loop.mp4'></video>"
                + "<main><video controls src='https://cdn.example.com/visible.mp4'></video></main>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal(1, result.getCandidates().size(), "ocultos filtrados");
        equal("https://cdn.example.com/visible.mp4", result.getMainMedia().getUrl(), "vídeo visible");
        check(!result.getMainMedia().isLikelyAuxiliary(), "principal no auxiliar");
    }

    private static void ignoresVideoMarkupInsideInertHtml() throws Exception {
        String html = "<!-- <video controls src='https://cdn.example.com/comment.mp4'></video> -->"
                + "<template><video controls src='https://cdn.example.com/template.mp4'></video></template>"
                + "<script>var markup=\"<video controls src='https://cdn.example.com/string.mp4'>\";</script>"
                + "<main><video controls src='https://cdn.example.com/real.mp4'></video></main>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal("https://cdn.example.com/real.mp4", result.getMainMedia().getUrl(), "markup inerte no suplanta al real");
        check(find(result, "https://cdn.example.com/comment.mp4") == null, "ignora comentario");
        check(find(result, "https://cdn.example.com/template.mp4") == null, "ignora template");
        GenericExtractor.Media script = find(result, "https://cdn.example.com/string.mp4");
        check(script == null || "script".equals(script.getDiscoverySource()), "string solo como evidencia de script");
    }

    private static void isolatesScriptSignalsBetweenSiblingUrls() throws Exception {
        String html = "<script>var sources={\"drmAd\":\"https://cdn.example.com/ad-one.mp4\","
                + "\"mainVideo\":\"https://cdn.example.com/main-film-720.mp4\"};</script>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal("https://cdn.example.com/main-film-720.mp4", result.getMainMedia().getUrl(), "señal principal aislada");
        equal(1, result.getRejectedDrmCandidates(), "solo rechaza URL DRM asociada");
    }

    private static void usesPerEncodingJsonLdMetadata() throws Exception {
        String html = "<script type='application/ld+json'>{\"@type\":\"VideoObject\","
                + "\"thumbnailUrl\":\"https://img.example.com/same.jpg\",\"duration\":\"PT20M\","
                + "\"encoding\":["
                + "{\"@type\":\"MediaObject\",\"contentUrl\":\"https://cdn.example.com/movie-low.mp4\","
                + "\"encodingFormat\":\"video/mp4\",\"width\":640,\"height\":360,\"contentSize\":\"20 MB\"},"
                + "{\"@type\":\"MediaObject\",\"contentUrl\":\"https://cdn.example.com/movie-high.mp4\","
                + "\"encodingFormat\":\"video/mp4\",\"width\":1920,\"height\":1080,\"contentSize\":\"120 MB\"}]}"
                + "</script>";
        GenericExtractor.Result maximum = GenericExtractor.parse(html, PAGE, false);
        GenericExtractor.Result saver = GenericExtractor.parse(html, PAGE, true);
        equal("https://cdn.example.com/movie-high.mp4", maximum.getMainMedia().getUrl(), "encoding máximo");
        equal("https://cdn.example.com/movie-low.mp4", saver.getMainMedia().getUrl(), "encoding ahorro");
    }

    private static void keepsWeakOnlyCandidateAsFallback() throws Exception {
        String html = "<aside class='advertisement'><video src='https://ads.example.com/only.mp4' "
                + "width='240' height='120' data-duration='3' data-size='120 KB' loop></video></aside>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal(1, result.getCandidates().size(), "fallback conserva único candidato");
        check(result.isFallbackSelection(), "fallback marcado");
    }

    private static void keepsDocumentOrderForExactTies() throws Exception {
        String html = "<script>var a='https://cdn.example.com/first.mp4';"
                + "var b='https://cdn.example.com/second.mp4';</script>";
        GenericExtractor.Result result = GenericExtractor.parse(html, PAGE);
        equal("https://cdn.example.com/first.mp4", result.getMainMedia().getUrl(), "desempate documental");
    }

    private static void ignoresBrokenAndOverdeepJsonLd() throws Exception {
        StringBuilder deep = new StringBuilder();
        for (int index = 0; index < 55; index++) deep.append("{\"x\":");
        deep.append("1");
        for (int index = 0; index < 55; index++) deep.append('}');
        String html = "<script type='application/ld+json'>{broken</script>"
                + "<script type='application/ld+json'>" + deep + "</script>"
                + "<video src='https://cdn.example.com/valid.mp4'></video>";
        equal("https://cdn.example.com/valid.mp4", GenericExtractor.parse(html, PAGE).getMainMedia().getUrl(), "ignora JSON roto/profundo");
    }

    private static void enforcesCandidateLimit() throws Exception {
        StringBuilder html = new StringBuilder("<script>");
        for (int index = 0; index < 80; index++) {
            html.append("var v").append(index).append("='https://cdn.example.com/video-")
                    .append(index).append(".mp4';");
        }
        html.append("</script>");
        equal(32, GenericExtractor.parse(html.toString(), PAGE).getCandidates().size(), "límite de candidatos");
    }

    private static void retainsLatePrincipalBeyondCandidateLimit() throws Exception {
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < 50; index++) {
            html.append("<aside class='related advertisement'><video src='https://ads.example.com/clip-")
                    .append(index).append(".mp4' width='200' height='100' data-duration='3'></video></aside>");
        }
        html.append("<main><video controls class='main-player' src='https://cdn.example.com/late-main.mp4' "
                + "width='1920' height='1080' data-duration='900' data-size='200 MB'></video></main>");
        GenericExtractor.Result result = GenericExtractor.parse(html.toString(), PAGE);
        equal("https://cdn.example.com/late-main.mp4", result.getMainMedia().getUrl(), "principal tardío conservado");
        equal(1, result.getCandidates().size(), "auxiliares tempranos filtrados");
    }

    private static void rejectsNonHttpsPage() throws Exception {
        boolean rejected = false;
        try {
            GenericExtractor.parse("<video src='https://cdn.example.com/a.mp4'>", URI.create("http://site.example.com/"));
        } catch (IOException expected) {
            rejected = true;
        }
        check(rejected, "rechaza página HTTP");
    }

    private static void expectFailure(String html, String label) throws Exception {
        boolean failed = false;
        try {
            GenericExtractor.parse(html, PAGE);
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, label);
    }

    private static GenericExtractor.Media find(GenericExtractor.Result result, String url) {
        for (GenericExtractor.Media media : result.getCandidates()) {
            if (url.equals(media.getUrl())) return media;
        }
        return null;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": esperado=" + expected + ", actual=" + actual);
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
