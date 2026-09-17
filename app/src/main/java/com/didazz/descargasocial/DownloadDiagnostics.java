// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.util.*;
import java.util.regex.*;
/** Bounded diagnostic without raw exception messages, paths, network URLs or credentials. */
final class DownloadDiagnostics {
    private static final Pattern HTTP=Pattern.compile("(?i)\\bHTTP[ :]+([1-5][0-9]{2})\\b");
    private static final Pattern SYSTEM=Pattern.compile("\\b(EACCES|EPERM|ENOSPC|ENOENT|EIO|ECONNRESET|ECONNABORTED|ETIMEDOUT)\\b");
    static List<Throwable> failures(Throwable error) {
        List<Throwable> result=new ArrayList<>();if(error==null)return result;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        ArrayDeque<Throwable> next=new ArrayDeque<>();next.add(error);
        while(!next.isEmpty() && result.size()<16) {
            Throwable t=next.remove();if(!seen.add(t))continue;result.add(t);
            if(t.getCause()!=null)next.add(t.getCause());
            for(Throwable s:t.getSuppressed()){if(next.size()<32)next.add(s);}
        }
        return result;
    }
    static StorageException storageFailure(Throwable error) {
        for(Throwable t:failures(error))if(t instanceof StorageException)return (StorageException)t;
        return null;
    }
    static String report(Throwable error,String phase) {
        String friendly=DownloadPolicy.friendly(error);
        StorageException storage=storageFailure(error);
        if(storage!=null && storage!=error)friendly+="\n"+Messages.ref("cleanup_pending", storage.code);
        StringBuilder details=new StringBuilder(friendly).append("\n\n"+Messages.ref("diagnostic_heading", "1.5.0"));
        details.append("\n").append(Messages.ref("diagnostic_phase", safePhase(phase)));
        int count=0;
        for(Throwable t:failures(error)) {
            if(count++>=4)break;
            details.append("\n").append(t.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]",""));
            if(t instanceof StorageException)details.append(" [").append(((StorageException)t).code).append(']');
            String message=t.getMessage();
            if(message!=null) {
                Matcher http=HTTP.matcher(message);if(http.find())details.append(" HTTP ").append(http.group(1));
                Matcher system=SYSTEM.matcher(message);if(system.find())details.append(' ').append(system.group(1));
            }
            for(StackTraceElement frame:t.getStackTrace()) {
                if(frame.getClassName().startsWith("com.didazz.descargasocial.")) {
                    String name=frame.getClassName().substring("com.didazz.descargasocial.".length());
                    details.append(" · ").append(name.replaceAll("[^A-Za-z0-9_$]",""))
                            .append('.').append(frame.getMethodName().replaceAll("[^A-Za-z0-9_$]",""))
                            .append(':').append(frame.getLineNumber());break;
                }
            }
        }
        return details.toString();
    }
    private static String safePhase(String phase) {
        if (phase == null) return Messages.ref("phase_preparation");
        if (Messages.isPhase(phase)) return phase;
        return Messages.ref("phase_download");
    }
}
