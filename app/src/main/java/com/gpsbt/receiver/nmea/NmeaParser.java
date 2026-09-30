package com.gpsbt.receiver.nmea;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * 流式 NMEA 0183 解析器：把蓝牙字节流切成完整语句，校验后解析 GGA / RMC / GSA / GSV / VTG。
 * 支持增量输入（一条语句可能分多次到达）。
 */
public final class NmeaParser {

    public interface Listener {
        /** GGA / RMC / VTG 更新定位时回调。 */
        void onFix(Fix fix);

        /** 一组 GSV 分页接收完成时回调。 */
        void onSatellites(List<Sat> satellites, int inView);

        /** 收到一条校验和有效的语句（原始文本，不含行结束符）。 */
        void onSentence(String sentence);

        /** 收到无法解析或校验失败的内容。 */
        void onInvalidLine(String raw);
    }

    /** 最新定位快照。字段可能因语句缺失而是 NaN / -1。 */
    public static final class Fix {
        public boolean valid;
        public double latitude = Double.NaN;
        public double longitude = Double.NaN;
        public double altitudeM = Double.NaN;
        public double speedMps = Double.NaN;
        public double courseDeg = Double.NaN;
        public float hdop = Float.NaN;
        public int satellitesUsed = -1;
        public int satellitesInView = -1;
        /** 由语句中的 UTC 日期时间推算；推算不出时为 0。 */
        public long utcMillis;
    }

    public static final class Sat {
        public int prn;
        public float elevationDeg = Float.NaN;
        public float azimuthDeg = Float.NaN;
        public float snr = Float.NaN;
        public boolean used;
        /** 星座：GPS / BDS / GLN / GAL / QZSS / SBAS，无法判断时为 null。 */
        public String constellation;
    }

    private static final long MAX_BUFFER = 8192;

    private final Listener listener;
    private final StringBuilder buffer = new StringBuilder();

    /** 跨语句累积的定位状态：GGA 与 RMC 各自带一部分字段，合并后输出。 */
    private final Fix latest = new Fix();
    private final Set<Integer> usedPrns = new HashSet<>();
    private final List<Sat> pendingSatellites = new ArrayList<>();
    private int gsvTotalPages;
    private int gsvInView = -1;
    /** 当前正在处理的语句的 talker（GP/GL/GA/GB…）。 */
    private String currentTalker;
    /** 当前 GSV 块的 talker（GP/GL/GA/GB…），每块 GSV 对应一个星座。 */
    private String gsvTalker;
    /** RMC 提供的日期（ddMMyy），用于把 GGA 的时间补全成完整 UTC。 */
    private int lastDay = -1;
    private int lastMonth = -1;
    private int lastYear = -1;

    public NmeaParser(Listener listener) {
        this.listener = listener;
    }

    /** 收到新的字节流片段（任意切分位置）。 */
    public void feed(byte[] data, int offset, int length) {
        if (buffer.length() > MAX_BUFFER) {
            // 异常数据流保护：直接丢弃重来
            buffer.setLength(0);
        }
        String chunk = new String(data, offset, length,
                java.nio.charset.StandardCharsets.US_ASCII);
        buffer.append(chunk);
        int start = 0;
        while (true) {
            int lineEnd = indexOfLineEnd(start);
            if (lineEnd < 0) {
                buffer.delete(0, start);
                return;
            }
            String line = buffer.substring(start, lineEnd);
            start = skipLineEnd(lineEnd);
            handleLine(line);
        }
    }

