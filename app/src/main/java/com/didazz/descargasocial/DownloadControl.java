// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/** Per-transfer cancellation and byte progress, independent of every Android Activity. */
public final class DownloadControl {
    private static final ThreadLocal<DownloadControl> CURRENT = new ThreadLocal<>();
    public interface Progress { void update(long bytes, long expected); }
    public static final class Cancelled extends InterruptedIOException {
        private static final long serialVersionUID = 1L;
        Cancelled(String reason) { super(reason); }
    }
    private final Progress progress;
    private volatile String cancelled;
    private volatile Thread owner;
    private HttpURLConnection connection;
    private long expected = -1;
    private long bytes;
    private volatile String phase;

    public DownloadControl(Progress progress) { this.progress = progress; }
    public void attach() { owner = Thread.currentThread(); CURRENT.set(this); }
    public void detach() {
        CURRENT.remove(); owner = null;
        synchronized (this) { connection = null; }
    }
    public boolean isCancelled() { return cancelled != null; }
    public String cancellationReason() { return cancelled == null ? Messages.ref("cancelled") : cancelled; }
    public void check() throws Cancelled {
        if (cancelled != null || Thread.currentThread().isInterrupted()) {
            throw new Cancelled(cancellationReason());
        }
    }
    public void cancel(String reason) {
        final HttpURLConnection active;
        synchronized (this) {
            if (cancelled != null) { return; }
            cancelled = reason == null ? Messages.ref("cancelled") : reason;
            active = connection;
        }
        Thread thread = owner;
        if (thread != null) { thread.interrupt(); }
        if (active != null) {
            // HttpURLConnection.disconnect may block: never run it on Android's main thread.
            Thread closer = new Thread(new Runnable() {
                @Override public void run() { active.disconnect(); }
            }, "download-cancel");
            closer.setDaemon(true);
            closer.start();
        }
    }
    public void beginAttempt() throws Cancelled {
        check(); bytes = 0; expected = -1; phase = null; progress.update(0, -1);
    }
    public static DownloadControl current() { return CURRENT.get(); }
    public String phase() { return phase; }
    public void startPhase(String label, long length) throws Cancelled {
        check(); phase=label; bytes=0; expected=length; progress.update(0,length);
    }
    public void pause(long milliseconds) throws Cancelled {
        check();
        try { Thread.sleep(milliseconds); }
        catch (InterruptedException stopped) {
            Thread.currentThread().interrupt(); throw new Cancelled(cancellationReason());
        }
        check();
    }
    public OutputStream wrap(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override public void write(int value) throws IOException {
                checkSize(1); out.write(value); report(1);
            }
            @Override public void write(byte[] data, int offset, int length) throws IOException {
                checkSize(length); out.write(data, offset, length); report(length);
            }
            private void checkSize(int count) throws IOException {
                check();
                if (count > MediaValidator.MAX_VIDEO_BYTES - bytes) {
                    throw new IOException("El archivo supera el límite de seguridad de 2 GiB.");
                }
            }
            private void report(int count) {
                bytes += count;
                progress.update(bytes, expected);
            }
        };
    }
    public static void track(HttpURLConnection opened) throws IOException {
        DownloadControl control = CURRENT.get();
        if (control == null) { return; }
        synchronized (control) {
            if (control.isCancelled()) { opened.disconnect(); control.check(); }
            control.connection = opened;
        }
        control.check();
    }
    public static void release(HttpURLConnection closed) {
        DownloadControl control = CURRENT.get();
        if (control != null) {
            synchronized (control) {
                if (control.connection == closed) { control.connection = null; }
            }
        }
    }
    public static void reportSize(long length) {
        DownloadControl control = CURRENT.get();
        if (control != null) { control.expected = length > 0 ? length : -1; }
    }
}
