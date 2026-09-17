// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.io.*;
import java.net.*;
public final class DownloadDiagnosticsTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        IOException unknown=new IOException("EACCES /data/user/0/private/path https://secret.example/video?token=TOKEN Authorization: ACCOUNT");
        unknown.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.didazz.descargasocial.DownloadStorage","recoverCache","DownloadStorage.java",80)});
        String report=DownloadDiagnostics.report(unknown,Messages.ref("phase_storage"));
        check(report.contains("IOException") && report.contains("EACCES"),"Type and system code retained");
        check(report.contains("DownloadStorage.recoverCache:80"),"Relevant app location retained");
        check(TestResources.render("es",report).contains("Preparando almacenamiento"),"Failure before first transfer has stage");
        check(!report.contains("private") && !report.contains("secret.example") && !report.contains("TOKEN") && !report.contains("ACCOUNT"),"No paths, URLs or credentials copied");
        check(DownloadDiagnostics.report(new IOException("HTTP 416 https://cdn.example/?sig=secret"),Messages.ref("phase_video")).contains("HTTP 416"),"Unhandled HTTP status visible in details");
        check(!DownloadDiagnostics.report(unknown,"https://secret.example").contains("secret.example"),"Only known stage labels displayed");
        StorageException cleanup=new StorageException("TEMP_DELETE","No se pudo retirar el archivo parcial temporal.");
        check(DownloadPolicy.friendly(cleanup).equals(cleanup.getMessage()),"Storage cause no longer reduced to generic message");
        SocketException network=new SocketException("Connection reset");network.addSuppressed(cleanup);
        check(!DownloadPolicy.retryable(network),"Do not retry network over uncleared stage");
        String both=DownloadDiagnostics.report(network,"Descargando sonido");
        check(both.contains("SocketException") && both.contains("TEMP_DELETE"),"Original and cleanup failures both preserved");
        check(TestResources.render("es",both).contains("limpieza"),"User sees that cleanup is also pending");
        YoutubeException mux=new YoutubeException("No se pudo unir el vídeo.",unknown);
        check(DownloadDiagnostics.report(mux,"Uniendo vídeo y sonido").startsWith("No se pudo unir el vídeo."),"Specific native mux message preserved");
        Throwable a=new IOException(),b=new IOException();a.initCause(b);b.initCause(a);a.addSuppressed(b);
        check(DownloadDiagnostics.failures(a).size()==2,"Cyclic cause/suppressed traversal bounded");
        check(DownloadDiagnostics.report(a,null).length()<1000,"Cyclic diagnosis bounded and readable");
        System.out.println("DownloadDiagnosticsTest: "+checks+" comprobaciones");
    }
}
