// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.IOException;

/** Dependency-free regression tests for Facebook's public SSR response parser. */
public final class FacebookExtractorParserTest {
    public static void main(String[] args) throws Exception {
        parsesTargetReelInsteadOfRecommendation();
        ignoresUnrelatedInitialNodeWhenUrlIdentifiesTarget();
        neverSelectsAnUnidentifiedRecommendedStory();
        ignoresMediaIdsEmbeddedInUnrelatedQueryParameters();
        parsesDeliveryFieldsNestedUnderTheTargetVideo();
        prefersNestedDeliveryWithoutDuplicatingDirectPlayback();
        trustsTheResolvedTargetBeforeTheSharedUrl();
        consolidatesVideoDeliveryInsidePostAttachments();
        selectsQualityAcrossRepeatedVideoRepresentations();
        selectsQualityAcrossRepeatedPhotoRepresentations();
        identifiesPermalinkAndPhotoPathTargets();
        ignoresTypeWordsInsideUnrelatedQueryParameters();
        ignoresRecommendationsNestedInsideTargetAttachments();
        correlatesSiblingDeliveryWithTheMatchedTarget();
        rejectsOpenGraphFallbackWhenConcreteIdsConflict();
        preservesQueryDistinctIdlessAttachments();
        validatesExpectedMediaSignatures();
        rejectsLoginRedirectEvenWhenItContainsAnOgImage();
        classifiesRedirectFetchMetadataCorrectly();
        sanitizesRedirectReferrers();
        selectsDataSaverVideo();
        parsesOrderedPhotoAttachmentsWithoutDuplicates();
        removesOnlyTheFacebookPresentationSizeHint();
        keepsUnknownPhotoSignaturesUntouched();
        parsesVideoInsideARegularPost();
        fallsBackToOpenGraphForAPhoto();
        rejectsStoriesExplicitly();
        System.out.println("FacebookExtractorParserTest: 27 PASS");
    }

