package com.gpsbt.receiver.state;

import android.os.Handler;
import android.os.Looper;

import com.gpsbt.receiver.bt.ConnectionMode;
import com.gpsbt.receiver.nmea.NmeaParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 接收端全局状态：服务在后台线程写入，界面在主线程读取。
 * 任何变更通过 {@link Listener#onChanged} 在主线程通知，界面收到后整体刷新。
 */
public final class ReceiverState {

    public enum Phase { IDLE, WAITING, CONNECTING, CONNECTED, FAILED }

    public static final class Device {
        public final String name;
        public final String mac;

        public Device(String name, String mac) {
            this.name = name;
            this.mac = mac;
        }
    }

    public interface Listener {
        void onChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile ReceiverState instance;

    private final List<Listener> listeners = new ArrayList<>();

    private volatile Phase phase = Phase.IDLE;
    private volatile ConnectionMode mode = ConnectionMode.CLIENT;
    private volatile String statusText = "";
    private volatile Device connectedDevice;
    private volatile NmeaParser.Fix fix;
    private volatile List<NmeaParser.Sat> satellites = new ArrayList<>();
    private volatile int satellitesInView = -1;
    private volatile long sentences;
    private volatile long bytes;
    private volatile long badChecksum;
    private volatile long startedAt;
    private volatile long lastSentenceAt;
    private volatile long lastFixAt;

    private ReceiverState() {
    }

    public static ReceiverState get() {
        ReceiverState local = instance;
        if (local == null) {
            synchronized (ReceiverState.class) {
                local = instance;
                if (local == null) {
                    local = new ReceiverState();
                    instance = local;
                }
            }
        }
        return local;
    }

    // ---------- 读取 ----------

    public Phase phase() {
        return phase;
    }

    public ConnectionMode mode() {
        return mode;
    }

    public String statusText() {
        return statusText;
    }

    public Device connectedDevice() {
        return connectedDevice;
    }

    public NmeaParser.Fix fix() {
        return fix;
    }

    public List<NmeaParser.Sat> satellites() {
        return satellites;
    }

    public int satellitesInView() {
        return satellitesInView;
    }

    public long sentences() {
        return sentences;
    }

    public long bytes() {
        return bytes;
    }

    public long badChecksum() {
        return badChecksum;
    }

    public long startedAt() {
        return startedAt;
    }

    public long lastSentenceAt() {
        return lastSentenceAt;
    }

    public long lastFixAt() {
        return lastFixAt;
    }

    // ---------- 写入（服务调用） ----------

    public void setPhase(Phase value) {
        phase = value;
    }

    public void setMode(ConnectionMode value) {
        mode = value;
    }

    public void setStatusText(String value) {
        statusText = value == null ? "" : value;
    }

    public void setConnectedDevice(Device value) {
        connectedDevice = value;
    }

    public void setFix(NmeaParser.Fix value) {
        fix = value;
        lastFixAt = System.currentTimeMillis();
    }

    public void setSatellites(List<NmeaParser.Sat> value, int inView) {
        satellites = value == null ? new ArrayList<NmeaParser.Sat>() : value;
        satellitesInView = inView;
    }

    public void setSentences(long value) {
        sentences = value;
    }

    public void setBytes(long value) {
        bytes = value;
    }

    public void setBadChecksum(long value) {
        badChecksum = value;
    }

    public void setStartedAt(long value) {
        startedAt = value;
    }

    public void setLastSentenceAt(long value) {
        lastSentenceAt = value;
    }

    /** 服务停止时清理链路相关状态；phase 与 statusText 保留，让界面能显示最终结果（如失败原因）。 */
    public void resetForStop() {
        connectedDevice = null;
    }

    /** 复位全部状态（新一轮接收开始时）。 */
    public void resetForStart(ConnectionMode newMode) {
        phase = Phase.IDLE;
        mode = newMode;
        statusText = "";
        connectedDevice = null;
        fix = null;
        satellites = new ArrayList<>();
        satellitesInView = -1;
        sentences = 0;
        bytes = 0;
        badChecksum = 0;
        startedAt = 0;
        lastSentenceAt = 0;
        lastFixAt = 0;
    }

    // ---------- 变更通知 ----------

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

    /** 通知界面刷新；可在任意线程调用，回调在主线程执行。 */
    public void notifyChanged() {
        List<Listener> snapshot;
        synchronized (listeners) {
            snapshot = new ArrayList<>(listeners);
        }
        for (final Listener listener : snapshot) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    listener.onChanged();
                }
            });
        }
    }
}
