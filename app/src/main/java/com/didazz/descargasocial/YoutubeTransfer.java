// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.*;

/** Ordinary range downloads, with exact offsets and lengths, restartable only by the outer policy. */
final class YoutubeTransfer {
    interface Opener { HttpURLConnection open(String url,Map<String,String> headers) throws IOException; }
    static void download(YoutubeQuality.Track track,OutputStream out) throws IOException {
        download(track,out,(url,headers)->YoutubeHttp.open(url,"GET",headers,null,true));
    }
    static void download(YoutubeQuality.Track track,OutputStream out,Opener opener) throws IOException {
        if(track.size<=0 || track.size>MediaValidator.MAX_VIDEO_BYTES) {
            throw new YoutubeException("YouTube no indicó un tamaño de archivo compatible.");
        }
        long offset=0;
        while(offset<track.size) {
            if(Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("Descarga cancelada."); }
            long end=Math.min(track.size-1,offset+8*1024*1024-1);
            Map<String,String> headers=new LinkedHashMap<>();
            headers.put("Range","bytes="+offset+"-"+end);
            HttpURLConnection c=opener.open(track.url,headers);
            try {
                long wanted=YoutubeRange.expectedBytes(c.getResponseCode(),c.getHeaderField("Content-Range"),offset,end,track.size);
                if(c.getContentEncoding()!=null && !"identity".equalsIgnoreCase(c.getContentEncoding())) {
                    throw new IOException("YouTube comprimió inesperadamente el archivo.");
                }
                long declared=c.getContentLengthLong();
                if(declared>=0 && declared!=wanted) { throw new IOException("YouTube declaró un tamaño incoherente."); }
                try(InputStream in=c.getInputStream()) {
                    byte[] buffer=new byte[65536]; long received=0; int n;
                    if(offset==0) {
                        byte[] prefix=new byte[(int)Math.min(4096,wanted)]; int used=0;
                        while(used<prefix.length) {
                            n=in.read(prefix,used,prefix.length-used);
                            if(n<0) { throw new EOFException("Archivo de YouTube incompleto."); }
                            used+=n;
                        }
                        MediaValidator.Format format=MediaValidator.detect(prefix,c.getContentType());
                        if(!"mp4".equals(format.getExtension())) { throw new YoutubeException("YouTube no entregó un archivo MP4 válido."); }
                        out.write(prefix); received+=prefix.length;
                    }
                    while((n=in.read(buffer))!=-1) {
                        if(Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("Descarga cancelada."); }
                        if(received+n>wanted) { throw new IOException("YouTube envió más bytes de los solicitados."); }
                        out.write(buffer,0,n); received+=n;
                    }
                    if(received!=wanted) { throw new EOFException("Archivo de YouTube incompleto."); }
                    offset+=received;
                }
            } finally { YoutubeHttp.close(c); }
        }
    }
}
