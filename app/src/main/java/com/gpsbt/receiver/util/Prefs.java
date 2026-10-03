package com.gpsbt.receiver.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.gpsbt.receiver.bt.ConnectionMode;

/** 设置持久化。 */
public final class Prefs {

    public static final String DEFAULT_UUID = "00001101-0000-1000-8000-00805F9B34FB";
    public static final String DEFAULT_SERVICE_NAME = "GPS蓝牙传输";

    private static final String NAME = "gps_receiver_prefs";
    private static final String KEY_MODE = "mode";
    private static final String KEY_LAST_MAC = "last_device_mac";
    private static final String KEY_AUTO_START = "auto_start_boot";
    private static final String KEY_MOCK_INJECTION = "mock_injection";

    /** 开机自启默认关闭：用户不开启就不自动运行。 */
    public static final boolean DEFAULT_AUTO_START = false;

    private static volatile Prefs instance;
    private final SharedPreferences prefs;

    private Prefs(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Prefs get(Context context) {
        Prefs local = instance;
        if (local == null) {
            synchronized (Prefs.class) {
                local = instance;
                if (local == null) {
                    local = new Prefs(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public ConnectionMode getMode() {
        String name = prefs.getString(KEY_MODE, ConnectionMode.CLIENT.name());
        try {
            return ConnectionMode.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ConnectionMode.CLIENT;
        }
    }

    public void setMode(ConnectionMode mode) {
        prefs.edit().putString(KEY_MODE, (mode == null ? ConnectionMode.CLIENT : mode).name()).apply();
    }

    public String getLastDeviceMac() {
        return prefs.getString(KEY_LAST_MAC, null);
    }

    public void setLastDeviceMac(String mac) {
        prefs.edit().putString(KEY_LAST_MAC, mac).apply();
    }

    /** 开机是否自动开始接收，默认关闭。 */
    public boolean isAutoStartOnBoot() {
        return prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START);
    }

    public void setAutoStartOnBoot(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_START, value).apply();
    }

    /** 是否把收到的定位注入系统（模拟位置），供平板上其它 App 使用。默认关闭。 */
    public boolean isMockInjectionEnabled() {
        return prefs.getBoolean(KEY_MOCK_INJECTION, false);
    }

    public void setMockInjectionEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_MOCK_INJECTION, value).apply();
    }
}
