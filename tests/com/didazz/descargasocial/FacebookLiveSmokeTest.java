// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

/** Optional live smoke test. It prints metadata only and never downloads the media files. */
public final class FacebookLiveSmokeTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Indica al menos un enlace público de Facebook.");
        }
        for (String url : args) {
            ExtractionResult result = FacebookExtractor.extract(url, false);
            System.out.println(
                    result.getPlatform().getDisplayName()
                            + " id=" + result.getContentId()
                            + " elementos=" + result.getItems().size());
            for (int index = 0; index < result.getItems().size(); index++) {
                MediaItem item = result.getItems().get(index);
                System.out.println(
                        "  " + (index + 1)
                                + ": " + (item.isVideo() ? "video" : "imagen")
                                + " " + item.getWidth() + "x" + item.getHeight());
            }
        }
    }
}
