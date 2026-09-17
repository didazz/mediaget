// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

/** Platforms supported by the local extraction engine. */
public enum SocialPlatform {
    INSTAGRAM("Instagram", "Instagram"),
    FACEBOOK("Facebook", "Facebook"),
    YOUTUBE("YouTube", "YouTube"),
    GENERIC("la web", "Web");

    private final String displayName;
    private final String filePrefix;

    SocialPlatform(String displayName, String filePrefix) {
        this.displayName = displayName;
        this.filePrefix = filePrefix;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFilePrefix() {
        return filePrefix;
    }
}
