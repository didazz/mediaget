// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.*;
import java.nio.ByteBuffer;

/** Joins AVC video and AAC audio using Android, without transcoding or an external service. */
final class YoutubeDownload {
    static void downloadToStream(MediaItem item,OutputStream output,File directory) throws Exception {
        DownloadControl control=DownloadControl.current();
        if(control==null) { throw new YoutubeException(Messages.ref("error_304")); }
        control.startPhase(Messages.ref("phase_youtube"),-1);
        YoutubeExtractor.Plan plan=YoutubeExtractor.resolve(item.getUrl(),item.isYoutubeDataSaver(),false);
        YoutubeQuality.Selection selection=plan.selection;
        if(selection.audio==null) {
            control.startPhase(Messages.ref("phase_combined"),selection.video.size);
            YoutubeTransfer.download(selection.video,output);
            return;
        }
        if(directory.getUsableSpace()<selection.totalBytes()*2+8*1024*1024) {
            throw new YoutubeException(Messages.ref("error_305"));
        }
        File video=new File(directory,"video.part"), audio=new File(directory,"audio.part"), muxed=new File(directory,"muxed.mp4");
        control.startPhase(Messages.ref("phase_video"),selection.video.size);
        try(OutputStream out=control.wrap(new BufferedOutputStream(new FileOutputStream(video)))) {
            YoutubeTransfer.download(selection.video,out);
        }
        control.startPhase(Messages.ref("phase_audio"),selection.audio.size);
        try(OutputStream out=control.wrap(new BufferedOutputStream(new FileOutputStream(audio)))) {
            YoutubeTransfer.download(selection.audio,out);
        }
        control.startPhase(Messages.ref("phase_mux"),-1);
        mux(video,audio,muxed,control);
        if(muxed.length()<=0 || muxed.length()>MediaValidator.MAX_VIDEO_BYTES) {
            throw new YoutubeException(Messages.ref("error_306"));
        }
        // Free the source tracks before making the final public copy.
        if(!video.delete() || !audio.delete()) { throw new IOException(Messages.ref("error_95")); }
        control.startPhase(Messages.ref("phase_save"),muxed.length());
        try(InputStream in=new FileInputStream(muxed)) {
            byte[] buffer=new byte[65536]; int n;
            while((n=in.read(buffer))!=-1) { control.check(); output.write(buffer,0,n); }
        }
    }

    private static void mux(File video,File audio,File output,DownloadControl control) throws Exception {
        MediaExtractor v=new MediaExtractor(), a=new MediaExtractor();
        MediaMuxer muxer=null;
        boolean started=false;
        Exception failure=null;
        String step=Messages.ref("error_307");
        try {
            control.check();
            openLocal(v,video);
            step=Messages.ref("error_308");
            openLocal(a,audio);
            step=Messages.ref("error_309");
            int videoIndex=findTrack(v,"video/avc"), audioIndex=findTrack(a,"audio/mp4a-latm");
            if(v.getPsshInfo()!=null && !v.getPsshInfo().isEmpty()
                    || a.getPsshInfo()!=null && !a.getPsshInfo().isEmpty()) {
                throw new YoutubeException(Messages.ref("error_310"));
            }
            v.selectTrack(videoIndex); a.selectTrack(audioIndex);
            step=Messages.ref("error_311");
            muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOutput=muxer.addTrack(v.getTrackFormat(videoIndex));
            int audioOutput=muxer.addTrack(a.getTrackFormat(audioIndex));
            muxer.start(); started=true;
            step=Messages.ref("error_312");
            ByteBuffer buffer=ByteBuffer.allocateDirect(8*1024*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            long videoSamples=0,audioSamples=0;
            while(v.getSampleTime()>=0 || a.getSampleTime()>=0) {
                control.check();
                boolean useVideo=a.getSampleTime()<0 || v.getSampleTime()>=0 && v.getSampleTime()<=a.getSampleTime();
                MediaExtractor source=useVideo?v:a;
                buffer.clear();
                int size=source.readSampleData(buffer,0);
                if(size<0) { throw new YoutubeException(Messages.ref("error_313")); }
                if(size>buffer.capacity() || (source.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0) {
                    throw new YoutubeException(Messages.ref("error_314"));
                }
                int flags=(source.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME:0;
                info.set(0,size,source.getSampleTime(),flags);
                muxer.writeSampleData(useVideo?videoOutput:audioOutput,buffer,info);
                if(useVideo) { videoSamples++; } else { audioSamples++; }
                source.advance();
            }
            if(videoSamples==0 || audioSamples==0) { throw new YoutubeException(Messages.ref("error_315")); }
            control.check();
            step=Messages.ref("error_316");
            muxer.stop(); started=false;
        } catch(DownloadControl.Cancelled | YoutubeException specific) {
            failure=specific;
            throw specific;
        } catch(IOException | RuntimeException error) {
            YoutubeException reported=new YoutubeException(Messages.ref("error_mux", step),error);
            failure=reported;
            throw reported;
        } finally {
            // Release every native handle, without replacing the actual error or a cancellation.
            RuntimeException cleanup=null;
            if(muxer!=null) {
                if(started) { try { muxer.stop(); } catch(RuntimeException ignored) { } }
                try { muxer.release(); } catch(RuntimeException error) { cleanup=error; }
            }
            try { v.release(); } catch(RuntimeException error) {
                if(cleanup==null) { cleanup=error; } else { cleanup.addSuppressed(error); }
            }
            try { a.release(); } catch(RuntimeException error) {
                if(cleanup==null) { cleanup=error; } else { cleanup.addSuppressed(error); }
            }
            if(cleanup!=null) {
                if(failure!=null) { failure.addSuppressed(cleanup); }
                else { throw new YoutubeException(Messages.ref("error_319"),cleanup); }
            }
        }
        MediaExtractor result=new MediaExtractor();
        Exception verificationFailure=null;
        try {
            control.check();
            openLocal(result,output);
            findTrack(result,"video/avc"); findTrack(result,"audio/mp4a-latm");
        } catch(DownloadControl.Cancelled | YoutubeException specific) {
            verificationFailure=specific; throw specific;
        } catch(IOException | RuntimeException error) {
            YoutubeException reported=new YoutubeException(Messages.ref("error_320"),error);
            verificationFailure=reported; throw reported;
        } finally {
            try { result.release(); } catch(RuntimeException error) {
                if(verificationFailure!=null) { verificationFailure.addSuppressed(error); }
                else { throw new YoutubeException(Messages.ref("error_321"),error); }
            }
        }
    }

    /** A path may be opened by Android's other process, which cannot read this app's private cache.
     * Open it as the owning app and give MediaExtractor a bounded, seekable descriptor instead.
     * Android duplicates the descriptor; this stream can close as soon as setDataSource returns.
     */
    private static void openLocal(MediaExtractor extractor,File file) throws IOException {
        try(FileInputStream stream=new FileInputStream(file)) {
            long length=stream.getChannel().size();
            if(length<=0) { throw new IOException("La pista temporal está vacía."); }
            extractor.setDataSource(stream.getFD(),0,length);
        }
    }
    private static int findTrack(MediaExtractor extractor,String mime) throws YoutubeException {
        for(int i=0;i<extractor.getTrackCount();i++) {
            MediaFormat format=extractor.getTrackFormat(i);
            if(mime.equals(format.getString(MediaFormat.KEY_MIME))) { return i; }
        }
        throw new YoutubeException(Messages.ref("error_323"));
    }
}
