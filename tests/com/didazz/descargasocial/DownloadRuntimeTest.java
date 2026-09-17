// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;

/** Offline tests. No external pages, credentials, media or network requests are used. */
public final class DownloadRuntimeTest {
    private static int assertions;
    private static DownloadControl control() { return new DownloadControl((bytes, size) -> { }); }
    private static void check(boolean result, String label) {
        assertions++;
        if (!result) { throw new AssertionError(label); }
    }
    private static void throwsCancelled(DownloadPolicy.Attempt action) throws Exception {
        try { action.run(); throw new AssertionError("Cancellation was ignored"); }
        catch (DownloadControl.Cancelled expected) { assertions++; }
    }
    public static void main(String[] args) throws Exception {
        check(DownloadPolicy.retryable(new SocketException("Software caused connection abort")), "socket abort");
        check(DownloadPolicy.retryable(new SocketTimeoutException()), "timeout");
        check(DownloadPolicy.retryable(new UnknownHostException()), "DNS failure");
        check(DownloadPolicy.retryable(new EOFException()), "truncated transfer");
        check(DownloadPolicy.retryable(new IOException("HTTP 503")), "server temporarily unavailable");
        check(!DownloadPolicy.retryable(new IOException("HTTP 403")), "no retry denied access");
        check(!DownloadPolicy.retryable(new IOException("HTTP 429")), "no retry rate limit");
        check(!DownloadPolicy.retryable(new IOException("archivo caducado")), "no retry expired link");
        check(!DownloadPolicy.retryable(new IOException("DRM")), "no retry DRM");
        check(!DownloadPolicy.retryable(new IOException("ENOSPC")), "no retry full disk");
        check(!DownloadPolicy.retryable(new IOException("formato inválido")), "no retry invalid content");
        check(!DownloadPolicy.retryable(new SecurityException()), "no retry permissions");
        check(!DownloadPolicy.retryable(new SSLException(new SocketException())), "no retry TLS denial");
        check(!DownloadPolicy.retryable(new IOException("HTTP 401", new SocketException())), "denial wins");
        check(DownloadPolicy.retryable(new IOException("wrapper", new SocketException("ECONNABORTED"))), "nested socket");
        check(DownloadPolicy.friendly(new SocketException("Software caused connection abort"))
                .contains("error_retries"), "Localized connection error resource");
        check(!DownloadPolicy.friendly(new IOException("https://secret.example/path?token=secret"))
                .contains("secret"), "no URL or token in generic error");
        check(DownloadPolicy.friendly(new SSLException("TLS"))
                .contains("error_tls"), "Localized secure-connection resource");
        Throwable a = new IOException(), b = new IOException(), c = new IOException();
        a.initCause(b); b.initCause(c); c.initCause(b);
        check(!DownloadPolicy.retryable(a), "cyclic exception bounded");
        check(DownloadPolicy.friendly(a) != null, "cyclic friendly bounded");

        int[] calls = {0};
        List<Long> delays = new ArrayList<>();
        DownloadPolicy.execute(() -> { if (++calls[0] < 3) { throw new SocketException(); } },
                (attempt, delay) -> delays.add(delay), control());
        check(calls[0] == 3, "third attempt succeeds");
        check(delays.size() == 2 && delays.get(0) == 2000L && delays.get(1) == 5000L, "bounded backoff");
        calls[0] = 0;
        try {
            DownloadPolicy.execute(() -> { calls[0]++; throw new SocketException(); },
                    (attempt, delay) -> { }, control());
            throw new AssertionError("expected failure");
        } catch (SocketException expected) { check(calls[0] == 3, "never a fourth attempt"); }
        calls[0] = 0;
        try {
            DownloadPolicy.execute(() -> { calls[0]++; throw new IOException("HTTP 403"); },
                    (attempt, delay) -> { throw new AssertionError("denial retried"); }, control());
        } catch (IOException expected) { check(calls[0] == 1, "denial attempted once"); }

        DownloadControl cancellation = control();
        calls[0] = 0;
        throwsCancelled(() -> DownloadPolicy.execute(() -> { calls[0]++; throw new SocketException(); },
                (attempt, delay) -> cancellation.cancel("Cancelada por el usuario."), cancellation));
        check(calls[0] == 1, "cancel during backoff prevents retry");
        throwsCancelled(() -> cancellation.pause(5000));
        DownloadControl committed = control();
        DownloadPolicy.execute(() -> committed.cancel("Cancelada tras completar"), (attempt, delay) -> { }, committed);
        check(committed.isCancelled(), "completed action not falsely retried after commit");

        List<long[]> samples = new ArrayList<>();
        DownloadControl bytes = new DownloadControl((done, size) -> samples.add(new long[]{done, size}));
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        bytes.attach();
        try {
            bytes.beginAttempt();
            DownloadControl.reportSize(10);
            try (OutputStream output = bytes.wrap(destination)) {
                output.write(new byte[]{1, 2, 3}); output.write(4);
            }
            check(destination.size() == 4, "no duplicate byte writes");
            check(samples.get(samples.size() - 1)[0] == 4, "actual byte progress");
            check(samples.get(samples.size() - 1)[1] == 10, "known length progress");
            bytes.beginAttempt();
            check(samples.get(samples.size() - 1)[0] == 0, "retry resets byte progress");
            check(samples.get(samples.size() - 1)[1] == -1, "retry resets expected size");
        } finally { bytes.detach(); }
        bytes.cancel("Cancelada");
        int written = destination.size();
        throwsCancelled(() -> bytes.wrap(destination).write(5));
        check(destination.size() == written, "no writes after cancel");

        List<byte[]> published = new ArrayList<>();
        DownloadControl transactional = control();
        calls[0] = 0;
        DownloadPolicy.execute(() -> {
            ByteArrayOutputStream attempt = new ByteArrayOutputStream();
            try (OutputStream stream = transactional.wrap(attempt)) {
                stream.write(new byte[]{7, 8});
                if (++calls[0] == 1) { throw new SocketException(); }
                stream.write(9);
            }
            published.add(attempt.toByteArray());
        }, (attempt, delay) -> { }, transactional);
        check(published.size() == 1 && published.get(0).length == 3, "only complete retry published");

        // Simulate a socket read which ignores interruption and only exits when disconnected.
        DownloadControl blocked = control();
        FakeConnection socket = new FakeConnection();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            blocked.attach();
            try {
                DownloadControl.track(socket); ready.countDown();
                while (socket.closed.getCount() > 0) {
                    try { socket.closed.await(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* mimic a blocked native read */ }
                }
                blocked.check();
                failure.set(new AssertionError("read was not cancelled"));
            } catch (DownloadControl.Cancelled expected) { }
            catch (Throwable error) { failure.set(error); }
            finally { DownloadControl.release(socket); blocked.detach(); }
        });
        thread.setDaemon(true); thread.start();
        check(ready.await(2, TimeUnit.SECONDS), "socket ready");
        blocked.cancel("Cancelada");
        thread.join(2000);
        check(!thread.isAlive() && socket.closed.getCount() == 0, "cancellation closes active connection");
        check(failure.get() == null, "blocked read exits with cancellation");
        check(!Thread.currentThread().isInterrupted(), "UI caller not interrupted");

        DownloadControl released = control(); FakeConnection old = new FakeConnection();
        released.attach(); DownloadControl.track(old); DownloadControl.release(old); released.detach();
        released.cancel("Cancelada");
        check(old.closed.getCount() == 1, "released connection not cancelled again");
        System.out.println("DownloadRuntimeTest: " + assertions + " checks passed");
    }
    private static final class FakeConnection extends HttpURLConnection {
        final CountDownLatch closed = new CountDownLatch(1);
        FakeConnection() throws Exception { super(new URL("https://example.invalid/fixture")); }
        @Override public void disconnect() { closed.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { throw new AssertionError("No network requests allowed"); }
    }
}
