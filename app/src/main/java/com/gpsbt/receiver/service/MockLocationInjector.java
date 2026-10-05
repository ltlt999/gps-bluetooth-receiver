package com.gpsbt.receiver.service;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.gpsbt.receiver.nmea.NmeaParser;

/**
 * 把蓝牙收到的定位注入系统（模拟位置 / Mock Location）。
 * 需要用户在开发者选项中选择本应用为「模拟位置信息应用」；注入后平板上
 * 其它 App（地图等）将使用发送端的位置，停止接收后自动移除、恢复真实 GPS。
 * 卫星信息属于芯片级 GnssStatus，无法伪造，其它 App 的卫星列表仍为本机实际状态。
 *
 * 「断流保持」开启时：发送端暂时没有定位（只发保活帧）期间，继续以最后坐标
 * 刷新注入（时间戳持续更新），避免地图回落到本机网络定位。
 */
public final class MockLocationInjector {

    public interface Listener {
        void onInjectionError(String message);

        /** 进入/退出「断流保持最后位置」状态。 */
        void onHoldChanged(boolean holding);
    }

    /** 断流后重新注入最后位置的周期。 */
    private static final long HOLD_REFRESH_MS = 1000L;
    /** 距上次注入超过该时长才判定为断流。 */
    private static final long HOLD_AFTER_MS = 2500L;

    private final LocationManager locationManager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean providerAdded;
    private boolean failed;
    private boolean holdLast;
    private boolean holding;
    private NmeaParser.Fix lastFix;
    private long lastInjectAt;

    private final Runnable holdLoop = new Runnable() {
        @Override
        public void run() {
            if (!providerAdded) {
                return;
            }
            long now = System.currentTimeMillis();
            if (holdLast && lastFix != null && now - lastInjectAt > HOLD_AFTER_MS) {
                if (!holding) {
                    holding = true;
                    notifyHold(true);
                }
                injectLocation(lastFix);
            }
            main.postDelayed(this, HOLD_REFRESH_MS);
        }
    };

    public MockLocationInjector(Context context, Listener listener) {
        locationManager = (LocationManager) context.getApplicationContext()
                .getSystemService(Context.LOCATION_SERVICE);
        this.listener = listener;
    }

    /** 是否在断流时保持最后位置。 */
    public synchronized void setHoldLastPosition(boolean value) {
        holdLast = value;
        if (!value && holding) {
            holding = false;
            notifyHold(false);
        }
    }

    /** 添加 GPS 测试提供者。返回 false 表示被系统拒绝（通常是未选为模拟位置应用）。 */
    public synchronized boolean start() {
        failed = false;
        holding = false;
        lastFix = null;
        if (locationManager == null) {
            reportError("设备不支持定位服务，无法注入");
            return false;
        }
        try {
            locationManager.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            providerAdded = true;
        } catch (IllegalArgumentException e) {
            // 之前添加过尚未移除，视为已就绪
            providerAdded = true;
        } catch (SecurityException e) {
            reportError("请在开发者选项「选择模拟位置信息应用」中选择本应用");
            return false;
        }
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
        } catch (SecurityException e) {
            reportError("请在开发者选项「选择模拟位置信息应用」中选择本应用");
            return false;
        }
        main.removeCallbacks(holdLoop);
        main.postDelayed(holdLoop, HOLD_REFRESH_MS);
        return true;
    }

    /** 移除测试提供者，恢复平板真实 GPS。 */
    public synchronized void stop() {
        main.removeCallbacks(holdLoop);
        holding = false;
        lastFix = null;
        if (!providerAdded || locationManager == null) {
            providerAdded = false;
            return;
        }
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 已被系统清理，忽略
        }
        providerAdded = false;
        failed = false;
    }

    /** 注入一次定位；仅注入有效定位，精度由 HDOP 估算。 */
    public synchronized void inject(NmeaParser.Fix fix) {
        if (!providerAdded || failed || locationManager == null || !fix.valid) {
            return;
        }
        if (Double.isNaN(fix.latitude) || Double.isNaN(fix.longitude)) {
            return;
        }
        if (holding) {
            holding = false;
            notifyHold(false);
        }
        lastFix = copy(fix);
        injectLocation(lastFix);
    }

    /** 按给定定位写一次测试提供者位置（时间戳刷新为当前时刻）。 */
    private synchronized void injectLocation(NmeaParser.Fix fix) {
        if (!providerAdded || failed || locationManager == null) {
            return;
        }
        try {
            Location location = new Location(LocationManager.GPS_PROVIDER);
            location.setLatitude(fix.latitude);
            location.setLongitude(fix.longitude);
            if (!Double.isNaN(fix.altitudeM)) {
                location.setAltitude(fix.altitudeM);
            }
            if (!Double.isNaN(fix.speedMps)) {
                location.setSpeed((float) fix.speedMps);
            }
            if (!Double.isNaN(fix.courseDeg)) {
                location.setBearing((float) fix.courseDeg);
            }
            location.setAccuracy(estimateAccuracyM(fix.hdop));
            location.setTime(System.currentTimeMillis());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
            lastInjectAt = System.currentTimeMillis();
        } catch (SecurityException e) {
            failed = true;
            reportError("注入被系统拒绝，已停止：" + e.getMessage());
        }
    }

    /** 定位快照副本（Fix 为可变对象，必须拷贝保存）。 */
    private static NmeaParser.Fix copy(NmeaParser.Fix fix) {
        NmeaParser.Fix copy = new NmeaParser.Fix();
        copy.valid = fix.valid;
        copy.latitude = fix.latitude;
        copy.longitude = fix.longitude;
        copy.altitudeM = fix.altitudeM;
        copy.speedMps = fix.speedMps;
        copy.courseDeg = fix.courseDeg;
        copy.hdop = fix.hdop;
        copy.satellitesUsed = fix.satellitesUsed;
        copy.satellitesInView = fix.satellitesInView;
        copy.utcMillis = fix.utcMillis;
        return copy;
    }

    /** HDOP × 5 ≈ 米级精度，异常时按 10 米兜底。 */
    private static float estimateAccuracyM(float hdop) {
        if (Float.isNaN(hdop) || hdop <= 0f) {
            return 10f;
        }
        return Math.max(1f, Math.min(50f, hdop * 5f));
    }

    private void reportError(String message) {
        if (listener != null) {
            listener.onInjectionError(message);
        }
    }

    private void notifyHold(boolean holding) {
        if (listener != null) {
            listener.onHoldChanged(holding);
        }
    }
}

