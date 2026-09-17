// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class DownloadQueueTest {
    static int checks;
    static void check(boolean condition, String message) {
        checks++; if (!condition) { throw new AssertionError(message); }
    }
    static DownloadQueue.Snapshot state(DownloadQueue<?> q, String id) {
        for (DownloadQueue.Snapshot s : q.snapshots()) { if (id.equals(s.id)) { return s; } }
        throw new AssertionError("Missing " + id);
    }
    public static void main(String[] args) throws Exception {
        DownloadQueue<String> q = new DownloadQueue<>(2, 20, 50);
        q.add("a", "A", "media-a"); q.add("b", "B", "media-b");
        check(q.takeNext().value.equals("media-a"), "FIFO first");
        check(q.takeNext().value.equals("media-b"), "FIFO second");
        q.add("c", "C", "media-c"); q.add("d", "D", "media-d");
        check(q.takeNext() == null && q.runningCount() == 2, "Third must wait");
        q.progress("a", "writing a", 30, 100);
        q.progress("b", "writing b", 75, 150);
        check(state(q, "a").bytes == 30 && state(q, "b").percent() == 50, "Independent progress");
        check(q.cancel("a", "cancel a"), "Cancel running");
        check(q.takeNext() == null && q.runningCount() == 2, "Cancelling keeps slot until close");
        check(q.cancel("c", "cancel c"), "Cancel queued");
        check(state(q, "c").phase == DownloadQueue.Phase.CANCELLED, "Queued cancellation immediate");
        q.finish("a", "partial removed", false, true);
        check(q.takeNext().id.equals("d"), "Skip cancelled queued job");
        check(state(q, "b").bytes == 75 && state(q, "b").running(), "Other job remains untouched");
        q.finish("a", "late completion", true, false);
        q.progress("a", "late progress", 80, 100);
        check(state(q, "a").phase == DownloadQueue.Phase.CANCELLED, "Stale callbacks cannot revive task");
        q.clearCompleted();
        check(q.snapshots().size() == 2 && q.activeCount() == 2, "Clear history keeps workers");
        check(!q.cancel("a", "old action"), "Old cancel cannot affect new jobs");
        q.finish("b", "done", true, false);
        check(q.runningCount() == 1 && state(q, "d").running(), "One finishing doesn't stop other");
        check(state(q, "b").percent() == 100, "Terminal progress");
        DownloadQueue<String> restored = new DownloadQueue<>(2, 20, 50);
        for (DownloadQueue.Snapshot s : q.snapshots()) { restored.restore(s); }
        check(restored.activeCount() == 0 && restored.takeNext() == null, "Process death doesn't replay URLs");
        check(state(restored, "d").phase == DownloadQueue.Phase.FAILED, "Interrupted history is honest");
        DownloadQueue<String> full = new DownloadQueue<>(2, 20, 50);
        for (int i = 0; i < 20; i++) { full.add("j" + i, "job", "url" + i); }
        try { full.add("overflow", "job", "value"); throw new AssertionError("Capacity"); }
        catch (IllegalStateException expected) { checks++; }
        full.cancel("j0", "cancel");
        full.add("accepted", "new", "value");
        check(full.activeCount() == 20, "Cancellation frees admission capacity");

        // Eight competing dispatchers must never execute more than two tasks or execute one twice.
        final DownloadQueue<Integer> concurrent = new DownloadQueue<>(2, 20, 50);
        for (int i = 0; i < 20; i++) { concurrent.add("x" + i, "task", i); }
        AtomicInteger live = new AtomicInteger(), peak = new AtomicInteger(), done = new AtomicInteger();
        Set<String> visited = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                while (done.get() < 20) {
                    DownloadQueue.Work<Integer> work = concurrent.takeNext();
                    if (work == null) { Thread.yield(); continue; }
                    if (!visited.add(work.id)) { throw new AssertionError("Duplicate dispatch"); }
                    int count = live.incrementAndGet();
                    peak.accumulateAndGet(count, Math::max);
                    for (int j = 0; j < 100; j++) { Thread.yield(); }
                    live.decrementAndGet();
                    concurrent.finish(work.id, "done", true, false); done.incrementAndGet();
                }
            }));
        }
        for (Future<?> future : futures) { future.get(5, TimeUnit.SECONDS); }
        pool.shutdownNow();
        check(peak.get() <= 2 && done.get() == 20 && visited.size() == 20, "Bounded exactly-once dispatch");
        check(concurrent.activeCount() == 0, "All slots freed");
        System.out.println("DownloadQueueTest: " + checks + " comprobaciones");
    }
}
