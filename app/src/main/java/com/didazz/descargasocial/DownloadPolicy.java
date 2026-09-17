// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/** Bounded retries; never retry denials, TLS validation, unsupported formats or local storage. */
public final class DownloadPolicy {
    public static final int MAX_ATTEMPTS = 3;
    public interface Attempt { void run() throws Exception; }
    public interface Retry { void waiting(int nextAttempt, long delayMs) throws Exception; }
    private DownloadPolicy() { }
    public static void execute(Attempt action, Retry retry, DownloadControl control) throws Exception {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            control.beginAttempt();
            try { action.run(); return; }
            catch (Exception error) {
                control.check();
                if (attempt == MAX_ATTEMPTS || !retryable(error)) { throw error; }
                retry.waiting(attempt + 1, attempt == 1 ? 2000L : 5000L);
                control.check();
            }
        }
    }
    public static boolean retryable(Throwable error) {
        if(DownloadDiagnostics.storageFailure(error)!=null || Messages.deniesRetry(error)) { return false; }
        String messages = messages(error);
        if (messages.matches("(?s).*\\b(?:401|403|404|410|429)\\b.*")
                || messages.contains("caduc") || messages.contains("cifrado")
                || messages.contains("drm") || messages.contains("enospc")
                || messages.contains("archivo parcial")) { return false; }
        boolean transientError = false;
        Throwable item = error;
        for (int depth = 0; item != null && depth < 16; depth++, item = next(item)) {
            if (item instanceof DownloadControl.Cancelled || item instanceof SSLException
                    || item instanceof SecurityException) { return false; }
            if (item instanceof SocketException || item instanceof SocketTimeoutException
                    || item instanceof UnknownHostException || item instanceof EOFException) {
                transientError = true;
            }
            if (item.getCause() == error) { break; }
        }
        return transientError || messages.matches("(?s).*http[ :]+(?:500|502|503|504)\\b.*");
    }
    public static String friendly(Throwable error) {
        if (error instanceof StorageException) { return error.getMessage(); }
        if (error instanceof YoutubeException) { return error.getMessage(); }
        String message = messages(error);
        if (error instanceof DownloadControl.Cancelled) { return Messages.ref("cancelled"); }
        if (message.contains("enospc") || message.contains("space") || message.contains("almacen")) {
            return Messages.ref("error_storage");
        }
        if (message.matches("(?s).*\\b(?:401|403|410)\\b.*") || message.contains("caduc")) {
            return Messages.ref("error_expired");
        }
        if (message.contains("429") || message.contains("limitado temporalmente")) {
            return Messages.ref("error_limited");
        }
        if (message.contains("404")) { return Messages.ref("error_missing"); }
        if (message.contains("cifrado") || message.contains("drm")) {
            return Messages.ref("error_protected");
        }
        if (retryable(error)) {
            return Messages.ref("error_retries");
        }
        Throwable item = error;
        for (int depth = 0; item != null && depth < 16; depth++, item = next(item)) {
            if (item instanceof SSLException) {
                return Messages.ref("error_tls");
            }
            if (item.getCause() == error) { break; }
        }
        return Messages.ref("error_download");
    }
    private static String messages(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable item = error;
        for (int depth = 0; item != null && depth < 16; depth++, item = next(item)) {
            if (item.getMessage() != null) { result.append(item.getMessage()).append(' '); }
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }
    private static Throwable next(Throwable error) {
        return error.getCause() == error ? null : error.getCause();
    }
}
