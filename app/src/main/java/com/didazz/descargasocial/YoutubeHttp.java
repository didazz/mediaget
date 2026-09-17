// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.schabi.newpipe.extractor.downloader.*;

/** Bounded HTTPS transport for the embedded engine. No account cookies, proxies or external providers. */
final class YoutubeHttp extends Downloader {
    static boolean allowedHost(String host, boolean media) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (media) { return h.equals("googlevideo.com") || h.endsWith(".googlevideo.com"); }
        return h.equals("youtube.com") || h.endsWith(".youtube.com")
                || h.equals("youtubei.googleapis.com") || h.equals("ytimg.com") || h.endsWith(".ytimg.com")
                || h.equals("youtube-nocookie.com") || h.endsWith(".youtube-nocookie.com");
    }
    static HttpURLConnection open(String value, String method, Map<String,String> headers,
            byte[] body, boolean media) throws IOException {
        String next=value;
        for(int redirects=0;redirects<=5;redirects++) {
            URI uri=PublicWebUrlPolicy.requirePublicHttps(next);
            if(!allowedHost(uri.getHost(),media)) { throw new YoutubeException("YouTube redirigió a un destino no compatible."); }
            HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
            try {
                DownloadControl.track(c);
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(30000);
                c.setRequestMethod(method);
                c.setRequestProperty("User-Agent","Mozilla/5.0");
                c.setRequestProperty("Accept-Encoding","identity");
                for(Map.Entry<String,String> entry:headers.entrySet()) {
                    String key=entry.getKey(), val=entry.getValue();
                    if(key.equalsIgnoreCase("Authorization") || key.equalsIgnoreCase("Proxy-Authorization")) { continue; }
                    if(key.equalsIgnoreCase("Cookie") && !val.equals("SOCS=CAE=")) { continue; }
                    c.setRequestProperty(key,val);
                }
                if(body!=null) {
                    if(body.length>2*1024*1024) { throw new YoutubeException("La petición de YouTube es demasiado grande."); }
                    c.setDoOutput(true); c.setFixedLengthStreamingMode(body.length);
                    try(OutputStream out=c.getOutputStream()) { out.write(body); }
                }
                int status=c.getResponseCode();
                if(status==301 || status==302 || status==303 || status==307 || status==308) {
                    String location=c.getHeaderField("Location");
                    if(location==null) { throw new YoutubeException("YouTube envió una redirección incompleta."); }
                    next=uri.resolve(location).toString();
                    if(status==303 || (status==301 || status==302) && method.equals("POST")) { method="GET"; body=null; }
                    close(c); continue;
                }
                if(status>=400) { throw new IOException("HTTP "+status+" al consultar YouTube."); }
                return c;
            } catch(IOException | RuntimeException failure) { close(c); throw failure; }
        }
        throw new YoutubeException("YouTube envió demasiadas redirecciones.");
    }
    static void close(HttpURLConnection c) { DownloadControl.release(c); c.disconnect(); }
    @Override public Response execute(Request request) throws IOException {
        Map<String,String> headers=new LinkedHashMap<>();
        for(Map.Entry<String,List<String>> entry:request.headers().entrySet()) {
            if(!entry.getValue().isEmpty()) { headers.put(entry.getKey(),String.join(", ",entry.getValue())); }
        }
        HttpURLConnection c=open(request.url(),request.httpMethod(),headers,request.dataToSend(),false);
        try {
            InputStream raw=c.getInputStream();
            if("gzip".equalsIgnoreCase(c.getContentEncoding())) { raw=new GZIPInputStream(raw); }
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=raw) {
                byte[] buffer=new byte[16384]; int n;
                while((n=in.read(buffer))!=-1) {
                    if(Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("Consulta cancelada."); }
                    if(bytes.size()+n>20*1024*1024) { throw new YoutubeException("La respuesta de YouTube es demasiado grande."); }
                    bytes.write(buffer,0,n);
                }
            }
            return new Response(c.getResponseCode(),c.getResponseMessage(),c.getHeaderFields(),
                    new String(bytes.toByteArray(),StandardCharsets.UTF_8),c.getURL().toString());
        } finally { close(c); }
    }
}
