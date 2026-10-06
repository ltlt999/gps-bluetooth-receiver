package com.gpsbt.receiver.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.gpsbt.receiver.MainActivity;
import com.gpsbt.receiver.R;
import com.gpsbt.receiver.boot.BootNotifier;
import com.gpsbt.receiver.bt.BluetoothLinkManager;
import com.gpsbt.receiver.bt.BtState;
import com.gpsbt.receiver.bt.ConnectionMode;
import com.gpsbt.receiver.nmea.NmeaChecksum;
import com.gpsbt.receiver.nmea.NmeaParser;
import com.gpsbt.receiver.state.LogBus;
import com.gpsbt.receiver.state.ReceiverState;
import com.gpsbt.receiver.util.BootLog;
import com.gpsbt.receiver.util.Formatters;
import com.gpsbt.receiver.util.Prefs;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 前台服务：蓝牙 SPP 接收 → NMEA 解析 → 状态分发。
 * 使用 START_NOT_STICKY，被杀后不自动重启；是否重新开始由用户决定。
 */
public class ReceiverService extends Service {

    public static final String ACTION_START = "com.gpsbt.receiver.action.START";
    public static final String ACTION_STOP = "com.gpsbt.receiver.action.STOP";
    public static final String EXTRA_MODE = "com.gpsbt.receiver.extra.MODE";
    public static final String EXTRA_DEVICE_MAC = "com.gpsbt.receiver.extra.DEVICE_MAC";
    public static final String EXTRA_FROM_BOOT = "com.gpsbt.receiver.extra.FROM_BOOT";
    public static final String EXTRA_FROM_UI = "com.gpsbt.receiver.extra.FROM_UI";

    private static final int NOTIFICATION_ID = 2001;
    private static final String CHANNEL_ID = "gps_receiver_status";

    /** 开机后等待蓝牙开启的最大时长（每 2 秒检查一次）。 */
    private static final int BT_WAIT_MAX_ATTEMPTS = 60;

    /** 服务是否正在运行，避免重复启动。 */
    private static volatile boolean running;

    private ReceiverState state;
    private BluetoothLinkManager link;
    private NmeaParser parser;
    private MockLocationInjector injector;
    private long lastNotificationAt;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean fromBoot;
    private volatile ConnectionMode pendingMode;
    private volatile String pendingMac;
    private ScheduledExecutorService retryExecutor;
    private volatile int retryAttempts;
    private ScheduledExecutorService watchdogExecutor;
    private volatile long watchdogBytes = -1;
    private volatile long watchdogDataAt;
    private volatile long connectedAtMs;
    private volatile boolean noDataHintShown;
    /** 连续「重连后仍无数据」的轮数，用于升级提示。 */
    private volatile int emptyRounds;

    /** 已连接状态下超过该时长没有任何数据到达，视为链路假死，强制重连。 */
    private static final long DATA_TIMEOUT_MS = 30000L;

    /** 重连间隔：2、4、6…秒，封顶 30 秒。 */
    static long retryDelaySeconds(int attempts) {
        return Math.min(30L, 2L * Math.max(1, attempts));
    }

