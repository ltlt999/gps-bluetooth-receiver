package com.gpsbt.receiver.util;

import java.util.Locale;

/** 展示格式化工具。 */
public final class Formatters {

    private Formatters() {
    }

    /** 纬度：31.2304° N。 */
    public static String latitude(double value) {
        return coordinate(value, "N", "S");
    }

    /** 经度：121.4737° E。 */
    public static String longitude(double value) {
        return coordinate(value, "E", "W");
    }

    private static String coordinate(double value, String positive, String negative) {
        if (Double.isNaN(value)) {
            return "--";
        }
        return String.format(Locale.US, "%.6f° %s", Math.abs(value), value >= 0 ? positive : negative);
    }

    /** 距今多少秒；不足 1 秒显示“刚刚”。 */
    public static String ago(long thenMillis, long nowMillis) {
        if (thenMillis == 0) {
            return "--";
        }
        long seconds = Math.max(0, (nowMillis - thenMillis) / 1000L);
        if (seconds <= 0) {
            return "刚刚";
        }
        return seconds + " 秒前";
    }

    /** 连接时长：1小时2分 / 3分04秒 / 5秒。 */
    public static String duration(long millis) {
        if (millis < 0) {
            return "--";
        }
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d小时%02d分", hours, minutes);
        }
        if (minutes > 0) {
            return String.format(Locale.US, "%d分%02d秒", minutes, seconds);
        }
        return seconds + "秒";
    }

    /** 一位小数；非法值显示 --。 */
    public static String oneDecimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "--";
        }
        return String.format(Locale.US, "%.1f", value);
    }

    /** 字节数：123 B / 4.5 KB / 1.2 MB。 */
    public static String bytes(long value) {
        if (value < 1024L) {
            return value + " B";
        }
        double kb = value / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        return String.format(Locale.US, "%.2f MB", kb / 1024.0);
    }
}
