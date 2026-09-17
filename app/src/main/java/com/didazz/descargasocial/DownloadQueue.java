// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/** Thread-safe FIFO. Cancellation keeps a running slot until the worker has really stopped. */
final class DownloadQueue<T> {
    enum Phase { QUEUED, RUNNING, CANCELLING, COMPLETE, FAILED, CANCELLED }
    static final class Snapshot {
        final String id, label, message;
        final Phase phase;
        final long bytes, expected;
        Snapshot(String id, String label, Phase phase, String message, long bytes, long expected) {
            this.id = id; this.label = label; this.phase = phase; this.message = message;
            this.bytes = bytes; this.expected = expected;
        }
        boolean active() { return phase == Phase.QUEUED || running(); }
        boolean running() { return phase == Phase.RUNNING || phase == Phase.CANCELLING; }
        int percent() {
            return phase == Phase.COMPLETE ? 100
                    : expected > 0 ? (int) Math.min(99, Math.max(0, bytes * 100.0 / expected)) : 0;
        }
    }
    static final class Work<T> {
        final String id;
        final T value;
        Work(String id, T value) { this.id = id; this.value = value; }
    }
    private static final class Entry<T> {
        T value;
        Snapshot state;
        Entry(T value, Snapshot state) { this.value = value; this.state = state; }
    }
    private final LinkedHashMap<String, Entry<T>> entries = new LinkedHashMap<>();
    private final int parallel, capacity, history;
    private long revision;
    DownloadQueue(int parallel, int capacity, int history) {
        if (parallel < 1 || capacity < parallel || history < capacity) {
            throw new IllegalArgumentException("Límites de cola inválidos.");
        }
        this.parallel = parallel; this.capacity = capacity; this.history = history;
    }
    synchronized void add(String id, String label, T value) {
        if (id == null || value == null || entries.containsKey(id)) {
            throw new IllegalArgumentException("Petición duplicada o inválida.");
        }
        if (activeCount() >= capacity) {
            throw new IllegalStateException(Messages.ref("queue_full", capacity));
        }
        entries.put(id, new Entry<>(value,
                new Snapshot(id, label, Phase.QUEUED, Messages.ref("queue_waiting"), 0, -1)));
        revision++; trim();
    }
    synchronized Work<T> takeNext() {
        if (runningCount() >= parallel) { return null; }
        for (Entry<T> entry : entries.values()) {
            if (entry.state.phase == Phase.QUEUED) {
                Snapshot s = entry.state;
                entry.state = new Snapshot(s.id, s.label, Phase.RUNNING, Messages.ref("queue_starting"), 0, -1);
                revision++;
                return new Work<>(s.id, entry.value);
            }
        }
        return null;
    }
    synchronized boolean cancel(String id, String reason) {
        Entry<T> entry = entries.get(id);
        if (entry == null || !entry.state.active() || entry.state.phase == Phase.CANCELLING) { return false; }
        Snapshot s = entry.state;
        Phase phase = s.phase == Phase.QUEUED ? Phase.CANCELLED : Phase.CANCELLING;
        entry.state = new Snapshot(s.id, s.label, phase, reason, s.bytes, s.expected);
        if (phase == Phase.CANCELLED) { entry.value = null; }
        revision++; return true;
    }
    synchronized void progress(String id, String message, long bytes, long expected) {
        Entry<T> entry = entries.get(id);
        if (entry == null || entry.state.phase != Phase.RUNNING) { return; }
        Snapshot s = entry.state;
        entry.state = new Snapshot(s.id, s.label, s.phase, message, bytes, expected);
        revision++;
    }
    synchronized void finish(String id, String message, boolean success, boolean cancelled) {
        Entry<T> entry = entries.get(id);
        if (entry == null || !entry.state.active()) { return; }
        Snapshot s = entry.state;
        Phase phase = cancelled || s.phase == Phase.CANCELLING ? Phase.CANCELLED
                : success ? Phase.COMPLETE : Phase.FAILED;
        entry.state = new Snapshot(s.id, s.label, phase, message, s.bytes, s.expected);
        entry.value = null;
        revision++; trim();
    }
    synchronized void restore(Snapshot state) {
        if (entries.containsKey(state.id)) { return; }
        if (state.active()) {
            state = new Snapshot(state.id, state.label, Phase.FAILED,
                    Messages.ref("queue_interrupted"), 0, -1);
        }
        entries.put(state.id, new Entry<T>(null, state)); revision++; trim();
    }
    synchronized List<Snapshot> snapshots() {
        List<Snapshot> result = new ArrayList<>();
        for (Entry<T> entry : entries.values()) { result.add(entry.state); }
        return result;
    }
    synchronized long revision() { return revision; }
    synchronized int activeCount() {
        int count = 0;
        for (Entry<T> entry : entries.values()) { if (entry.state.active()) { count++; } }
        return count;
    }
    synchronized int runningCount() {
        int count = 0;
        for (Entry<T> entry : entries.values()) { if (entry.state.running()) { count++; } }
        return count;
    }
    synchronized void clearCompleted() {
        Iterator<Entry<T>> it = entries.values().iterator();
        while (it.hasNext()) { if (!it.next().state.active()) { it.remove(); revision++; } }
    }
    private void trim() {
        Iterator<Entry<T>> it = entries.values().iterator();
        while (entries.size() > history && it.hasNext()) {
            if (!it.next().state.active()) { it.remove(); }
        }
    }
}