    public static void start(Context context, ConnectionMode mode, String deviceMac) {
        Intent intent = new Intent(context, ReceiverService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_MODE, (mode == null ? ConnectionMode.CLIENT : mode).name());
        intent.putExtra(EXTRA_DEVICE_MAC, deviceMac);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    /** 开机自启入口：按上次的模式与设备自动开始接收，蓝牙未就绪时由服务内部等待。 */
    public static void startFromBoot(Context context) {
        Prefs prefs = Prefs.get(context);
        BootLog.record(context, "启动服务（模式：" + prefs.getMode() + "）");
        Intent intent = new Intent(context, ReceiverService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_FROM_BOOT, true);
        intent.putExtra(EXTRA_MODE, prefs.getMode().name());
        intent.putExtra(EXTRA_DEVICE_MAC, prefs.getLastDeviceMac());
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    /**
     * 界面启动通道（开机自启的兜底路径）：从正在显示的界面启动属前台操作，
     * 最可靠。蓝牙未开启时直接提示，不做开机等待。
     */
    public static void startFromUi(Context context, ConnectionMode mode, String deviceMac) {
        Intent intent = new Intent(context, ReceiverService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_FROM_UI, true);
        intent.putExtra(EXTRA_MODE, (mode == null ? ConnectionMode.CLIENT : mode).name());
        intent.putExtra(EXTRA_DEVICE_MAC, deviceMac);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ReceiverService.class);
        intent.setAction(ACTION_STOP);
        context.getApplicationContext().startService(intent);
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        state = ReceiverState.get();
        injector = new MockLocationInjector(this, new MockLocationInjector.Listener() {
            @Override
            public void onInjectionError(String message) {
                LogBus.get().log(LogBus.Level.ERROR, "位置注入：" + message);
                state.setStatusText("位置注入失败：" + message);
                notifyStateChanged();
            }

            @Override
            public void onHoldChanged(boolean holding) {
                if (holding) {
                    LogBus.get().log(LogBus.Level.WARN, "发送端暂无定位，保持最后位置注入");
                } else {
                    LogBus.get().log(LogBus.Level.INFO, "定位已恢复，继续使用实时位置");
                }
            }
        });
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopReceiving();
            return START_NOT_STICKY;
        }
        ConnectionMode mode;
        try {
            mode = ConnectionMode.valueOf(intent.getStringExtra(EXTRA_MODE));
        } catch (RuntimeException e) {
            mode = ConnectionMode.CLIENT;
        }
        String mac = intent.getStringExtra(EXTRA_DEVICE_MAC);
        fromBoot = intent.getBooleanExtra(EXTRA_FROM_BOOT, false);
        if (mode == ConnectionMode.CLIENT && (mac == null || mac.isEmpty())) {
            state.setPhase(ReceiverState.Phase.FAILED);
            state.setStatusText(getString(R.string.paired_devices_empty));
            LogBus.get().log(LogBus.Level.ERROR, "未选择发送端设备");
            notifyStateChanged();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Android 12+ 在刚停掉一个前台服务后短时间内不允许再启动（"频繁启停"限制），
        // 用户连续点「停止→开始」时会被拒。失败一次后等 1.5 秒重试；重试仍失败才标失败。
        final ConnectionMode startMode = mode;
        final String startMac = mac;
        try {
            goForeground();
        } catch (RuntimeException first) {
            LogBus.get().log(LogBus.Level.WARN,
                    "前台服务启动被拒绝，1.5 秒后重试：" + first.getMessage());
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        goForeground();
                    } catch (RuntimeException retry) {
                        LogBus.get().log(LogBus.Level.ERROR,
                                "前台服务启动重试仍失败：" + retry.getMessage());
                        state.setPhase(ReceiverState.Phase.FAILED);
                        state.setStatusText("无法启动后台接收：" + retry.getMessage());
                        notifyStateChanged();
                        stopSelf();
                        return;
                    }
                    startAfterForeground(startMode, startMac);
                }
            }, 1500L);
            return START_NOT_STICKY;
        }
        return startAfterForeground(startMode, startMac);
    }

    /** 跑实际流水线：先调用此方法启动前台服务；被 FGS 启动被拒重试时也走这里。 */
    private int startAfterForeground(ConnectionMode mode, String mac) {
        running = true;
        startPipeline(mode, mac);
        // 进程被系统杀掉后带着原参数重启，接收自动恢复（正常 stopSelf 不会触发重启）
        return START_REDELIVER_INTENT;
    }

    private void startPipeline(ConnectionMode mode, String mac) {
        // 可能是运行中重新选择设备后的重启：先清掉旧链路与重连任务
        stopRetry();
        stopWatchdog();
        if (link != null) {
            link.stop();
            link = null;
        }
        parser = null;

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            failBluetooth();
            return;
        }

        state.resetForStart(mode);
        state.setStartedAt(System.currentTimeMillis());
        pendingMode = mode;
        pendingMac = mac;
        emptyRounds = 0;
        startWatchdog();
        // 位置注入：用户开启且已授权（模拟位置应用）时，把收到的定位提供给其它 App
        if (Prefs.get(this).isMockInjectionEnabled()) {
            injector.setHoldLastPosition(Prefs.get(this).isMockHoldLastEnabled());
            injector.start();
        } else {
            injector.stop();
        }

        if (!adapter.isEnabled()) {
            if (!fromBoot) {
                failBluetooth();
                return;
            }
            // 开机自启：蓝牙栈可能尚未就绪，等待而非直接放弃
            state.setPhase(ReceiverState.Phase.WAITING);
            state.setStatusText(getString(R.string.wait_bluetooth));
            LogBus.get().log(LogBus.Level.INFO, getString(R.string.wait_bluetooth));
            notifyStateChanged();
            refreshNotification();
            waitForBluetooth(adapter);
            return;
        }
        if (fromBoot) {
            // 刚开机时蓝牙栈可能还没完全就绪，立刻连接容易建立「半死」的 RFCOMM 会话
            // （两端都显示已连接但数据不通）。等 15 秒再连，成功率明显更高。
            state.setPhase(ReceiverState.Phase.WAITING);
            state.setStatusText("开机自启：等待蓝牙就绪…");
            LogBus.get().log(LogBus.Level.INFO, "开机自启：15 秒后开始连接（等待蓝牙栈就绪）");
            notifyStateChanged();
            refreshNotification();
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!running || link != null) {
                        return;
                    }
                    BluetoothAdapter current = BluetoothAdapter.getDefaultAdapter();
                    if (current == null) {
                        failBluetooth();
                        return;
                    }
                    if (!current.isEnabled()) {
                        waitForBluetooth(current);
                        return;
                    }
                    startTransmission(current, pendingMode, pendingMac);
                }
            }, 15000L);
            return;
        }
        startTransmission(adapter, mode, mac);
    }

    private void failBluetooth() {
        state.setPhase(ReceiverState.Phase.FAILED);
        state.setStatusText(getString(R.string.bluetooth_unavailable));
        LogBus.get().log(LogBus.Level.ERROR, getString(R.string.bluetooth_unavailable));
        notifyStateChanged();
        stopSelfSafely();
    }

    /** 蓝牙尚未开启时的开机等待，最多等 120 秒；期间用户手动停止则退出。 */
    private void waitForBluetooth(final BluetoothAdapter adapter) {
        final ScheduledExecutorService waiter = Executors.newSingleThreadScheduledExecutor();
        waiter.scheduleWithFixedDelay(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (!running) {
                    waiter.shutdownNow();
                    return;
                }
                attempts++;
                if (adapter.isEnabled()) {
                    waiter.shutdownNow();
                    startTransmission(adapter, pendingMode, pendingMac);
                    return;
                }
                if (attempts >= BT_WAIT_MAX_ATTEMPTS) {
                    waiter.shutdownNow();
                    LogBus.get().log(LogBus.Level.WARN, "蓝牙未开启，自动接收未启动");
                    BootNotifier.notifyBlocked(ReceiverService.this,
                            getString(R.string.boot_reason_bluetooth_off));
                    stopSelfSafely();
                }
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private void startTransmission(BluetoothAdapter adapter, ConnectionMode mode, String mac) {
        if (link != null) {
            link.stop();
            link = null;
        }
        parser = new NmeaParser(nmeaListener);
        link = new BluetoothLinkManager(adapter, null,
                BluetoothLinkManager.SPP_UUID, linkListener);

        if (mode == ConnectionMode.SERVER) {
            state.setPhase(ReceiverState.Phase.WAITING);
            state.setStatusText(getString(R.string.status_waiting));
            link.startServer();
        } else {
            state.setPhase(ReceiverState.Phase.CONNECTING);
            state.setStatusText(getString(R.string.status_connecting));
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(mac);
            } catch (IllegalArgumentException e) {
                state.setPhase(ReceiverState.Phase.FAILED);
                state.setStatusText("设备地址无效：" + mac);
                LogBus.get().log(LogBus.Level.ERROR, "设备地址无效：" + mac);
                notifyStateChanged();
                stopSelfSafely();
                return;
            }
            link.startClient(device);
        }
        notifyStateChanged();
        LogBus.get().log(LogBus.Level.INFO, "接收已启动，模式："
                + (mode == ConnectionMode.SERVER ? "等待发送端连入" : "主动连接发送端"));
        refreshNotification();
    }

    /** 读线程回调：字节流 → 解析器。 */
    private final BluetoothLinkManager.Listener linkListener =
            new BluetoothLinkManager.Listener() {
                @Override
                public void onState(BtState btState, String message) {
                    if (btState == BtState.WAITING) {
                        state.setPhase(ReceiverState.Phase.WAITING);
                    } else if (btState == BtState.CONNECTING) {
                        state.setPhase(ReceiverState.Phase.CONNECTING);
                    } else if (btState == BtState.CONNECTED) {
                        state.setPhase(ReceiverState.Phase.CONNECTED);
                    }
                    state.setStatusText(message);
                    LogBus.get().log(levelOf(btState), "蓝牙：" + message);
                    notifyStateChanged();
                    refreshNotification();
                    if (btState == BtState.FAILED) {
                        // 不停止服务：持续自动重连，直到用户手动停止或连上为止
                        scheduleReconnect(message);
                    }
                }

                @Override
                public void onConnected(String remoteName, String remoteMac) {
                    retryAttempts = 0;
                    watchdogBytes = -1;
                    watchdogDataAt = System.currentTimeMillis();
                    connectedAtMs = System.currentTimeMillis();
                    noDataHintShown = false;
                    state.setConnectedDevice(new ReceiverState.Device(remoteName, remoteMac));
                    state.setPhase(ReceiverState.Phase.CONNECTED);
                    BootLog.record(ReceiverService.this, "已连接 " + remoteName);
                    LogBus.get().log(LogBus.Level.INFO,
                            "已连接 " + remoteName + "（" + remoteMac + "）");
                    notifyStateChanged();
                    refreshNotification();
                }

                @Override
                public void onDisconnected(String message) {
                    LogBus.get().log(LogBus.Level.WARN, "蓝牙已断开：" + message);
                    // 不停止服务：持续自动重连
                    scheduleReconnect(message);
                }

                @Override
                public void onData(byte[] buffer, int length) {
                    state.setBytes(state.bytes() + length);
                    state.setLastSentenceAt(System.currentTimeMillis());
                    if (parser != null) {
                        parser.feed(buffer, 0, length);
                    }
                }
            };

    /** 读线程回调：解析结果 → 状态。 */
    private final NmeaParser.Listener nmeaListener = new NmeaParser.Listener() {
        @Override
        public void onFix(NmeaParser.Fix fix) {
            if (noDataHintShown) {
                noDataHintShown = false;
                state.setStatusText("已连接，正在接收数据");
            }
            state.setFix(fix);
            if (Prefs.get(ReceiverService.this).isMockInjectionEnabled()) {
                injector.inject(fix);
            }
            state.notifyChanged();
        }

        @Override
        public void onSatellites(List<NmeaParser.Sat> satellites, int inView) {
            state.setSatellites(satellites, inView);
            state.notifyChanged();
        }

        @Override
        public void onSentence(String sentence) {
            state.setSentences(state.sentences() + 1);
            // 卫星语句（GSV/GSA）单独分类，便于在日志里与普通信息分开查看
            String upper = sentence.toUpperCase(java.util.Locale.US);
            LogBus.Level level = upper.contains("GSV") || upper.contains("GSA")
                    ? LogBus.Level.SAT : LogBus.Level.DATA;
            LogBus.get().log(level, sentence);
            // 高频语句不需要每条都刷新界面，交给 onFix / onSatellites 触发
        }

        @Override
        public void onInvalidLine(String raw) {
            if (raw != null && raw.trim().startsWith("$") && !NmeaChecksum.isValid(raw.trim())) {
                state.setBadChecksum(state.badChecksum() + 1);
                LogBus.get().log(LogBus.Level.WARN, "校验失败：" + raw.trim());
            }
        }
    };

    private static LogBus.Level levelOf(BtState state) {
        if (state == BtState.FAILED) {
            return LogBus.Level.ERROR;
        }
        if (state == BtState.DISCONNECTED) {
            return LogBus.Level.WARN;
        }
        return LogBus.Level.INFO;
    }

    /**
     * 连接失败或断开后的自动重连：间隔递增（封顶 30 秒），连上后清零。
     * 服务保持运行，只有用户手动停止才会真正退出。
     */
    private void scheduleReconnect(final String reason) {
        if (!running) {
            return;
        }
        stopRetry();
        retryAttempts++;
        // 反复「连上却收不到数据」时额外延长间隔，给蓝牙栈足够时间释放旧会话
        final long delay = Math.min(30L,
                retryDelaySeconds(retryAttempts) + 5L * emptyRounds);
        state.setPhase(ReceiverState.Phase.WAITING);
        state.setStatusText(reason + "，" + delay + " 秒后重连（第 " + retryAttempts + " 次）");
        LogBus.get().log(LogBus.Level.WARN, reason + "，" + delay + " 秒后重连");
        notifyStateChanged();
        refreshNotification();
        retryExecutor = Executors.newSingleThreadScheduledExecutor();
        retryExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) {
                    scheduleReconnect("设备不支持蓝牙");
                    return;
                }
                if (!adapter.isEnabled()) {
                    scheduleReconnect("蓝牙未开启");
                    return;
                }
                startTransmission(adapter, pendingMode, pendingMac);
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void stopRetry() {
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
            retryExecutor = null;
        }
    }

    /** 链路假死看门狗：已连接但持续无数据到达（蓝牙假死常见现象）时强制重连。 */
    private void startWatchdog() {
        stopWatchdog();
        watchdogBytes = -1;
        watchdogDataAt = System.currentTimeMillis();
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor();
        watchdogExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (state.phase() != ReceiverState.Phase.CONNECTED) {
                    watchdogDataAt = now;
                    return;
                }
                // 已连接但一直没有定位数据：大概率是发送端 GPS 尚未定位，
                // 链路靠 ZDA 保活帧维持。给出明确提示，避免看起来像坏了
                if (state.fix() == null && !noDataHintShown
                        && now - connectedAtMs > 10000L) {
                    noDataHintShown = true;
                    state.setStatusText("已连接，但发送端暂无定位数据（等待 GPS，"
                            + "或让发送端开启「允许网络定位」）");
                    LogBus.get().log(LogBus.Level.INFO,
                            "发送端已连接但未发送定位数据（可能 GPS 尚未定位，仅有保活帧）");
                    notifyStateChanged();
                }
                long bytes = state.bytes();
                if (bytes != watchdogBytes) {
                    watchdogBytes = bytes;
                    watchdogDataAt = now;
                    emptyRounds = 0;
                    return;
                }
                if (now - watchdogDataAt > DATA_TIMEOUT_MS) {
                    emptyRounds++;
                    LogBus.get().log(LogBus.Level.WARN,
                            "已连接但超过 30 秒无数据，判定链路假死，强制重连（第 "
                                    + emptyRounds + " 次）");
                    if (emptyRounds >= 3) {
                        // 反复重连都收不到数据：多半是发送端侧链路异常，给出可操作建议
                        LogBus.get().log(LogBus.Level.WARN,
                                "连续多次重连均无数据：建议在发送端「停止传输」后重新开始");
                        state.setStatusText("连续重连无数据：请在发送端停止后重新开始传输");
                        notifyStateChanged();
                    }
                    scheduleReconnect("连接无数据超时");
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void stopWatchdog() {
        if (watchdogExecutor != null) {
            watchdogExecutor.shutdownNow();
            watchdogExecutor = null;
        }
    }

    private void notifyStateChanged() {
        if (state != null) {
            state.notifyChanged();
        }
    }

    // ---------- 通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_receive_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_receive_desc));
        channel.setShowBadge(false);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void goForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void refreshNotification() {
        long now = System.currentTimeMillis();
        if (now - lastNotificationAt < 1000L) {
            return;
        }
        lastNotificationAt = now;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        ReceiverState.Phase phase = state.phase();
        String title;
        switch (phase) {
            case CONNECTED:
                title = getString(R.string.notif_receiving);
                break;
            case WAITING:
                title = getString(R.string.notif_waiting);
                break;
            case CONNECTING:
                title = getString(R.string.notif_connecting);
                break;
            case FAILED:
                title = getString(R.string.status_failed);
                break;
            default:
                title = getString(R.string.app_name);
                break;
        }

        StringBuilder text = new StringBuilder();
        ReceiverState.Device device = state.connectedDevice();
        if (device != null) {
            text.append(getString(R.string.notif_connected_to, device.name)).append("　");
        } else {
            text.append(state.statusText()).append("　");
        }
        NmeaParser.Fix fix = state.fix();
        if (fix != null && fix.valid) {
            text.append(Formatters.latitude(fix.latitude)).append("　")
                    .append(Formatters.longitude(fix.longitude));
        } else if (phase == ReceiverState.Phase.CONNECTED) {
            text.append(getString(R.string.notif_no_data));
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, ReceiverService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(content)
                .addAction(0, getString(R.string.notif_action_stop), stopAction)
                .build();
    }

    // ---------- 停止 ----------

    private void stopReceiving() {
        LogBus.get().log(LogBus.Level.INFO, "用户停止接收");
        state.setPhase(ReceiverState.Phase.IDLE);
        state.setStatusText("");
        state.resetForStop();
        notifyStateChanged();
        stopSelfSafely();
    }

    private synchronized void stopSelfSafely() {
        running = false;
        stopRetry();
        stopWatchdog();
        if (injector != null) {
            injector.stop();
        }
        if (link != null) {
            link.stop();
            link = null;
        }
        parser = null;
        state.resetForStop();
        notifyStateChanged();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopSelfSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
