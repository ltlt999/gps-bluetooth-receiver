package com.gpsbt.receiver.nmea;

/** NMEA 0183 校验和计算与验证。 */
public final class NmeaChecksum {

    private NmeaChecksum() {
    }

    /** 计算以 '$' 开头、'*' 之前内容（不含两者）的异或校验和，两位大写十六进制。 */
    public static String compute(String body) {
        int checksum = 0;
        for (int i = 0; i < body.length(); i++) {
            checksum ^= body.charAt(i);
        }
        return String.format("%02X", checksum & 0xFF);
    }

    /**
     * 验证完整语句（例如 {@code $GPGGA,...*48}）的校验和。
     * 无 '*校验' 部分的语句视为无效。
     */
    public static boolean isValid(String sentence) {
        int star = sentence.lastIndexOf('*');
        if (star < 0 || star + 3 > sentence.length()) {
            return false;
        }
        String provided = sentence.substring(star + 1).trim().toUpperCase();
        if (provided.length() != 2) {
            return false;
        }
        int start = sentence.startsWith("$") ? 1 : 0;
        String body = sentence.substring(start, star);
        return compute(body).equals(provided);
    }

    /** 给语句补上 '*CS'；缺少 '$' 前缀时自动补全，返回完整语句。 */
    public static String appendChecksum(String withoutChecksum) {
        String body = withoutChecksum.startsWith("$")
                ? withoutChecksum : "$" + withoutChecksum;
        if (body.contains("*")) {
            return body;
        }
        return body + "*" + compute(body.substring(1));
    }
}
