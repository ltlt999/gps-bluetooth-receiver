package com.gpsbt.receiver.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 开机自启的持久化记录。
 * 内存日志（LogBus）在进程被杀后即丢失，而开机自启的问题恰恰发生在进程存活期间之外，
 * 因此把关键节点落盘，用户打开应用即可看到开机时到底走到了哪一步。
 */
public final class BootLog {

    private static final String NAME = "gps_receiver_boot_log";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 12;

    private BootLog() {
    }

    /** 记录一条（最新在前）。 */
    public static void record(Context context, String message) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(NAME, Context.MODE_PRIVATE);
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            String line = stamp + "  " + message;
            String old = prefs.getString(KEY_ENTRIES, "");
            StringBuilder sb = new StringBuilder(line);
            if (old != null && !old.isEmpty()) {
                String[] lines = old.split("\n");
                int keep = Math.min(lines.length, MAX_ENTRIES - 1);
                for (int i = 0; i < keep; i++) {
                    sb.append('\n').append(lines[i]);
                }
            }
            prefs.edit().putString(KEY_ENTRIES, sb.toString()).apply();
        } catch (RuntimeException ignored) {
            // 记录失败不影响主流程
        }
    }

    /** 读取全部记录（最新在前）；无记录时返回空串。 */
    public static String read(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_ENTRIES, "");
        return value == null ? "" : value;
    }

    public static void clear(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_ENTRIES).apply();
    }
}
