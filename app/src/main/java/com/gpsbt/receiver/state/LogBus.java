package com.gpsbt.receiver.state;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** 内存日志总线：任意线程写，监听器回调固定在主线程执行。 */
public final class LogBus {

    public enum Level { INFO, WARN, ERROR, DATA }

    public static final class Entry {
        public final long time;
        public final Level level;
        public final String message;

        Entry(long time, Level level, String message) {
            this.time = time;
            this.level = level;
            this.message = message;
        }
    }

    public interface Listener {
        void onLog(Entry entry);
    }

    public static final int MAX_ENTRIES = 400;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile LogBus instance;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<Listener> listeners = new ArrayList<>();

    private LogBus() {
    }

    public static LogBus get() {
        LogBus local = instance;
        if (local == null) {
            synchronized (LogBus.class) {
                local = instance;
                if (local == null) {
                    local = new LogBus();
                    instance = local;
                }
            }
        }
        return local;
    }

    public synchronized void log(Level level, String message) {
        Entry entry = new Entry(System.currentTimeMillis(), level, message == null ? "" : message);
        if (entries.size() >= MAX_ENTRIES) {
            entries.pollFirst();
        }
        entries.addLast(entry);
        for (final Listener listener : snapshotListeners()) {
            // 监听器多为 UI，统一投递到主线程，避免后台线程日志导致跨线程崩溃
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    listener.onLog(entry);
                }
            });
        }
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized void clear() {
        entries.clear();
    }

    public void addListener(Listener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private List<Listener> snapshotListeners() {
        synchronized (listeners) {
            return new ArrayList<>(listeners);
        }
    }
}