    private int indexOfLineEnd(int from) {
        for (int i = Math.max(from, 0); i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }

    private int skipLineEnd(int lineEnd) {
        int i = lineEnd;
        while (i < buffer.length() && (buffer.charAt(i) == '\r' || buffer.charAt(i) == '\n')) {
            i++;
        }
        return i;
    }

    public void handleLine(String raw) {
        String line = raw.trim();
        if (line.isEmpty()) {
            return;
        }
        if (!line.startsWith("$")) {
            listener.onInvalidLine(raw);
            return;
        }
        if (!NmeaChecksum.isValid(line)) {
            listener.onInvalidLine(raw);
            return;
        }
        listener.onSentence(line);

        int star = line.lastIndexOf('*');
        String body = line.substring(1, star);
        String[] fields = body.split(",", -1);
        if (fields.length < 1) {
            listener.onInvalidLine(raw);
            return;
        }
        // 兼容 GP / GN 等不同 talker：取地址字段最后三位作为类型
        String address = fields[0];
        String type = address.length() >= 3
                ? address.substring(address.length() - 3).toUpperCase(Locale.US) : "";
        currentTalker = address.length() > 3
                ? address.substring(0, address.length() - 3).toUpperCase(Locale.US) : "";
        try {
            switch (type) {
                case "GGA":
                    handleGga(fields);
                    break;
                case "RMC":
                    handleRmc(fields);
                    break;
                case "GSA":
                    handleGsa(fields);
                    break;
                case "GSV":
                    handleGsv(fields);
                    break;
                case "VTG":
                    handleVtg(fields);
                    break;
                default:
                    // 其他语句类型仅计入日志，不影响定位
                    break;
            }
        } catch (RuntimeException e) {
            listener.onInvalidLine(raw);
        }
    }

    private void handleGga(String[] f) {
        if (f.length < 10) {
            listener.onInvalidLine(join(f));
            return;
        }
        latest.utcMillis = resolveUtc(f[1]);
        latest.latitude = parseCoordinate(f[2], f[3]);
        latest.longitude = parseCoordinate(f[4], f[5]);
        int quality = parseInt(f[6], 0);
        latest.valid = quality > 0;
        latest.satellitesUsed = parseInt(f[7], -1);
        if (!f[8].isEmpty()) {
            latest.hdop = parseFloat(f[8], Float.NaN);
        }
        latest.altitudeM = parseFloat(f[9], Double.NaN);
        emitFix();
    }

    private void handleRmc(String[] f) {
        if (f.length < 10) {
            listener.onInvalidLine(join(f));
            return;
        }
        if (f[9].length() >= 6) {
            lastDay = parseInt(f[9].substring(0, 2), -1);
            lastMonth = parseInt(f[9].substring(2, 4), -1);
            int year = parseInt(f[9].substring(4, 6), -1);
            lastYear = year >= 0 ? 2000 + year : -1;
        }
        latest.valid = "A".equalsIgnoreCase(f[2].trim());
        latest.utcMillis = resolveUtc(f[1]);
        latest.latitude = parseCoordinate(f[3], f[4]);
        latest.longitude = parseCoordinate(f[5], f[6]);
        double knots = parseFloat(f[7], Double.NaN);
        if (!Double.isNaN(knots)) {
            latest.speedMps = knots * 0.514444;
        }
        double course = parseFloat(f[8], Double.NaN);
        if (!Double.isNaN(course)) {
            latest.courseDeg = course;
        }
        emitFix();
    }

    private void handleGsa(String[] f) {
        if (f.length < 15) {
            listener.onInvalidLine(join(f));
            return;
        }
        usedPrns.clear();
        for (int i = 3; i <= 14 && i < f.length; i++) {
            int prn = parseInt(f[i], -1);
            if (prn > 0) {
                usedPrns.add(prn);
            }
        }
        // GSA 更新了"参与定位"的卫星集合，刷新已收到的卫星列表标记
        for (Sat sat : pendingSatellites) {
            sat.used = usedPrns.contains(sat.prn);
        }
        if (!pendingSatellites.isEmpty()) {
            listener.onSatellites(new ArrayList<Sat>(pendingSatellites), gsvInView);
        }
    }

    private void handleGsv(String[] f) {
        if (f.length < 4) {
            listener.onInvalidLine(join(f));
            return;
        }
        int totalPages = parseInt(f[1], 1);
        int page = parseInt(f[2], 1);
        gsvInView = parseInt(f[3], gsvInView);
        if (page <= 1) {
            pendingSatellites.clear();
            gsvTalker = currentTalker;
        }
        int index = 4;
        while (index + 3 < f.length) {
            int prn = parseInt(f[index], -1);
            if (prn > 0) {
                Sat sat = new Sat();
                sat.prn = prn;
                sat.elevationDeg = parseFloat(f[index + 1], Float.NaN);
                sat.azimuthDeg = parseFloat(f[index + 2], Float.NaN);
                sat.snr = f[index + 3].isEmpty() ? Float.NaN : parseFloat(f[index + 3], Float.NaN);
                sat.used = usedPrns.contains(prn);
                sat.constellation = constellationOf(gsvTalker, prn);
                pendingSatellites.add(sat);
            }
            index += 4;
        }
        if (page >= totalPages && !pendingSatellites.isEmpty()) {
            listener.onSatellites(new ArrayList<Sat>(pendingSatellites), gsvInView);
            latest.satellitesInView = gsvInView;
        }
    }

    private void handleVtg(String[] f) {
        if (f.length < 9) {
            listener.onInvalidLine(join(f));
            return;
        }
        double kmh = parseFloat(f[7], Double.NaN);
        if (!Double.isNaN(kmh)) {
            latest.speedMps = kmh / 3.6;
        }
        double track = parseFloat(f[1], Double.NaN);
        if (!Double.isNaN(track)) {
            latest.courseDeg = track;
        }
        emitFix();
    }

    /**
     * 推断卫星所属星座：GSV talker 优先（GL/GA/GB/BD/GQ 明确指定星座）；
     * GP / GN（含发送端统一用 GP 前缀的情况）按 PRN 编号段推断。
     */
    private static String constellationOf(String talker, int prn) {
        if ("GL".equals(talker)) {
            return "GLN";
        }
        if ("GA".equals(talker)) {
            return "GAL";
        }
        if ("GB".equals(talker) || "BD".equals(talker)) {
            return "BDS";
        }
        if ("GQ".equals(talker)) {
            return "QZSS";
        }
        // GP / GN / 未知 talker：按编号段推断（Android GnssStatus 的 svid 区间）
        if (prn >= 1 && prn <= 32) {
            return "GPS";
        }
        if (prn >= 65 && prn <= 96) {
            return "GLN";
        }
        if (prn >= 120 && prn <= 158) {
            return "SBAS";
        }
        if (prn >= 193 && prn <= 200) {
            return "QZSS";
        }
        if (prn >= 201 && prn <= 237) {
            return "BDS";
        }
        if (prn >= 301 && prn <= 336) {
            return "GAL";
        }
        return null;
    }

    private void emitFix() {
        Fix copy = new Fix();
        copy.valid = latest.valid;
        copy.latitude = latest.latitude;
        copy.longitude = latest.longitude;
        copy.altitudeM = latest.altitudeM;
        copy.speedMps = latest.speedMps;
        copy.courseDeg = latest.courseDeg;
        copy.hdop = latest.hdop;
        copy.satellitesUsed = Math.max(latest.satellitesUsed, usedPrns.size() > 0 ? usedPrns.size() : -1);
        copy.satellitesInView = latest.satellitesInView;
        copy.utcMillis = latest.utcMillis;
        listener.onFix(copy);
    }

    /** ddmm.mmmm + N/S/E/W → 带符号十进制度。 */
    private static double parseCoordinate(String value, String hemisphere) {
        if (value == null || value.isEmpty()) {
            return Double.NaN;
        }
        int dot = value.indexOf('.');
        if (dot < 0) {
            return Double.NaN;
        }
        // NMEA 固定格式：纬度 ddmm.mmmm、经度 dddmm.mmmm，度数占小数点前 2 位以上
        int degreeLength = Math.max(1, Math.min(3, dot - 2));
        double degrees = Double.parseDouble(value.substring(0, degreeLength));
        double minutes = Double.parseDouble(value.substring(degreeLength));
        double result = degrees + minutes / 60.0;
        if (hemisphere != null && (hemisphere.equalsIgnoreCase("S") || hemisphere.equalsIgnoreCase("W"))) {
            result = -result;
        }
        return result;
    }

    /** HHmmss(.sss) + 已知日期（来自 RMC）→ UTC 毫秒；推算不出返回 0。 */
    private long resolveUtc(String timeField) {
        if (timeField == null || timeField.length() < 6) {
            return 0L;
        }
        int hours = parseInt(timeField.substring(0, 2), -1);
        int minutes = parseInt(timeField.substring(2, 4), -1);
        int seconds = parseInt(timeField.substring(4, 6), -1);
        int millis = 0;
        if (timeField.length() > 7 && timeField.charAt(6) == '.') {
            String fraction = timeField.substring(7);
            int value = parseInt(fraction, 0);
            if (fraction.length() == 1) {
                millis = value * 100;
            } else if (fraction.length() == 2) {
                millis = value * 10;
            } else {
                while (fraction.length() > 3) {
                    fraction = fraction.substring(0, fraction.length() - 1);
                }
                millis = Integer.parseInt(fraction);
            }
        }
        if (hours < 0 || minutes < 0 || seconds < 0) {
            return 0L;
        }
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        if (lastDay > 0 && lastMonth > 0 && lastYear > 0) {
            calendar.set(lastYear, lastMonth - 1, lastDay, hours, minutes, seconds);
        } else {
            calendar.set(Calendar.HOUR_OF_DAY, hours);
            calendar.set(Calendar.MINUTE, minutes);
            calendar.set(Calendar.SECOND, seconds);
            calendar.set(Calendar.MILLISECOND, 0);
        }
        calendar.set(Calendar.MILLISECOND, millis);
        return calendar.getTimeInMillis();
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(String value, float fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseFloat(String value, double fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String join(String[] fields) {
        StringBuilder sb = new StringBuilder("$");
        for (String field : fields) {
            sb.append(field).append(',');
        }
        return sb.toString();
    }
}
