// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Two independent workers in one visible dataSync service, plus a bounded FIFO queue. */
public final class DownloadService extends Service {
    private static final String CHANNEL = "descargas";
    private static final String START = "com.didazz.descargasocial.DOWNLOAD";
    private static final String CANCEL = "com.didazz.descargasocial.CANCEL_DOWNLOAD";
    private static final int NOTIFICATION_ID = 1201;
    private static final long MAX_BATCH_MS = 2L * 60 * 60 * 1000;
    private static DownloadQueue<Batch> queue;
    private static boolean stopping;
    private static int draining, sequence;

    private static final class Batch {
        final List<MediaItem> items;
        final List<Integer> positions;
        final String contentId;
        Batch(List<MediaItem> items, List<Integer> positions, String contentId) {
            this.items = new ArrayList<>(items); this.positions = new ArrayList<>(positions);
            this.contentId = contentId.replaceAll("[^A-Za-z0-9_-]", "");
        }
    }
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("download_queue", MODE_PRIVATE);
    }
    private static synchronized DownloadQueue<Batch> queue(Context context) {
        if (queue != null) { return queue; }
        queue = new DownloadQueue<>(2, 20, 50);
        SharedPreferences saved = preferences(context);
        sequence = saved.getInt("sequence", 0);
        try {
            JSONArray history = new JSONArray(saved.getString("history", "[]"));
            for (int i = Math.max(0, history.length() - 50); i < history.length(); i++) {
                JSONObject item = history.getJSONObject(i);
                queue.restore(new DownloadQueue.Snapshot(item.getString("id"), item.getString("label"),
                        DownloadQueue.Phase.valueOf(item.getString("phase")), item.getString("message"),
                        item.optLong("bytes", 0), item.optLong("expected", -1)));
            }
        } catch (Exception ignored) { /* A damaged history cannot block new downloads. */ }
        persist(context);
        return queue;
    }
    static List<DownloadQueue.Snapshot> getStates(Context context) { return queue(context).snapshots(); }
    static long revision(Context context) { return queue(context).revision(); }
    static void clearCompleted(Context context) { queue(context).clearCompleted(); persist(context); }

    public static synchronized String start(Context context, List<MediaItem> items,
            List<Integer> positions, String contentId, String label) {
        DownloadQueue<Batch> tasks = queue(context);
        if (stopping || draining > 0) {
            throw new IllegalStateException(Messages.ref("service_closing"));
        }
        if (items.isEmpty() || items.size() != positions.size() || items.size() > 200) {
            throw new IllegalArgumentException(Messages.ref("invalid_selection"));
        }
        Context app = context.getApplicationContext();
        String id = UUID.randomUUID().toString();
        tasks.add(id, Messages.ref("request_number", ++sequence) + " · " + label, new Batch(items, positions, contentId));
        persist(app);
        try {
            Intent intent = new Intent(app, DownloadService.class).setAction(START);
            if (Build.VERSION.SDK_INT >= 26) { app.startForegroundService(intent); }
            else { app.startService(intent); }
        } catch (RuntimeException rejected) {
            tasks.finish(id, Messages.ref("service_start_failed"), false, false);
            persist(app);
            throw new IllegalStateException(Messages.ref("service_start_failed"), rejected);
        }
        return id;
    }
    public static void cancel(Context context, String id) {
        context.startService(new Intent(context, DownloadService.class).setAction(CANCEL).putExtra("task_id", id));
    }
    private static synchronized void persist(Context context) {
        if (queue == null) { return; }
        JSONArray history = new JSONArray();
        for (DownloadQueue.Snapshot s : queue.snapshots()) {
            try {
                history.put(new JSONObject().put("id", s.id).put("label", s.label).put("phase", s.phase.name())
                        .put("message", s.message).put("bytes", s.bytes).put("expected", s.expected));
            } catch (Exception ignored) { }
        }
        // No media URLs, cookies, credentials or Activity references are persisted.
        preferences(context).edit().putString("history", history.toString()).putInt("sequence", sequence).apply();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Runner> runners = new LinkedHashMap<>();
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground, destroyed, closing;
    private int latestStartId;

    private final class Runner {
        final String id;
        final Batch batch;
        final DownloadControl control;
        final Runnable deadline;
        int position;
        long lastProgress;
        boolean done;
        Runner(DownloadQueue.Work<Batch> work) {
            id = work.id; batch = work.value;
            control = new DownloadControl(new DownloadControl.Progress() {
                @Override public void update(long bytes, long expected) {
                    long now = SystemClock.elapsedRealtime();
                    if (bytes != 0 && now - lastProgress < 600) { return; }
                    lastProgress = now;
                    String phase = control.phase() == null ? Messages.ref("progress_position", position, batch.items.size()) : control.phase();
                    String label = expected > 0
                            ? Messages.ref("progress_known", phase, String.format(Locale.ROOT, "%.1f", bytes / 1048576.0), String.format(Locale.ROOT, "%.1f", expected / 1048576.0))
                            : Messages.ref("progress_unknown", phase, String.format(Locale.ROOT, "%.1f", bytes / 1048576.0));
                    publish(Runner.this, label, bytes, expected);
                }
            });
            deadline = new Runnable() {
                @Override public void run() {
                    cancelTask(id, Messages.ref("task_timeout"));
                }
            };
        }
    }
    @Override public void onCreate() {
        super.onCreate(); queue(this);
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.downloads_tab),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            channel.setSound(null, null); notifications.createNotificationChannel(channel);
        }
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.downloads_tab),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
        notifySafely();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent != null && CANCEL.equals(intent.getAction())) {
            cancelTask(intent.getStringExtra("task_id"), Messages.ref("request_cancelled"));
            if (foreground || queue.activeCount() == 0) {
                pump(); return START_NOT_STICKY;
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else { startForeground(NOTIFICATION_ID, notification()); }
            foreground = true;
            if (queue.activeCount() > 0 && !closing) {
                if (wakeLock == null) {
                    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DescargaSocial:downloads");
                    wakeLock.setReferenceCounted(false);
                }
                wakeLock.acquire(MAX_BATCH_MS + 90_000);
            }
            pump();
        } catch (RuntimeException rejected) {
            stopAll(Messages.ref("service_keep_failed"));
        }
        return START_NOT_STICKY;
    }
    /** Main thread only. A slot is released only after its worker has stopped writing. */
    private void pump() {
        if (destroyed || closing) { return; }
        DownloadQueue.Work<Batch> work;
        while ((work = queue.takeNext()) != null) {
            final Runner runner = new Runner(work);
            runners.put(runner.id, runner);
            if (wakeLock != null) { wakeLock.acquire(MAX_BATCH_MS + 90_000); }
            main.postDelayed(runner.deadline, MAX_BATCH_MS);
            new Thread(new Runnable() {
                @Override public void run() { runBatch(runner); }
            }, "social-download-" + runner.id.substring(0, 8)).start();
        }
        persist(this);
        if (queue.activeCount() == 0 && runners.isEmpty()) {
            releaseWakeLock();
            if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
            notifySafely(); stopSelfResult(latestStartId);
        } else { notifySafely(); }
    }
    private void runBatch(final Runner runner) {
        int saved = 0, failures = 0;
        String firstError = null;
        DownloadControl control = runner.control;
        Batch batch = runner.batch;
        control.attach();
        try {
            control.startPhase(Messages.ref("phase_storage"),-1);
            try (final DownloadStorage storage = new DownloadStorage(this, control, runner.id)) {
            for (int index = 0; index < batch.items.size(); index++) {
                control.check(); runner.position = index + 1;
                final int selected = index;
                try {
                    DownloadPolicy.execute(new DownloadPolicy.Attempt() {
                        @Override public void run() throws Exception {
                            control.startPhase(Messages.ref("phase_recover"),-1);
                            storage.recoverPending();
                            control.startPhase(Messages.ref("phase_destination"),-1);
                            storage.saveMedia(batch.items.get(selected), batch.contentId, batch.positions.get(selected));
                        }
                    }, new DownloadPolicy.Retry() {
                        @Override public void waiting(int attempt, long delay) throws Exception {
                            publish(runner, Messages.ref("retry_attempt", attempt), 0, -1);
                            control.pause(delay);
                        }
                    }, control);
                    saved++;
                } catch (DownloadControl.Cancelled cancelled) { throw cancelled; }
                catch (Exception error) {
                    failures++;
                    if (firstError == null) { firstError = DownloadDiagnostics.report(error,control.phase()); }
                }
            }
            }
        } catch (Exception error) {
            if (!control.isCancelled()) {
                failures++;
                if (firstError == null) { firstError = DownloadDiagnostics.report(error,control.phase()); }
            }
        } finally { control.detach(); }
        final boolean success = !control.isCancelled() && failures == 0;
        String summary;
        if (control.isCancelled()) {
            summary = control.cancellationReason();
            if (saved > 0) { summary += Messages.ref("saved_kept", saved); }
        } else if (failures > 0) {
            summary = (saved > 0 ? Messages.ref("saved_failed", saved, failures) : "") + firstError;
        } else {
            summary = saved == 1 ? Messages.ref("saved_one")
                    : Messages.ref("saved_many", saved);
        }
        final String result = summary;
        main.post(new Runnable() {
            @Override public void run() { finish(runner, result, success); }
        });
    }
    private void publish(Runner runner, String message, long bytes, long expected) {
        queue.progress(runner.id, message, bytes, expected);
        main.post(new Runnable() {
            @Override public void run() { if (!destroyed && !closing) { notifySafely(); } }
        });
    }
    private void cancelTask(String id, String reason) {
        if (id == null || !queue.cancel(id, reason)) { return; }
        Runner runner = runners.get(id);
        if (runner != null) { runner.control.cancel(reason); }
        persist(this); notifySafely();
    }
    private void finish(Runner runner, String message, boolean success) {
        if (runner.done) { return; }
        runner.done = true;
        main.removeCallbacks(runner.deadline); runners.remove(runner.id);
        queue.finish(runner.id, message, success, runner.control.isCancelled());
        persist(this);
        if (destroyed) {
            synchronized (DownloadService.class) {
                draining = Math.max(0, draining - 1);
                if (draining == 0) { stopping = false; }
            }
        } else if (!closing) { pump(); }
    }
    private void stopAll(String reason) {
        closing = true;
        synchronized (DownloadService.class) { stopping = true; }
        for (DownloadQueue.Snapshot s : queue.snapshots()) {
            if (s.active()) { cancelTask(s.id, reason); }
        }
        releaseWakeLock();
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        stopSelf();
    }
    @SuppressWarnings("deprecation")
    private Notification notification() {
        int running = queue.runningCount(), waiting = queue.activeCount() - running;
        boolean active = running + waiting > 0;
        String text = active ? Messages.ref("queue_summary", running, waiting)
                : Messages.ref("requests_finished");
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).putExtra("show_downloads", true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(active ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.app_name)).setContentText(TextResources.render(this, text))
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active)
                .setAutoCancel(!active).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (active) {
            builder.setProgress(0, 0, true);
            Notification.InboxStyle details = new Notification.InboxStyle().setSummaryText(TextResources.render(this, text));
            for (DownloadQueue.Snapshot task : queue.snapshots()) {
                if (!task.running()) { continue; }
                String number = task.label.split(" · ", 2)[0];
                details.addLine(TextResources.render(this, number + ": " + task.message));
                if (task.phase == DownloadQueue.Phase.CANCELLING) { continue; }
                Intent cancel = new Intent(this, DownloadService.class).setAction(CANCEL)
                        .setData(Uri.parse("descargasocial://cancel/" + task.id)).putExtra("task_id", task.id);
                PendingIntent action = PendingIntent.getService(this, 0, cancel,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.addAction(new Notification.Action.Builder(null,
                        getString(R.string.cancel_named, TextResources.render(this, number)), action).build());
            }
            builder.setStyle(details);
        }
        return builder.build();
    }
    private void notifySafely() {
        try { notifications.notify(NOTIFICATION_ID, notification()); }
        catch (SecurityException denied) { /* In-app task controls remain available. */ }
    }
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); }
    }
    @Override public void onTimeout(int startId, int foregroundServiceType) {
        stopAll(Messages.ref("background_timeout"));
    }
    @Override public void onDestroy() {
        destroyed = true;
        synchronized (DownloadService.class) { stopping = true; draining += runners.size(); }
        for (DownloadQueue.Snapshot s : queue.snapshots()) {
            if (s.active()) { queue.cancel(s.id, Messages.ref("android_stopped")); }
        }
        for (Runner runner : runners.values()) {
            main.removeCallbacks(runner.deadline);
            runner.control.cancel(Messages.ref("android_stopped"));
        }
        persist(this); releaseWakeLock();
        synchronized (DownloadService.class) { if (draining == 0) { stopping = false; } }
        // Keep finish callbacks so each worker retires its own slot and storage marker.
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
