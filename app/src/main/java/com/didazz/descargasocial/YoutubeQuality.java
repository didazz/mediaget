// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.util.List;

/** Selects a complete AVC/AAC MP4. Never treats a video-only track as a playable finished video. */
final class YoutubeQuality {
    static final class Track {
        final String url, codec;
        final int width, height, fps, bitrate, itag;
        final long size;
        final boolean video, audio, original;
        Track(String url, String codec, int width, int height, int fps, int bitrate, int itag,
                long size, boolean video, boolean audio, boolean original) {
            this.url=url; this.codec=codec; this.width=width; this.height=height; this.fps=fps;
            this.bitrate=bitrate; this.itag=itag; this.size=size; this.video=video; this.audio=audio; this.original=original;
        }
        int resolution() { return Math.min(width,height); }
        boolean compatible() {
            return size > 0 && size <= MediaValidator.MAX_VIDEO_BYTES && url != null
                    && (video ? codec != null && codec.startsWith("avc1")
                              : audio && codec != null && codec.startsWith("mp4a"));
        }
    }
    static final class Selection {
        final Track video, audio;
        Selection(Track video, Track audio) { this.video=video; this.audio=audio; }
        long totalBytes() { return video.size + (audio == null ? 0 : audio.size); }
    }
    static Selection select(List<Track> tracks, boolean saver) throws YoutubeException {
        Track audio = null;
        for (Track t : tracks) {
            if (!t.video && t.audio && t.compatible()
                    && (audio == null || t.original && !audio.original
                    || t.original == audio.original && (saver ? t.bitrate < audio.bitrate : t.bitrate > audio.bitrate))) {
                audio = t;
            }
        }
        Selection best = null;
        for (Track t : tracks) {
            if (!t.video || !t.compatible() || t.width <= 0 || t.height <= 0 || !t.audio && audio == null) { continue; }
            Selection choice = new Selection(t,t.audio ? null : audio);
            if (choice.totalBytes() > MediaValidator.MAX_VIDEO_BYTES) { continue; }
            if (best == null || better(choice,best,saver)) { best=choice; }
        }
        if (best == null) { throw new YoutubeException(Messages.ref("error_174")); }
        return best;
    }
    private static boolean better(Selection a, Selection b, boolean saver) {
        int ar=a.video.resolution(), br=b.video.resolution();
        if (saver) {
            // Prefer the highest resolution up to 360p; above that, choose the smaller rendition.
            boolean al=ar<=360, bl=br<=360;
            if (al!=bl) { return al; }
            if (ar!=br) { return al ? ar>br : ar<br; }
            return a.totalBytes()<b.totalBytes();
        }
        if (ar!=br) { return ar>br; }
        if (a.video.fps!=b.video.fps) { return a.video.fps>b.video.fps; }
        if (a.video.bitrate!=b.video.bitrate) { return a.video.bitrate>b.video.bitrate; }
        return a.audio==null && b.audio!=null;
    }
}
