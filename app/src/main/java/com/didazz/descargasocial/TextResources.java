// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import android.content.Context;
import android.content.res.Configuration;
import java.util.*;
import java.util.regex.*;

/** Android-only rendering boundary. Languages and translations live in res/values*. */
final class TextResources {
    private TextResources() {}
    private static final Pattern ARG = Pattern.compile("%(\\d+)\\$([dsf])");
    private static volatile List<Legacy> legacy;
    private static final class Legacy {
        final String key, literal; final Pattern pattern; final int[] positions; final char[] types;
        Legacy(String key, String template) {
            this.key=key;literal=template;
            Matcher m=ARG.matcher(template);StringBuilder regex=new StringBuilder("^");int at=0;
            List<Integer> p=new ArrayList<>();List<Character> t=new ArrayList<>();
            while(m.find()) {
                regex.append(Pattern.quote(template.substring(at,m.start()))).append(m.group(2).equals("d")?"([0-9]+)":"(.+?)");
                p.add(Integer.parseInt(m.group(1))-1);t.add(m.group(2).charAt(0));at=m.end();
            }
            regex.append(Pattern.quote(template.substring(at))).append('$');
            positions=new int[p.size()];types=new char[t.size()];
            for(int i=0;i<p.size();i++){positions[i]=p.get(i);types[i]=t.get(i);}
            pattern=p.isEmpty()?null:Pattern.compile(regex.toString(),Pattern.DOTALL);
        }
        String match(String input) {
            if(pattern==null)return literal.equals(input)?Messages.ref(key):null;
            Matcher m=pattern.matcher(input);if(!m.matches())return null;
            Object[] args=new Object[positions.length];
            try {for(int i=0;i<positions.length;i++)args[positions[i]]=types[i]=='d'?Long.valueOf(m.group(i+1)):m.group(i+1);}
            catch(RuntimeException invalid){return null;}
            return Messages.ref(key,args);
        }
    }
    private static List<Legacy> legacy(Context context) {
        List<Legacy> ready=legacy;if(ready!=null)return ready;
        synchronized(TextResources.class) {
            if(legacy!=null)return legacy;
            List<Legacy> all=new ArrayList<>();
            // Upgrade adapter for Spanish-only 1.4.x history and old internal diagnostic messages.
            // New languages need only resources; this migration never selects the app language.
            for(String language:new String[]{"es","en"}) {
                Configuration c=new Configuration(context.getResources().getConfiguration());
                c.setLocale(Locale.forLanguageTag(language));
                Context localized=context.createConfigurationContext(c);
                for(int i=0;i<ResourceIds.KEYS.length;i++) all.add(new Legacy(ResourceIds.KEYS[i],localized.getString(ResourceIds.IDS[i])));
            }
            legacy=Collections.unmodifiableList(all);return legacy;
        }
    }
    static String defer(Context context,String text) {
        if(text==null || text.isEmpty())return "";
        if(text.length()>131072)return Messages.ref("error_query");
        if(Messages.contains(text)) {
            // A migrated record can mix old labels with new message tokens.
            StringBuilder out=new StringBuilder();int cursor=0;
            while(cursor<text.length()) {
                int start=text.indexOf('\u001e',cursor);
                if(start<0){out.append(defer(context,text.substring(cursor)));break;}
                out.append(defer(context,text.substring(cursor,start)));
                int end=text.indexOf('\u001f',start);
                if(end<0){out.append(text.substring(start));break;}
                out.append(text.substring(start,end+1));cursor=end+1;
            }
            return out.toString();
        }
        for(Legacy candidate:legacy(context)) {
            String token=candidate.match(text);if(token!=null)return token;
        }
        // Older history records contain concatenated labels and multi-line diagnostics.
        // Translate their known parts without altering the persisted record or a worker.
        for (String separator : new String[]{"\n", " · "}) {
            if (text.contains(separator)) {
                String[] parts=text.split(Pattern.quote(separator), -1);
                StringBuilder out=new StringBuilder();
                for(int i=0;i<parts.length;i++) {
                    if(i>0)out.append(separator);
                    out.append(defer(context,parts[i]));
                }
                return out.toString();
            }
        }
        return text;
    }
    static String render(Context context,String text) {
        return Messages.render(defer(context,text),(key,args)->{
            int id=ResourceIds.find(key);
            if(id==0)return context.getString(R.string.error_download);
            for(int i=0;i<args.length;i++) if(args[i] instanceof String) args[i]=render(context,(String)args[i]);
            return context.getString(id,args);
        });
    }
    static String error(Context context,String text) {
        String deferred=defer(context,text);
        return Messages.contains(deferred)?deferred:Messages.ref("error_query");
    }
}
