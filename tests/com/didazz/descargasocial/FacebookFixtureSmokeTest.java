// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Parses one previously captured public response without making a network request. */
public final class FacebookFixtureSmokeTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Uso: <enlace solicitado> <enlace final> <respuesta.html>");
        }
        String html = new String(
                Files.readAllBytes(Paths.get(args[2])), StandardCharsets.UTF_8);
        ExtractionResult result = FacebookExtractor.parseResponse(args[0], args[1], html, false);
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
