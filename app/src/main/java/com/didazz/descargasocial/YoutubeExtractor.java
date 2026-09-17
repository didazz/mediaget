// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.util.*;
import org.schabi.newpipe.extractor.*;
import org.schabi.newpipe.extractor.stream.*;
import org.schabi.newpipe.extractor.localization.*;
import org.schabi.newpipe.extractor.services.youtube.*;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;

/** Embedded NewPipe adapter. Its mutable player caches are used by one extraction at a time. */
final class YoutubeExtractor {
    static final class Plan {
        final String id, thumbnail;
        final YoutubeQuality.Selection selection;
        final long created = System.nanoTime();
        Plan(String id,String thumbnail,YoutubeQuality.Selection selection) {
            this.id=id; this.thumbnail=thumbnail; this.selection=selection;
        }
    }
    private static final LinkedHashMap<String,Plan> CACHE = new LinkedHashMap<>();
    private static boolean initialized;
    private YoutubeExtractor() { }
    static ExtractionResult extract(String value,boolean saver) throws Exception {
        Plan p=resolve(value,saver,true);
        MediaItem item=MediaItem.youtube(YoutubeLink.canonical(value),saver,p.thumbnail,
                p.selection.video.width,p.selection.video.height);
        return new ExtractionResult(SocialPlatform.YOUTUBE,YoutubeLink.canonical(value),p.id,
                Collections.singletonList(item));
    }
    static synchronized Plan resolve(String value,boolean saver,boolean fresh) throws Exception {
        String canonical=YoutubeLink.canonical(value), id=YoutubeLink.videoId(canonical);
        String key=id+(saver?":light":":best");
        Plan cached=CACHE.get(key);
        if(!fresh && cached!=null && System.nanoTime()-cached.created<180_000_000_000L) { return cached; }
        if(!initialized) {
            String country=Locale.getDefault().getCountry();
            if(country==null || country.length()!=2) { country="ES"; }
            NewPipe.init(new YoutubeHttp(),new Localization("en",country),new ContentCountry(country));
            YoutubeParsingHelper.setConsentAccepted(false);
            initialized=true;
        }
        if(Thread.currentThread().isInterrupted()) { throw new java.io.InterruptedIOException("Consulta cancelada."); }
        try {
            StreamExtractor extractor=ServiceList.YouTube.getStreamExtractor(canonical);
            extractor.fetchPage();
            if(!id.equals(extractor.getId())) { throw new YoutubeException(Messages.ref("error_333")); }
            if(extractor.getAgeLimit()>0) { throw new YoutubeException(Messages.ref("error_334")); }
            if(extractor.getStreamType()!=StreamType.VIDEO_STREAM) {
                throw new YoutubeException(Messages.ref("error_335"));
            }
            List<YoutubeQuality.Track> tracks=new ArrayList<>();
            List<VideoStream> videos=new ArrayList<>(extractor.getVideoStreams());
            videos.addAll(extractor.getVideoOnlyStreams());
            for(VideoStream video:videos) {
                if(video.getFormat()!=MediaFormat.MPEG_4 || !video.isUrl()
                        || video.getDeliveryMethod()!=DeliveryMethod.PROGRESSIVE_HTTP
                        || video.getItagItem()==null) { continue; }
                tracks.add(new YoutubeQuality.Track(video.getContent(),video.getCodec(),
                        video.getWidth(),video.getHeight(),video.getFps(),video.getBitrate(),video.getItag(),
                        video.getItagItem().getContentLength(),true,!video.isVideoOnly(),false));
            }
            for(AudioStream audio:extractor.getAudioStreams()) {
                if(audio.getFormat()!=MediaFormat.M4A || !audio.isUrl()
                        || audio.getDeliveryMethod()!=DeliveryMethod.PROGRESSIVE_HTTP
                        || audio.getItagItem()==null) { continue; }
                tracks.add(new YoutubeQuality.Track(audio.getContent(),audio.getCodec(),0,0,0,
                        audio.getBitrate(),audio.getItag(),audio.getItagItem().getContentLength(),false,true,
                        audio.getAudioTrackType()==AudioTrackType.ORIGINAL || audio.getAudioTrackType()==null));
            }
            String thumbnail=null;
            for(Image image:extractor.getThumbnails()) {
                if(image.getUrl()!=null) { thumbnail=image.getUrl(); break; }
            }
            Plan plan=new Plan(id,thumbnail,YoutubeQuality.select(tracks,saver));
            CACHE.put(key,plan);
            while(CACHE.size()>12) { CACHE.remove(CACHE.keySet().iterator().next()); }
            return plan;
        } catch(ExtractionException e) {
            String type=e.getClass().getSimpleName();
            if(type.contains("AgeRestricted") || type.contains("Private") || type.contains("Paid")
                    || type.contains("MusicPremium")) {
                throw new YoutubeException(Messages.ref("error_336"),e);
            }
            if(type.contains("SignIn") || type.contains("ReCaptcha") || type.contains("Geographic")) {
                throw new YoutubeException(Messages.ref("error_337"),e);
            }
            throw new YoutubeException(Messages.ref("error_338"),e);
        }
    }
}
