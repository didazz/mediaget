package com.didazz.descargasocial;
import java.util.*;
import java.util.regex.*;
import java.net.*;
public final class LocalizationTest {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static final Pattern FORMAT=Pattern.compile("%(\\d+)\\$([dsf])");
    static List<String> formats(String text){List<String> out=new ArrayList<>();Matcher m=FORMAT.matcher(text);while(m.find())out.add(m.group());Collections.sort(out);return out;}
    public static void main(String[] ignored)throws Exception {
        Map<String,String> es=TestResources.load("es"),en=TestResources.load("en");
        check(es.keySet().equals(en.keySet()),"Complete resource parity");
        for(String key:es.keySet()) {
            check(formats(es.get(key)).equals(formats(en.get(key))),"Format parity: "+key);
            Matcher m=FORMAT.matcher(en.get(key));Map<Integer,Character> types=new TreeMap<>();
            while(m.find())types.put(Integer.parseInt(m.group(1))-1,m.group(2).charAt(0));
            Object[] args=new Object[types.size()];
            for(Map.Entry<Integer,Character> e:types.entrySet())args[e.getKey()]=e.getValue()=='d'?Integer.valueOf(7):"YouTube";
            String token=Messages.ref(key,args);
            for(String locale:new String[]{"es","en"}) {
                String text=TestResources.render(locale,token);
                check(!Messages.contains(text)&&!text.trim().isEmpty(),"Resolvable resource: "+locale+" "+key);
            }
        }
        DownloadQueue<String> queue=new DownloadQueue<>(2,20,50);
        queue.add("one",Messages.ref("request_number",1),"payload1");queue.add("two",Messages.ref("request_number",2),"payload2");queue.add("three",Messages.ref("request_number",3),"payload3");
        queue.takeNext();queue.takeNext();
        queue.progress("one",Messages.ref("progress_known",Messages.ref("phase_video"),"1.0","2.0"),1048576,2097152);
        List<DownloadQueue.Snapshot> before=queue.snapshots();long revision=queue.revision();
        for(int pass=0;pass<10;pass++)for(String locale:new String[]{"es","en"}) {
            for(DownloadQueue.Snapshot state:before) {
                String text=TestResources.render(locale,state.label+" · "+state.message);
                check(!Messages.contains(text),"No token visible in task state");
                check(text.contains(locale.equals("es")?"Petición":"Request"),"Task label follows display locale");
            }
            check(queue.activeCount()==3 && queue.runningCount()==2 && queue.revision()==revision,"Display locale does not mutate queue or concurrency");
            check(queue.snapshots().get(0).bytes==1048576,"Progress retained across display locales");
        }
        check(TestResources.render("en",Messages.ref("request_added_named",Messages.ref("request_number",8))).startsWith("Request 8 added"),"Nested format survives persistence");
        String encoded=Messages.ref("error_mux",Messages.ref("error_308"));
        check(TestResources.render("es",encoded).contains("pista de sonido"),"Detailed native error in Spanish");
        check(TestResources.render("en",encoded).contains("audio track"),"Detailed native error in English");
        check(!DownloadPolicy.retryable(new YoutubeException(Messages.ref("error_310"),new SocketException())),"DRM retry denial retained after resource migration");
        check(DownloadPolicy.retryable(new YoutubeException(Messages.ref("error_mux",Messages.ref("error_308")),new SocketException())),"Transient retry semantics retained");
        check(!Messages.contains(TestResources.render("en","\u001ebad|oops\u001f")),"Malformed saved token fails safely");
        System.out.println("LocalizationTest: "+checks+" host XML/message checks (not Android UI execution)");
    }
}
