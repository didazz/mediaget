// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

/** Locale-neutral messages. Persist keys/arguments, never translated worker state. */
final class Messages {
    private static final char OPEN = '\u001e', CLOSE = '\u001f';
    interface Resolver { String get(String key, Object[] arguments); }
    private Messages() {}
    static String ref(String key, Object... arguments) {
        if (!key.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("message key");
        StringBuilder out = new StringBuilder().append(OPEN).append(key);
        for (Object value : arguments) {
            out.append('|').append(value instanceof Float || value instanceof Double ? 'f' : value instanceof Number ? 'n' : 's');
            String text = String.valueOf(value);
            for (int i=0;i<text.length();i++) {
                String hex = Integer.toHexString(text.charAt(i));
                for (int j=hex.length();j<4;j++) out.append('0');
                out.append(hex);
            }
        }
        return out.append(CLOSE).toString();
    }
    static boolean contains(String text) { return text != null && text.indexOf(OPEN)>=0; }
    static String render(String text, Resolver resolver) { return render(text, resolver, 0); }
    private static String render(String text, Resolver resolver, int depth) {
        if(text==null) return "";
        if(depth>12 || text.length()>131072) return resolver.get("error_download", new Object[0]);
        StringBuilder out=new StringBuilder(); int cursor=0;
        while(cursor<text.length()) {
            int start=text.indexOf(OPEN,cursor);
            if(start<0) { out.append(text.substring(cursor)); break; }
            out.append(text.substring(cursor,start)); int end=text.indexOf(CLOSE,start);
            if(end<0) { out.append(resolver.get("error_download",new Object[0]));break; }
            try {
                String[] fields=text.substring(start+1,end).split("\\|",-1);
                if(!fields[0].matches("[a-z][a-z0-9_]*") || fields.length>17) throw new IllegalArgumentException();
                Object[] args=new Object[fields.length-1];
                for(int i=1;i<fields.length;i++) {
                    String v=fields[i]; if(v.length()<1 || (v.length()-1)%4!=0)throw new IllegalArgumentException();
                    StringBuilder decoded=new StringBuilder();
                    for(int j=1;j<v.length();j+=4)decoded.append((char)Integer.parseInt(v.substring(j,j+4),16));
                    String raw=decoded.toString();
                    if(v.charAt(0)=='n') args[i-1]=Long.valueOf(raw);
                    else if(v.charAt(0)=='f') args[i-1]=Double.valueOf(raw);
                    else if(v.charAt(0)=='s') args[i-1]=render(raw,resolver,depth+1);
                    else throw new IllegalArgumentException();
                }
                out.append(resolver.get(fields[0],args));
            } catch(RuntimeException invalid) {out.append(resolver.get("error_download",new Object[0]));}
            cursor=end+1;
        }
        return out.toString();
    }
    static boolean isPhase(String text) {
        return text!=null && text.matches("\\x1ephase_[a-z_]+\\x1f") && ResourceKeys.isPhase(text.substring(1,text.length()-1));
    }
    static boolean deniesRetry(Throwable error) {
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(int i=0;error!=null && i<16 && seen.add(error);i++,error=error.getCause()) {
            String value=error.getMessage(); if(value==null)continue;
            for(String key:ResourceKeys.NO_RETRY) if(value.contains(""+OPEN+key+CLOSE)) return true;
        }
        return false;
    }
}
