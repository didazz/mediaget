package com.didazz.descargasocial.tests;
import android.app.*;
import android.content.*;
import android.content.res.*;
import android.os.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Instrumented resource/lifecycle tests. No download or network success is inferred. */
public final class NativeChecks extends Instrumentation {
    int checks; Context target; ClassLoader loader;
    void check(boolean condition,String description){if(!condition)throw new AssertionError(description);checks++;}
    Class<?> cls(String name)throws Exception{return loader.loadClass("com.didazz.descargasocial."+name);}
    Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    void field(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    String resource(Context c,String name){return c.getString(c.getResources().getIdentifier(name,"string",target.getPackageName()));}
    void main(Runnable action){runOnMainSync(action);waitForIdleSync();}
    void nativeMux() throws Exception {
        java.io.File dir=new java.io.File(target.getCacheDir(), "native-validation-"+System.nanoTime());
        check(dir.mkdir(),"Create isolated native test folder");
        java.io.File video=new java.io.File(dir,"video.mp4"),audio=new java.io.File(dir,"audio.m4a"),output=new java.io.File(dir,"muxed.mp4");
        try {
            for(java.io.File file:new java.io.File[]{video,audio}) {
                try(java.io.InputStream in=getContext().getAssets().open(file.getName());java.io.FileOutputStream out=new java.io.FileOutputStream(file)) {
                    byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                }
            }
            for(java.io.File file:new java.io.File[]{video,audio}) {
                android.media.MediaExtractor probe=new android.media.MediaExtractor();
                try(java.io.FileInputStream input=new java.io.FileInputStream(file)) {
                    probe.setDataSource(input.getFD(),0,file.length());probe.selectTrack(0);
                    Bundle sample=new Bundle();sample.putString("stream", "Fixture "+file.getName()+" first Android PTS="+probe.getSampleTime()+"\n");sendStatus(0,sample);
                    check(probe.getSampleTime()>=0,"Supported fixture has nonnegative timestamps");
                } finally {probe.release();}
            }
            Class<?> progress=cls("DownloadControl$Progress");
            Object listener=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{progress},(proxy,method,args)->null);
            Constructor<?> constructor=cls("DownloadControl").getDeclaredConstructor(progress);constructor.setAccessible(true);
            Object control=constructor.newInstance(listener);
            Method mux=cls("YoutubeDownload").getDeclaredMethod("mux",java.io.File.class,java.io.File.class,java.io.File.class,cls("DownloadControl"));mux.setAccessible(true);
            mux.invoke(null,video,audio,output,control);
            check(output.length()>0,"Production native mux wrote a file");
            android.media.MediaExtractor extractor=new android.media.MediaExtractor();
            try(java.io.FileInputStream input=new java.io.FileInputStream(output)) {
                extractor.setDataSource(input.getFD(),0,output.length());
                check(extractor.getTrackCount()==2,"Real Android MP4 contains both tracks");
                java.util.Set<String> mime=new java.util.HashSet<>();
                for(int i=0;i<extractor.getTrackCount();i++)mime.add(extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME));
                check(mime.contains("video/avc")&&mime.contains("audio/mp4a-latm"),"AVC and AAC preserved by native mux");
            } finally {extractor.release();}
        } finally { video.delete();audio.delete();output.delete();dir.delete(); }
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            target=getTargetContext();loader=target.getClassLoader();
            Field keysField=cls("ResourceIds").getDeclaredField("KEYS");keysField.setAccessible(true);String[] keys=(String[])keysField.get(null);
            for(String language:new String[]{"en","es","fr","de","es-MX","en-GB"}){
                Configuration c=new Configuration(target.getResources().getConfiguration());c.setLocales(LocaleList.forLanguageTags(language));
                Context localized=target.createConfigurationContext(c);
                check(resource(localized,"app_name").equals("MediaGet"),"Localized brand "+language);
                check(resource(localized,"new_tab").equals(language.startsWith("es")?"Nuevo":"New"),"Android resource selection "+language);
                for(String key:keys)check(!resource(localized,key).trim().isEmpty(),"Resource exists "+language+" "+key);
            }
            Method ref=cls("Messages").getDeclaredMethod("ref",String.class,Object[].class);ref.setAccessible(true);
            Method translated=cls("TextResources").getDeclaredMethod("render",Context.class,String.class);translated.setAccessible(true);
            Configuration englishConfiguration=new Configuration(target.getResources().getConfiguration());englishConfiguration.setLocales(LocaleList.forLanguageTags("en"));
            Context english=target.createConfigurationContext(englishConfiguration);
            String mixed="Petición 9 · la web · fixture_id\n\n"+ref.invoke(null,"queue_interrupted",new Object[0]);
            String mixedEnglish=(String)translated.invoke(null,english,mixed);
            check(mixedEnglish.startsWith("Request 9 · the website"),"Mixed old/new history label localized");
            check(mixedEnglish.contains("Android interrupted this request"),"Mixed old/new history message localized");
            Bundle resourceStatus=new Bundle();resourceStatus.putString("stream", "Native Android resource checks passed: "+checks+"\n");sendStatus(0,resourceStatus);
            nativeMux();
            Bundle muxStatus=new Bundle();muxStatus.putString("stream", "Production mux passed on real Android APIs with generated AVC/AAC fixtures.\n");sendStatus(0,muxStatus);
            org.json.JSONArray history=new org.json.JSONArray();
            history.put(new org.json.JSONObject().put("id","legacy-1").put("label","Petición 1 · YouTube · fixture_id").put("phase","COMPLETE")
                    .put("message","Archivo guardado en Descargas/DescargaSocial.").put("bytes",1024).put("expected",1024));
            target.getSharedPreferences("download_queue",0).edit().putString("history",history.toString()).putInt("sequence",1).commit();
            Activity activity=startActivitySync(new Intent().setClassName(target.getPackageName(),target.getPackageName()+".MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            final List<Object> items=new ArrayList<>();
            Constructor<?> media=cls("MediaItem").getConstructor(String.class,boolean.class,String.class);
            items.add(media.newInstance("https://example.com/fixture1.mp4",true,null));items.add(media.newInstance("https://example.com/fixture2.mp4",true,null));
            final String link="https://www.instagram.com/p/ABCdef12345/";
            main(()->{try {
                ((EditText)field(activity,"urlInput")).setText(link);
                field(activity,"analyzedUrl",link);
                Object platform=cls("SocialPlatform").getField("INSTAGRAM").get(null);
                Object extraction=cls("ExtractionResult").getConstructor(cls("SocialPlatform"),String.class,String.class,List.class).newInstance(platform,link,"ABCdef12345",items);
                field(activity,"extractionResult",extraction);
                Method render=activity.getClass().getDeclaredMethod("renderMedia",List.class,int.class);render.setAccessible(true);render.invoke(activity,items,(Integer)field(activity,"contentGeneration"));
                ((CheckBox)((List<?>)field(activity,"itemChecks")).get(1)).setChecked(false);
                field(activity,"busy",true);
                ((ProgressBar)field(activity,"progressBar")).setIndeterminate(true);
            }catch(Exception e){throw new RuntimeException(e);}});
            Object worker=field(activity,"operationWorker");Object extraction=field(activity,"extractionResult");
            // Framework delivers real locale config events; the Activity/worker must survive them.
            LocaleManager manager=target.getSystemService(LocaleManager.class);
            for(String language:new String[]{"es","en","fr","es"}){
                manager.setApplicationLocales(LocaleList.forLanguageTags(language));
                Thread.sleep(1800);waitForIdleSync();
                main(()->{try {
                    check(!activity.isDestroyed(),"Locale change does not destroy composition");
                    check(field(activity,"operationWorker")==worker,"Analysis worker retained");
                    check((Boolean)field(activity,"busy"),"Busy state retained");
                    check(((ProgressBar)field(activity,"progressBar")).isIndeterminate(),"Indeterminate progress retained");
                    check(field(activity,"extractionResult")==extraction,"Analysis result retained");
                    check(((EditText)field(activity,"urlInput")).getText().toString().equals(link),"Link retained");
                    boolean[] selected=(boolean[])field(activity,"selectedItems");check(selected.length==2 && selected[0] && !selected[1],"Selection retained");
                    check(((Button)field(activity,"newTab")).getText().toString().equals(language.equals("es")?"Nuevo":"New"),"Visible tab uses new locale");
                    check(((Button)field(activity,"downloadButton")).getText().toString().equals(language.equals("es")?"Descargar (1)":"Download (1)"),"Visible action/count localized");
                    Object row=((Map<?,?>)field(activity,"taskRows")).get("legacy-1");
                    check(row!=null,"Previous history retained");
                    check(((TextView)field(row,"title")).getText().toString().startsWith(language.equals("es")?"Petición 1":"Request 1"),"Previous history label translated");
                    check(((TextView)field(row,"message")).getText().toString().contains(language.equals("es")?"Archivo guardado":"File saved"),"Previous history status translated");
                }catch(Exception e){throw new RuntimeException(e);}});
            }
            manager.setApplicationLocales(LocaleList.getEmptyLocaleList());
            main(activity::finish);
            result.putString("stream","PASS "+checks+" native resource/lifecycle checks. No live download validation.\n");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
    }
}
