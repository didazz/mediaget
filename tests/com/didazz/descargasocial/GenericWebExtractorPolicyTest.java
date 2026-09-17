// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.net.URI;

/** Deterministic tests for the generic page-wait and navigation policy. */
public final class GenericWebExtractorPolicyTest {
    private static final URI PAGE = URI.create("https://video.example.com/watch/42");
    private static int passed;

    private GenericWebExtractorPolicyTest() {
    }

    public static void main(String[] arguments) throws Exception {
        parsesDeclaredRefreshes();
        ignoresRefreshInsideInertMarkup();
        waitsPastAnUnprovenClipButNotPastAProvenMainVideo();
        letsMeasuredWeightOutrankAStaticTeaserHint();
        expandsBoundedJsonMediaDefinitions();
        enforcesWaitBudget();
        enforcesSameOriginPolicy();
        System.out.println("GenericWebExtractorPolicyTest: " + passed + " PASS");
    }

    private static void parsesDeclaredRefreshes() {
        GenericWebExtractor.RefreshInstruction header =
                GenericWebExtractor.parseRefresh("5; url='/ready?a=1&amp;b=2'");
        equal(5, header.delaySeconds, "espera del encabezado");
        equal("/ready?a=1&b=2", header.target, "destino HTML decodificado");

        String html = "<meta HTTP-EQUIV='refresh' content='10; URL=/main'>";
        GenericWebExtractor.PageDocument page =
                new GenericWebExtractor.PageDocument(PAGE, html, false, null);
        GenericWebExtractor.RefreshInstruction meta = GenericWebExtractor.findRefresh(page);
        equal(10, meta.delaySeconds, "espera meta");
        equal("/main", meta.target, "destino meta");

        GenericWebExtractor.PageDocument headerFirst =
                new GenericWebExtractor.PageDocument(PAGE, html, false, "3;url=/header");
        equal("/header", GenericWebExtractor.findRefresh(headerFirst).target,
                "el encabezado precede al HTML");
        equal(null, GenericWebExtractor.parseRefresh("javascript:location='/bad'"),
                "no confunde JavaScript con Refresh");
    }

    private static void ignoresRefreshInsideInertMarkup() {
        String html = "<!-- <meta http-equiv='refresh' content='1;url=/comment'> -->"
                + "<script>var tag=\"<meta http-equiv='refresh' content='1;url=/script'>\";</script>"
                + "<template><meta http-equiv='refresh' content='1;url=/template'></template>"
                + "<meta http-equiv='refresh' content='6;url=/real'>";
        GenericWebExtractor.PageDocument page =
                new GenericWebExtractor.PageDocument(PAGE, html, false, null);
        GenericWebExtractor.RefreshInstruction result = GenericWebExtractor.findRefresh(page);
        equal(6, result.delaySeconds, "ignora contadores inertes");
        equal("/real", result.target, "solo obedece la etiqueta real");

        String inertOnly = "<noscript><meta http-equiv='refresh' content='1;url=/no'></noscript>";
        equal(null, GenericWebExtractor.findRefresh(
                new GenericWebExtractor.PageDocument(PAGE, inertOnly, false, null)),
                "ignora Refresh dentro de noscript");
    }

    private static void waitsPastAnUnprovenClipButNotPastAProvenMainVideo() throws Exception {
        GenericWebExtractor.RefreshInstruction refresh =
                GenericWebExtractor.parseRefresh("5;url=/ready");
        GenericExtractor.Result unproven = GenericExtractor.parse(
                "<video controls src='https://cdn.example.com/preroll.mp4'></video>", PAGE);
        check(GenericWebExtractor.canFollowRefresh(unproven, refresh, 0, 0),
                "controls sin tamaño ni duración no bloquea la espera");

        GenericExtractor.Result large = GenericExtractor.parse(
                "<video controls width='1280' height='720' "
                        + "src='https://cdn.example.com/main.mp4'></video>", PAGE);
        check(!GenericWebExtractor.canFollowRefresh(large, refresh, 0, 0),
                "una resolución principal evita una espera innecesaria");

        GenericExtractor.Result longVideo = GenericExtractor.parse(
                "<video data-duration='120' src='https://cdn.example.com/main.mp4'></video>", PAGE);
        check(!GenericWebExtractor.canFollowRefresh(longVideo, refresh, 0, 0),
                "una duración principal evita una espera innecesaria");
        check(GenericWebExtractor.canFollowRefresh(null, refresh, 0, 0),
                "espera si todavía no hay vídeo");
    }

