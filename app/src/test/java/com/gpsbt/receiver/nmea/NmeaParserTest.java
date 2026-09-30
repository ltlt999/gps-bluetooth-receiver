package com.gpsbt.receiver.nmea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class NmeaParserTest {

    /** 收集解析事件的监听器。 */
    private static final class RecordingListener implements NmeaParser.Listener {
        final List<NmeaParser.Fix> fixes = new ArrayList<>();
        final List<List<NmeaParser.Sat>> satelliteSets = new ArrayList<>();
        final List<String> sentences = new ArrayList<>();
        final List<String> invalid = new ArrayList<>();
        int inView;

        @Override
        public void onFix(NmeaParser.Fix fix) {
            fixes.add(fix);
        }

        @Override
        public void onSatellites(List<NmeaParser.Sat> satellites, int inView) {
            satelliteSets.add(satellites);
            this.inView = inView;
        }

        @Override
        public void onSentence(String sentence) {
            sentences.add(sentence);
        }

        @Override
        public void onInvalidLine(String raw) {
            invalid.add(raw);
        }
    }

    private static final String GGA =
            "$GPGGA,092750.000,5321.6802,N,00630.3372,W,1,8,1.038,61.7,M,55.2,M,,*4E";
    private static final String RMC =
            "$GPRMC,092750.000,A,5321.6802,N,00630.3372,W,0.02,31.66,280519,,,A*4B";

    @Test
    public void parsesGgaPosition() {
        RecordingListener listener = new RecordingListener();
        NmeaParser parser = new NmeaParser(listener);
        parser.handleLine(GGA);

        assertEquals(1, listener.fixes.size());
        NmeaParser.Fix fix = listener.fixes.get(0);
        assertTrue(fix.valid);
        assertEquals(53 + 21.6802 / 60.0, fix.latitude, 1e-6);
        assertEquals(-(6 + 30.3372 / 60.0), fix.longitude, 1e-6);
        assertEquals(61.7, fix.altitudeM, 1e-6);
        assertEquals(8, fix.satellitesUsed);
        assertEquals(1.038f, fix.hdop, 1e-6);
        assertEquals(0, listener.invalid.size());
    }

    @Test
    public void parsesRmcSpeedAndCourse() {
        RecordingListener listener = new RecordingListener();
        NmeaParser parser = new NmeaParser(listener);
        parser.handleLine(GGA);
        parser.handleLine(RMC);

        NmeaParser.Fix fix = listener.fixes.get(listener.fixes.size() - 1);
        assertTrue(fix.valid);
        assertEquals(0.02 * 0.514444, fix.speedMps, 1e-4);
        assertEquals(31.66, fix.courseDeg, 1e-6);
        // RMC 提供日期 280519，与时间合并成完整 UTC
        assertTrue(fix.utcMillis > 0);
        assertEquals(2019, utcYear(fix.utcMillis));
    }

    private long utcYear(long millis) {
        java.util.Calendar calendar =
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(millis);
        return calendar.get(java.util.Calendar.YEAR);
    }

    @Test
    public void feedHandlesFragmentedInput() {
        RecordingListener listener = new RecordingListener();
        NmeaParser parser = new NmeaParser(listener);
        byte[] data = (GGA + "\r\n" + RMC + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // 模拟逐字节到达
        for (byte b : data) {
            parser.feed(new byte[]{b}, 0, 1);
        }
        assertEquals(2, listener.sentences.size());
        assertEquals(2, listener.fixes.size());
        assertEquals(0, listener.invalid.size());
    }

    @Test
    public void invalidChecksumIsReported() {
        RecordingListener listener = new RecordingListener();
        NmeaParser parser = new NmeaParser(listener);
        parser.handleLine("$GPGGA,092750.000,5321.6802,N,00630.3372,W,1,8,1.038,61.7,M,55.2,M,,*00");
        assertEquals(0, listener.sentences.size());
        assertEquals(1, listener.invalid.size());
        assertEquals(0, listener.fixes.size());
    }

    @Test
    public void parsesGsvPagesAndGsaUsedFlags() {
        RecordingListener listener = new RecordingListener();
        NmeaParser parser = new NmeaParser(listener);
        parser.handleLine(
                "$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39");
        parser.handleLine(
                "$GPGSV,2,1,08,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*75");
        // 第一页不触发回调
        assertEquals(0, listener.satelliteSets.size());
        parser.handleLine(
                "$GPGSV,2,2,08,18,04,314,,24,82,157,42,27,02,036,,29,05,102,*7C");

        assertEquals(1, listener.satelliteSets.size());
        assertEquals(8, listener.inView);
        List<NmeaParser.Sat> satellites = listener.satelliteSets.get(0);
        assertEquals(8, satellites.size());
        // PRN 12、24 在 GSA 中标记为参与定位
        assertTrue(isUsed(satellites, 12));
        assertTrue(isUsed(satellites, 24));
        assertFalse(isUsed(satellites, 1));
    }

    private boolean isUsed(List<NmeaParser.Sat> satellites, int prn) {
        for (NmeaParser.Sat sat : satellites) {
            if (sat.prn == prn) {
                return sat.used;
            }
        }
        return false;
    }

    @Test
    public void infersConstellationFromPrn() {
        RecordingListener listener = new RecordingListener();
        NmeaParser parser = new NmeaParser(listener);
        // 发送端统一使用 GP 前缀：1-32 视为 GPS，201-237 视为北斗
        parser.handleLine("$GPGSV,1,1,02,06,45,100,42,206,60,150,45*4C");
        assertEquals(1, listener.satelliteSets.size());
        List<NmeaParser.Sat> satellites = listener.satelliteSets.get(0);
        assertEquals(2, satellites.size());
        assertEquals("GPS", satellites.get(0).constellation);
        assertEquals("BDS", satellites.get(1).constellation);
    }

    @Test
    public void talkerOverridesPrnInference() {
        RecordingListener listener = new RecordingListener();
        NmeaParser parser = new NmeaParser(listener);
        // BD talker 明确指定北斗：即使 PRN 06 落在 GPS 区间也判为 BDS
        parser.handleLine("$BDGSV,1,1,01,06,45,100,42*59");
        // GI talker：NavIC
        parser.handleLine("$GIGSV,1,1,01,403,45,100,42*60");
        assertEquals(2, listener.satelliteSets.size());
        assertEquals("BDS", listener.satelliteSets.get(0).get(0).constellation);
        assertEquals("NavIC", listener.satelliteSets.get(1).get(0).constellation);
    }

    @Test
    public void supportsGnTalker() {
        RecordingListener listener = new RecordingListener();
        NmeaParser parser = new NmeaParser(listener);
        parser.handleLine(
                "$GNGGA,001043.00,4404.14036,N,12118.85961,W,1,12,0.98,1113.0,M,-21.3,M,,*47");
        assertEquals(1, listener.fixes.size());
        assertTrue(listener.fixes.get(0).valid);
        assertEquals(44 + 4.14036 / 60.0, listener.fixes.get(0).latitude, 1e-6);
    }
}
