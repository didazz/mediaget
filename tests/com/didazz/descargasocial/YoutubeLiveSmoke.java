// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.net.URI;
/** Opt-in metadata smoke test using the exact production adapter; never prints temporary media URLs. */
public final class YoutubeLiveSmoke {
    public static void main(String[] args) throws Exception {
        // Honor this workstation's normal HTTPS proxy. This code is not included in Android.
        String proxy=System.getenv("HTTPS_PROXY");
        if(proxy!=null && !proxy.isEmpty()) {
            URI uri=URI.create(proxy);System.setProperty("https.proxyHost",uri.getHost());
            System.setProperty("https.proxyPort",String.valueOf(uri.getPort()));
        }
        String url="https://www.youtube.com/watch?v=aqz-KE-bpKQ";
        for(boolean saver:new boolean[]{false,true}) {
            YoutubeExtractor.Plan plan=YoutubeExtractor.resolve(url,saver,true);
            if(!"aqz-KE-bpKQ".equals(plan.id) || plan.selection.totalBytes()<=0) throw new AssertionError("Wrong public sample");
            System.out.println("YouTube production adapter: "+(saver?"saver":"best")+" "
                    +plan.selection.video.width+"x"+plan.selection.video.height+" "
                    +plan.selection.totalBytes()+" bytes; audio="+(plan.selection.audio==null?"combined":"separate"));
        }
        System.out.println("Metadata verified only; this test does not prove media download or Android muxing.");
    }
}