    private static void enforcesWaitBudget() {
        GenericWebExtractor.RefreshInstruction five = GenericWebExtractor.parseRefresh("5");
        GenericWebExtractor.RefreshInstruction ten = GenericWebExtractor.parseRefresh("10");
        GenericWebExtractor.RefreshInstruction thirteen = GenericWebExtractor.parseRefresh("13");
        check(GenericWebExtractor.canFollowRefresh(null, five, 0, 0), "admite cinco segundos");
        check(GenericWebExtractor.canFollowRefresh(null, ten, 1, 5), "admite quince acumulados");
        check(!GenericWebExtractor.canFollowRefresh(null, five, 2, 0), "máximo dos esperas");
        check(!GenericWebExtractor.canFollowRefresh(null, thirteen, 0, 0),
                "rechaza una espera individual excesiva");
        check(!GenericWebExtractor.canFollowRefresh(null, ten, 1, 10),
                "rechaza más de quince segundos acumulados");
    }

    private static void letsMeasuredWeightOutrankAStaticTeaserHint() throws Exception {
        String teaserUrl = "https://cdn.example.com/teaser.mp4";
        String mainUrl = "https://media.example.com/feature.mp4";
        String html = "<meta property='og:video' content='" + teaserUrl + "'>"
                + "<script>var file='" + mainUrl + "';</script>";
        GenericExtractor.Result parsed = GenericExtractor.parse(html, PAGE);
        GenericExtractor.Media teaser = find(parsed, teaserUrl);
        GenericExtractor.Media main = find(parsed, mainUrl);
        check(teaser != null && main != null, "conserva ambos candidatos para validarlos");
        long teaserRank = GenericWebExtractor.candidateRank(
                teaser, 0, 0, 0.0d, 1024L * 1024L);
        long mainRank = GenericWebExtractor.candidateRank(
                main, 0, 0, 0.0d, 100L * 1024L * 1024L);
        check(mainRank > teaserRank,
                "el peso medido del vídeo principal supera una pista OpenGraph pequeña");
    }

    private static void expandsBoundedJsonMediaDefinitions() throws Exception {
        URI endpoint = URI.create("https://video.example.com/video/get_media?id=42");
        String json = "[{\"format\":\"mp4\",\"quality\":\"360\","
                + "\"videoUrl\":\"https://cdn.example.com/get?id=low\"},"
                + "{\"format\":\"mp4\",\"quality\":\"720\","
                + "\"videoUrl\":\"https://cdn.example.com/get?id=high\"}]";
        GenericExtractor.Result parsed =
                GenericWebExtractor.parseMediaMetadata(endpoint, json, false);
        equal("https://cdn.example.com/get?id=high", parsed.getMainMedia().getUrl(),
                "expande las calidades del documento JSON");
        equal("720p", parsed.getMainMedia().getQualityLabel(),
                "conserva la calidad del documento JSON");
        boolean rejected = false;
        try {
            GenericWebExtractor.parseMediaMetadata(endpoint, "<html>challenge</html>", false);
        } catch (Exception expected) {
            rejected = true;
        }
        check(rejected, "no interpreta HTML como metadatos de vídeo");
    }

    private static void enforcesSameOriginPolicy() {
        check(GenericWebExtractor.sameOrigin(PAGE, URI.create("https://video.example.com/ready")),
                "misma procedencia");
        check(GenericWebExtractor.sameOrigin(PAGE, URI.create("https://VIDEO.example.com:443/ready")),
                "puerto HTTPS implícito y explícito equivalentes");
        check(!GenericWebExtractor.sameOrigin(PAGE, URI.create("https://ads.example.com/ready")),
                "bloquea otro host");
        check(!GenericWebExtractor.sameOrigin(PAGE, URI.create("http://video.example.com/ready")),
                "bloquea cambio a HTTP");
    }

    private static GenericExtractor.Media find(GenericExtractor.Result result, String url) {
        for (GenericExtractor.Media media : result.getCandidates()) {
            if (url.equals(media.getUrl())) {
                return media;
            }
        }
        return null;
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": esperado=" + expected + ", actual=" + actual);
        }
        passed++;
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
        passed++;
    }
}
