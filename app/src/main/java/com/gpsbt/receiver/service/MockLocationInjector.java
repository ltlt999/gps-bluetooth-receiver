package com.gpsbt.receiver.service;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import com.gpsbt.receiver.nmea.NmeaParser;

/**
 * 把蓝牙收到的定位注入系统（模拟位置 / Mock Location）。
 * 需要用户在开发者选项中选择本应用为「模拟位置信息应用」；注入后平板上
 * 其它 App（地图等）将使用发送端的位置，停止接收后自动移除、恢复真实 GPS。
 * 卫星信息属于芯片级 GnssStatus，无法伪造，其它 App 的卫星列表仍为本机实际状态。
 */
public final class MockLocationInjector {

    public interface ErrorListener {
        void onInjectionError(String message);
    }

    private final LocationManager locationManager;
    private final ErrorListener errorListener;
    private boolean providerAdded;
    private boolean failed;

    public MockLocationInjector(Context context, ErrorListener listener) {
        locationManager = (LocationManager) context.getApplicationContext()
                .getSystemService(Context.LOCATION_SERVICE);
        errorListener = listener;
    }

    /** 添加 GPS 测试提供者。返回 false 表示被系统拒绝（通常是未选为模拟位置应用）。 */
    public synchronized boolean start() {
        failed = false;
        if (locationManager == null) {
            report("设备不支持定位服务，无法注入");
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
            report("请在开发者选项「选择模拟位置信息应用」中选择本应用");
            return false;
        }
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
        } catch (SecurityException e) {
            report("请在开发者选项「选择模拟位置信息应用」中选择本应用");
            return false;
        }
        return true;
    }

    /** 移除测试提供者，恢复平板真实 GPS。 */
    public synchronized void stop() {
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
        } catch (SecurityException e) {
            failed = true;
            report("注入被系统拒绝，已停止：" + e.getMessage());
        }
    }

    /** HDOP × 5 ≈ 米级精度，异常时按 10 米兜底。 */
    private static float estimateAccuracyM(float hdop) {
        if (Float.isNaN(hdop) || hdop <= 0f) {
            return 10f;
        }
        return Math.max(1f, Math.min(50f, hdop * 5f));
    }

    private void report(String message) {
        if (errorListener != null) {
            errorListener.onInjectionError(message);
        }
    }
}
