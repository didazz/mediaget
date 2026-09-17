// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.io.IOException;
/** A user-facing, sanitized YouTube error; never includes media URLs or remote response bodies. */
final class YoutubeException extends IOException {
    private static final long serialVersionUID = 1L;
    YoutubeException(String message) { super(message); }
    YoutubeException(String message, Throwable cause) { super(message, cause); }
}