    private static void parsesTargetReelInsteadOfRecommendation() throws Exception {
        String html = page(
                "https://www.facebook.com/Jorge/videos/example/903624092745975/",
                "https://scontent.xx.fbcdn.net/target.jpg?x=1&amp;y=2",
                "{\"payload\":["
                        + "{\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/wrong.mp4\","
                        + "\"id\":\"111111111\"},"
                        + "{\"browser_native_sd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/target-sd.mp4?x=1&amp;y=2\","
                        + "\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/target-hd.mp4?x=1&amp;y=2\","
                        + "\"id\":\"903624092745975\"}]}" );
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/share/r/1ERNpz4hxB/",
                "https://www.facebook.com/reel/903624092745975/",
                html,
                false);
        equal(SocialPlatform.FACEBOOK, result.getPlatform(), "plataforma Reel");
        equal("903624092745975", result.getContentId(), "id Reel");
        equal(1, result.getItems().size(), "número de vídeos target");
        equal(
                "https://video.xx.fbcdn.net/target-hd.mp4?x=1&y=2",
                result.getItems().get(0).getUrl(),
                "calidad HD target");
        equal(
                "https://scontent.xx.fbcdn.net/target.jpg?x=1&y=2",
                result.getItems().get(0).getThumbnailUrl(),
                "miniatura Reel");
    }

    private static void selectsDataSaverVideo() throws Exception {
        String html = page(
                "https://www.facebook.com/reel/903624092745975/",
                "https://scontent.xx.fbcdn.net/target.jpg",
                "{\"browser_native_sd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/target-sd.mp4\","
                        + "\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/target-hd.mp4\","
                        + "\"id\":\"903624092745975\"}");
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/reel/903624092745975/",
                "https://www.facebook.com/reel/903624092745975/",
                html,
                true);
        equal(
                "https://video.xx.fbcdn.net/target-sd.mp4",
                result.getItems().get(0).getUrl(),
                "calidad ahorro");
    }

    private static void ignoresUnrelatedInitialNodeWhenUrlIdentifiesTarget() throws Exception {
        String html = page(
                "https://www.facebook.com/reel/903624092745975/",
                "https://scontent.xx.fbcdn.net/target.jpg",
                "{\"initial_node_id\":\"111111111\",\"videos\":["
                        + "{\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/target.mp4\","
                        + "\"id\":\"903624092745975\"},"
                        + "{\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/recommended.mp4\","
                        + "\"id\":\"111111111\"}]}" );
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/share/r/1ERNpz4hxB/",
                "https://www.facebook.com/reel/903624092745975/",
                html,
                false);
        equal(1, result.getItems().size(), "excluye initial_node recomendado");
        equal(
                "https://video.xx.fbcdn.net/target.mp4",
                result.getItems().get(0).getUrl(),
                "mantiene únicamente el objetivo de la URL");
    }

    private static void neverSelectsAnUnidentifiedRecommendedStory() throws Exception {
        String html = page(
                "https://www.facebook.com/share/opaqueToken/",
                "https://scontent.xx.fbcdn.net/target-og.jpg",
                "{\"stories\":[{\"__typename\":\"Story\",\"post_id\":\"111111111\","
                        + "\"attachments\":["
                        + photo("recommended", "wrong.jpg", 640, 480)
                        + "]}]}" );
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/share/opaqueToken/",
                "https://www.facebook.com/share/opaqueToken/",
                html,
                false);
        equal(1, result.getItems().size(), "fallback OG único");
        equal(
                "https://scontent.xx.fbcdn.net/target-og.jpg",
                result.getItems().get(0).getUrl(),
                "no toma una recomendación sin identidad");
    }

    private static void ignoresMediaIdsEmbeddedInUnrelatedQueryParameters() throws Exception {
        String requested = "https://www.facebook.com/watch/?v=903624092745975"
                + "&next=/reel/111111111/";
        String html = page(
                requested,
                "https://scontent.xx.fbcdn.net/target.jpg",
                "{\"videos\":["
                        + "{\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/target.mp4\","
                        + "\"id\":\"903624092745975\"},"
                        + "{\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/wrong.mp4\","
                        + "\"id\":\"111111111\"}]}" );
        ExtractionResult result = FacebookExtractor.parseResponse(
                requested,
                requested,
                html,
                false);
        equal(1, result.getItems().size(), "ignora IDs en query no permitida");
        equal(
                "https://video.xx.fbcdn.net/target.mp4",
                result.getItems().get(0).getUrl(),
                "mantiene el ID del parámetro v");
    }

    private static void parsesDeliveryFieldsNestedUnderTheTargetVideo() throws Exception {
        String html = page(
                "https://www.facebook.com/reel/903624092745975/",
                "https://scontent.xx.fbcdn.net/target.jpg",
                "{\"id\":\"903624092745975\",\"videoDeliveryLegacyFields\":{"
                        + "\"browser_native_hd_url\":"
                        + "\"https:\\/\\/video.xx.fbcdn.net\\/nested-target.mp4\"}}" );
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/reel/903624092745975/",
                "https://www.facebook.com/reel/903624092745975/",
                html,
                false);
        equal(1, result.getItems().size(), "vídeo en contenedor delivery");
        equal(
                "https://video.xx.fbcdn.net/nested-target.mp4",
                result.getItems().get(0).getUrl(),
                "URL delivery anidada");
    }

    private static void prefersNestedDeliveryWithoutDuplicatingDirectPlayback() throws Exception {
        String html = page(
                "https://www.facebook.com/reel/903624092745975/",
                "https://scontent.xx.fbcdn.net/target.jpg",
                "{\"id\":\"903624092745975\","
                        + "\"playable_url\":\"https:\\/\\/video.xx.fbcdn.net\\/direct-sd.mp4\","
                        + "\"videoDeliveryLegacyFields\":{"
                        + "\"browser_native_hd_url\":"
                        + "\"https:\\/\\/video.xx.fbcdn.net\\/nested-hd.mp4\"}}" );
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/reel/903624092745975/",
                "https://www.facebook.com/reel/903624092745975/",
                html,
                false);
        equal(1, result.getItems().size(), "sin duplicar playback directo y delivery");
        equal(
                "https://video.xx.fbcdn.net/nested-hd.mp4",
                result.getItems().get(0).getUrl(),
                "prioriza delivery HD");
        ExtractionResult saver = FacebookExtractor.parseResponse(
                "https://www.facebook.com/reel/903624092745975/",
                "https://www.facebook.com/reel/903624092745975/",
                html,
                true);
        equal(1, saver.getItems().size(), "sin duplicar en ahorro");
        equal(
                "https://video.xx.fbcdn.net/direct-sd.mp4",
                saver.getItems().get(0).getUrl(),
                "prioriza playback SD en ahorro");
    }

    private static void trustsTheResolvedTargetBeforeTheSharedUrl() throws Exception {
        String html = page(
                "https://www.facebook.com/reel/903624092745975/",
                "https://scontent.xx.fbcdn.net/target.jpg",
                "{\"videos\":["
                        + "{\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/target.mp4\","
                        + "\"id\":\"903624092745975\"},"
                        + "{\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/wrong.mp4\","
                        + "\"id\":\"111111111\"}]}" );
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/watch/?v=111111111",
                "https://www.facebook.com/reel/903624092745975/",
                html,
                false);
        equal(1, result.getItems().size(), "el destino resuelto tiene prioridad");
        equal(
                "https://video.xx.fbcdn.net/target.mp4",
                result.getItems().get(0).getUrl(),
                "no mezcla el ID del enlace compartido");
    }

    private static void consolidatesVideoDeliveryInsidePostAttachments() throws Exception {
        String video = "{\"__typename\":\"Video\",\"id\":\"video-child\","
                + "\"playable_url\":\"https:\\/\\/video.xx.fbcdn.net\\/child-sd.mp4\","
                + "\"preferred_thumbnail\":{\"image\":{\"uri\":"
                + "\"https:\\/\\/scontent.xx.fbcdn.net\\/child-specific.jpg\"}},"
                + "\"videoDeliveryLegacyFields\":{\"browser_native_hd_url\":"
                + "\"https:\\/\\/video.xx.fbcdn.net\\/child-hd.mp4\"}}";
        String html = page(
                "https://www.facebook.com/person/posts/22222222222/",
                "https://scontent.xx.fbcdn.net/child-poster.jpg",
                "{\"__typename\":\"Story\",\"post_id\":\"22222222222\","
                        + "\"attachments\":[{\"media\":" + video + "}]}");
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/person/posts/22222222222/",
                "https://www.facebook.com/person/posts/22222222222/",
                html,
                false);
        equal(1, result.getItems().size(), "un solo vídeo adjunto");
        equal(
                "https://video.xx.fbcdn.net/child-hd.mp4",
                result.getItems().get(0).getUrl(),
                "HD consolidado en adjunto");
        equal(
                "https://scontent.xx.fbcdn.net/child-specific.jpg",
                result.getItems().get(0).getThumbnailUrl(),
                "miniatura específica del adjunto");
    }

    private static void selectsQualityAcrossRepeatedVideoRepresentations() throws Exception {
        String html = page(
                "https://www.facebook.com/reel/903624092745975/",
                "https://scontent.xx.fbcdn.net/target.jpg",
                "{\"videos\":["
                        + "{\"id\":\"903624092745975\",\"browser_native_sd_url\":"
                        + "\"https:\\/\\/video.xx.fbcdn.net\\/repeat-sd.mp4\"},"
                        + "{\"id\":\"903624092745975\",\"browser_native_hd_url\":"
                        + "\"https:\\/\\/video.xx.fbcdn.net\\/repeat-hd.mp4\"}]}" );
        ExtractionResult maximum = FacebookExtractor.parseResponse(
                "https://www.facebook.com/reel/903624092745975/",
                "https://www.facebook.com/reel/903624092745975/",
                html,
                false);
        ExtractionResult saver = FacebookExtractor.parseResponse(
                "https://www.facebook.com/reel/903624092745975/",
                "https://www.facebook.com/reel/903624092745975/",
                html,
                true);
        equal(1, maximum.getItems().size(), "un vídeo repetido");
        equal("https://video.xx.fbcdn.net/repeat-hd.mp4", maximum.getItems().get(0).getUrl(), "HD repetido");
        equal("https://video.xx.fbcdn.net/repeat-sd.mp4", saver.getItems().get(0).getUrl(), "SD repetido");
    }

    private static void selectsQualityAcrossRepeatedPhotoRepresentations() throws Exception {
        String html = page(
                "https://www.facebook.com/person/posts/77777777777/",
                "https://scontent.xx.fbcdn.net/preview.jpg",
                "{\"__typename\":\"Story\",\"post_id\":\"77777777777\","
                        + "\"attachments\":["
                        + photo("photo-repeat", "small.jpg", 450, 600) + ","
                        + photo("photo-repeat", "large.jpg", 1080, 1440)
                        + "]}" );
        ExtractionResult maximum = FacebookExtractor.parseResponse(
                "https://www.facebook.com/person/posts/77777777777/",
                "https://www.facebook.com/person/posts/77777777777/",
                html,
                false);
        ExtractionResult saver = FacebookExtractor.parseResponse(
                "https://www.facebook.com/person/posts/77777777777/",
                "https://www.facebook.com/person/posts/77777777777/",
                html,
                true);
        equal("https://scontent.xx.fbcdn.net/large.jpg", maximum.getItems().get(0).getUrl(), "foto grande");
        equal("https://scontent.xx.fbcdn.net/small.jpg", saver.getItems().get(0).getUrl(), "foto ligera");
    }

    private static void identifiesPermalinkAndPhotoPathTargets() throws Exception {
        String permalinkHtml = page(
                "https://www.facebook.com/groups/example/permalink/888888888/",
                "https://scontent.xx.fbcdn.net/preview.jpg",
                "{\"__typename\":\"Story\",\"post_id\":\"888888888\","
                        + "\"attachments\":["
                        + photo("permalink-photo", "permalink.jpg", 800, 600) + "]}" );
        ExtractionResult permalink = FacebookExtractor.parseResponse(
                "https://www.facebook.com/groups/example/permalink/888888888/",
                "https://www.facebook.com/groups/example/permalink/888888888/",
                permalinkHtml,
                false);
        equal("888888888", permalink.getContentId(), "ID permalink");
        equal("https://scontent.xx.fbcdn.net/permalink.jpg", permalink.getItems().get(0).getUrl(), "foto permalink");

        String photoHtml = page(
                "https://www.facebook.com/photo/999999999/",
                "https://scontent.xx.fbcdn.net/preview.jpg",
                "{\"__typename\":\"Photo\",\"id\":\"999999999\","
                        + "\"photo_image\":{\"uri\":"
                        + "\"https:\\/\\/scontent.xx.fbcdn.net\\/photo-path.jpg\","
                        + "\"width\":900,\"height\":1200}}" );
        ExtractionResult photoResult = FacebookExtractor.parseResponse(
                "https://www.facebook.com/photo/999999999/",
                "https://www.facebook.com/photo/999999999/",
                photoHtml,
                false);
        equal("999999999", photoResult.getContentId(), "ID photo path");
        equal("https://scontent.xx.fbcdn.net/photo-path.jpg", photoResult.getItems().get(0).getUrl(), "foto path");
    }

    private static void ignoresTypeWordsInsideUnrelatedQueryParameters() throws Exception {
        String url = "https://www.facebook.com/person/posts/33333333333/"
                + "?next=/reel/999999999/";
        String html = page(
                url,
                "https://scontent.xx.fbcdn.net/correct-photo.jpg",
                "{\"require\":[]}");
        ExtractionResult result = FacebookExtractor.parseResponse(url, url, html, false);
        equal(1, result.getItems().size(), "query ajena no cambia el tipo");
        equal(false, result.getItems().get(0).isVideo(), "sigue siendo foto");
    }

    private static void ignoresRecommendationsNestedInsideTargetAttachments() throws Exception {
        String url = "https://www.facebook.com/person/posts/33333333333/";
        String html = page(
                url,
                "https://scontent.xx.fbcdn.net/target-og.jpg",
                "{\"id\":\"33333333333\",\"attachments\":{"
                        + "\"media\":" + photo("target-photo", "target.jpg", 1080, 1440) + ","
                        + "\"recommended_story\":"
                        + photo("wrong-photo", "nested-wrong.jpg", 1080, 1440)
                        + "}}" );
        ExtractionResult result = FacebookExtractor.parseResponse(url, url, html, false);
        equal(1, result.getItems().size(), "recomendación anidada excluida");
        equal(
                "https://scontent.xx.fbcdn.net/target.jpg",
                result.getItems().get(0).getUrl(),
                "medio real del adjunto");
    }

    private static void correlatesSiblingDeliveryWithTheMatchedTarget() throws Exception {
        String url = "https://www.facebook.com/reel/903624092745975/";
        String html = page(
                url,
                "https://scontent.xx.fbcdn.net/reel-cover.jpg",
                "{\"wrapper\":{"
                        + "\"story\":{\"id\":\"903624092745975\"},"
                        + "\"videoDeliveryLegacyFields\":{"
                        + "\"browser_native_hd_url\":"
                        + "\"https:\\/\\/video.xx.fbcdn.net\\/sibling-hd.mp4\"}}}" );
        ExtractionResult result = FacebookExtractor.parseResponse(url, url, html, false);
        equal(1, result.getItems().size(), "delivery hermano correlacionado");
        equal(
                "https://video.xx.fbcdn.net/sibling-hd.mp4",
                result.getItems().get(0).getUrl(),
                "vídeo del delivery hermano");
    }

    private static void rejectsOpenGraphFallbackWhenConcreteIdsConflict() throws Exception {
        String html = page(
                "https://www.facebook.com/person/posts/33333333333/",
                "https://scontent.xx.fbcdn.net/canonical-only.jpg",
                "{\"require\":[]}" );
        try {
            FacebookExtractor.parseResponse(
                    "https://www.facebook.com/person/posts/11111111111/",
                    "https://www.facebook.com/person/posts/22222222222/",
                    html,
                    false);
            throw new AssertionError("IDs concretos incompatibles no pueden usar Open Graph.");
        } catch (IOException expected) {
            // El rechazo evita asociar el medio canónico a otro identificador resuelto.
        }
    }

    private static void preservesQueryDistinctIdlessAttachments() throws Exception {
        String url = "https://www.facebook.com/person/posts/33333333333/";
        String html = page(
                url,
                "https://scontent.xx.fbcdn.net/cover.jpg",
                "{\"id\":\"33333333333\",\"attachments\":["
                        + "{\"photo_image\":{\"uri\":"
                        + "\"https:\\/\\/scontent.xx.fbcdn.net\\/shared.jpg?asset=A\"}},"
                        + "{\"photo_image\":{\"uri\":"
                        + "\"https:\\/\\/scontent.xx.fbcdn.net\\/shared.jpg?asset=B\"}}]}" );
        ExtractionResult result = FacebookExtractor.parseResponse(url, url, html, false);
        equal(2, result.getItems().size(), "adjuntos sin ID pero con asset distinto");
    }

    private static void validatesExpectedMediaSignatures() {
        byte[] jpeg = new byte[32];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        jpeg[3] = (byte) 0xE0;
        byte[] mp4 = new byte[32];
        byte[] validHeader = new byte[]{
                0, 0, 0, 32, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                0, 0, 2, 0, 'i', 's', 'o', 'm', 'i', 's', 'o', '2',
                'a', 'v', 'c', '1', 'm', 'p', '4', '1'};
        System.arraycopy(validHeader, 0, mp4, 0, validHeader.length);
        byte[] avif = mp4.clone();
        avif[8] = 'a';
        avif[9] = 'v';
        avif[10] = 'i';
        avif[11] = 'f';
        for (int offset = 16; offset < avif.length; offset++) {
            avif[offset] = 0;
        }
        byte[] html = "<html>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        equal(true, FacebookExtractor.hasExpectedMediaSignature(jpeg, jpeg.length, false, 1024), "JPEG");
        equal(true, FacebookExtractor.hasExpectedMediaSignature(mp4, mp4.length, true, 4096), "MP4");
        equal(false, FacebookExtractor.hasExpectedMediaSignature(avif, avif.length, true, 4096), "AVIF no es vídeo");
        equal(false, FacebookExtractor.hasExpectedMediaSignature(mp4, 12, true, 12), "MP4 truncado");
        equal(false, FacebookExtractor.hasExpectedMediaSignature(jpeg, 8, false, 8), "JPEG truncado");
        equal(false, FacebookExtractor.hasExpectedMediaSignature(html, html.length, false, html.length), "HTML no es foto");
    }

    private static void rejectsLoginRedirectEvenWhenItContainsAnOgImage() throws Exception {
        String html = page(
                "https://www.facebook.com/person/posts/33333333333/",
                "https://scontent.xx.fbcdn.net/generic-login-image.jpg",
                "{\"require\":[]}");
        try {
            FacebookExtractor.parseResponse(
                    "https://www.facebook.com/person/posts/33333333333/",
                    "https://www.facebook.com/login.php?next=%2Fperson%2Fposts%2F33333333333%2F",
                    html,
                    false);
            throw new AssertionError("Una redirección de acceso no puede usar la imagen OG.");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("iniciar sesión")) {
                throw expected;
            }
        }
    }

    private static void classifiesRedirectFetchMetadataCorrectly() {
        equal(
                "none",
                FacebookExtractor.fetchSiteFor(null, "https://www.facebook.com/share/example/"),
                "navegación inicial");
        equal(
                "same-origin",
                FacebookExtractor.fetchSiteFor(
                        "https://www.facebook.com/share/example/",
                        "https://www.facebook.com/reel/123456/"),
                "redirección mismo origen");
        equal(
                "same-site",
                FacebookExtractor.fetchSiteFor(
                        "https://m.facebook.com/watch/?v=123456",
                        "https://www.facebook.com/reel/123456/"),
                "redirección mismo sitio");
        equal(
                "cross-site",
                FacebookExtractor.fetchSiteFor(
                        "https://fb.watch/abcdef/",
                        "https://www.facebook.com/watch/?v=123456"),
                "redirección entre sitios");
    }

    private static void sanitizesRedirectReferrers() {
        equal(
                "https://www.facebook.com/share/example/?token=same-origin",
                FacebookExtractor.safeReferer(
                        "https://usuario:secreto@www.facebook.com/share/example/"
                                + "?token=same-origin#fragmento",
                        "https://www.facebook.com/reel/903624092745975/"),
                "sin credenciales ni fragmento en mismo origen");
        equal(
                "https://m.facebook.com/",
                FacebookExtractor.safeReferer(
                        "https://m.facebook.com/share/example/?token=no-filtrar",
                        "https://www.facebook.com/reel/903624092745975/"),
                "solo origen entre subdominios");
        equal(
                null,
                FacebookExtractor.safeReferer(
                        "https://www.facebook.com/share/example/?token=no-filtrar",
                        "https://video.xx.fbcdn.net/media.mp4"),
                "sin referrer entre sitios");
    }

    private static void parsesOrderedPhotoAttachmentsWithoutDuplicates() throws Exception {
        String html = page(
                "https://www.facebook.com/person/posts/10164142248141664/",
                "https://scontent.xx.fbcdn.net/preview.jpg",
                "{\"data\":{\"node_v2\":{\"__typename\":\"Story\","
                        + "\"id\":\"opaque-story-id\",\"post_id\":\"10164142248141664\","
                        + "\"attachments\":["
                        + photo("photo-a", "a.jpg", 1080, 1656) + ","
                        + photo("photo-b", "b.jpg", 2048, 1365) + ","
                        + photo("photo-a", "a.jpg", 1080, 1656)
                        + "]}}}");
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/share/1QE9NF6ZNd/",
                "https://www.facebook.com/599981663/posts/10164142248141664/",
                html,
                false);
        equal("10164142248141664", result.getContentId(), "id publicación");
        equal(2, result.getItems().size(), "fotos únicas");
        equal("https://scontent.xx.fbcdn.net/a.jpg", result.getItems().get(0).getUrl(), "orden 1");
        equal("https://scontent.xx.fbcdn.net/b.jpg", result.getItems().get(1).getUrl(), "orden 2");
        equal(1080, result.getItems().get(0).getWidth(), "ancho foto");
        equal(1656, result.getItems().get(0).getHeight(), "alto foto");
    }

    private static void parsesVideoInsideARegularPost() throws Exception {
        String video = "{\"__typename\":\"Video\",\"id\":\"video-child\","
                + "\"videoDeliveryLegacyFields\":{"
                + "\"browser_native_sd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/child-sd.mp4\","
                + "\"browser_native_hd_url\":\"https:\\/\\/video.xx.fbcdn.net\\/child-hd.mp4\","
                + "\"id\":\"video-child\"}}";
        String html = page(
                "https://www.facebook.com/person/posts/22222222222/",
                "https://scontent.xx.fbcdn.net/child-poster.jpg",
                "{\"__typename\":\"Story\",\"post_id\":\"22222222222\","
                        + "\"attachments\":[{\"media\":" + video + "}]}");
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/person/posts/22222222222/",
                "https://www.facebook.com/person/posts/22222222222/",
                html,
                false);
        equal(1, result.getItems().size(), "vídeo adjunto");
        equal(true, result.getItems().get(0).isVideo(), "tipo vídeo");
        equal("https://video.xx.fbcdn.net/child-hd.mp4", result.getItems().get(0).getUrl(), "vídeo HD");
    }

    private static void removesOnlyTheFacebookPresentationSizeHint() throws Exception {
        String rawPhoto = "{\"media\":{\"__typename\":\"Photo\",\"id\":\"photo-quality\","
                + "\"viewer_image\":{\"width\":1080,\"height\":1656},"
                + "\"photo_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/quality.jpg?"
                + "stp=dst-jpg&cstp=mx1080x1656&ctp=p526x296&oh=signed\","
                + "\"width\":526,\"height\":807}}}";
        String html = page(
                "https://www.facebook.com/person/posts/55555555555/",
                "https://scontent.xx.fbcdn.net/preview.jpg",
                "{\"__typename\":\"Story\",\"post_id\":\"55555555555\","
                        + "\"attachments\":[" + rawPhoto + "]}");
        ExtractionResult maximum = FacebookExtractor.parseResponse(
                "https://www.facebook.com/person/posts/55555555555/",
                "https://www.facebook.com/person/posts/55555555555/",
                html,
                false);
        MediaItem best = maximum.getItems().get(0);
        equal(
                "https://scontent.xx.fbcdn.net/quality.jpg?stp=dst-jpg&cstp=mx1080x1656&oh=signed",
                best.getUrl(),
                "URL máxima firmada");
        equal(1080, best.getWidth(), "ancho máximo");
        equal(1656, best.getHeight(), "alto máximo");
        equal(
                "https://scontent.xx.fbcdn.net/quality.jpg?stp=dst-jpg&cstp=mx1080x1656&ctp=p526x296&oh=signed",
                best.getThumbnailUrl(),
                "fallback original");

        ExtractionResult saver = FacebookExtractor.parseResponse(
                "https://www.facebook.com/person/posts/55555555555/",
                "https://www.facebook.com/person/posts/55555555555/",
                html,
                true);
        equal(best.getThumbnailUrl(), saver.getItems().get(0).getUrl(), "URL ahorro");
        equal(526, saver.getItems().get(0).getWidth(), "ancho ahorro");
        equal(807, saver.getItems().get(0).getHeight(), "alto ahorro");
    }

    private static void keepsUnknownPhotoSignaturesUntouched() throws Exception {
        String original = "https://scontent.xx.fbcdn.net/quality.jpg?ctp=p526x296&oh=signed";
        String html = page(
                "https://www.facebook.com/person/posts/66666666666/",
                "https://scontent.xx.fbcdn.net/preview.jpg",
                "{\"__typename\":\"Story\",\"post_id\":\"66666666666\","
                        + "\"attachments\":[{\"media\":{\"__typename\":\"Photo\","
                        + "\"id\":\"photo-unknown-signature\",\"photo_image\":{\"uri\":\""
                        + original + "\",\"width\":526,\"height\":807}}}]}" );
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/person/posts/66666666666/",
                "https://www.facebook.com/person/posts/66666666666/",
                html,
                false);
        equal(
                "https://scontent.xx.fbcdn.net/quality.jpg?ctp=p526x296&oh=signed",
                result.getItems().get(0).getUrl(),
                "firma desconocida sin cstp");
    }

    private static void fallsBackToOpenGraphForAPhoto() throws Exception {
        String html = page(
                "https://www.facebook.com/person/posts/33333333333/",
                "https://scontent.xx.fbcdn.net/og-only.jpg?quality=full&amp;x=1",
                "{\"require\":[]}");
        ExtractionResult result = FacebookExtractor.parseResponse(
                "https://www.facebook.com/person/posts/33333333333/",
                "https://www.facebook.com/person/posts/33333333333/",
                html,
                false);
        equal(1, result.getItems().size(), "fallback OG");
        equal(
                "https://scontent.xx.fbcdn.net/og-only.jpg?quality=full&x=1",
                result.getItems().get(0).getUrl(),
                "URL OG decodificada");
    }

    private static void rejectsStoriesExplicitly() throws Exception {
        try {
            FacebookExtractor.parseResponse(
                    "https://www.facebook.com/share/s/example/",
                    "https://www.facebook.com/stories/person/444444444/",
                    "<html><head></head></html>",
                    false);
            throw new AssertionError("Una Historia no puede aceptarse como publicación.");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("Historias")) {
                throw expected;
            }
        }
    }

    private static String page(String canonical, String image, String json) {
        return "<!doctype html><html><head>"
                + "<meta content=\"" + canonical + "\" property=\"og:url\">"
                + "<meta property=\"og:image\" content=\"" + image + "\">"
                + "</head><body><script data-sjs type=\"application/json\">"
                + json
                + "</script></body></html>";
    }

    private static String photo(String id, String file, int width, int height) {
        return "{\"media\":{\"__typename\":\"Photo\",\"id\":\"" + id + "\","
                + "\"viewer_image\":{\"width\":" + width + ",\"height\":" + height + "},"
                + "\"photo_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/" + file + "\","
                + "\"width\":" + width + ",\"height\":" + height + "}}}";
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": esperado=" + expected + ", real=" + actual);
        }
    }
}
