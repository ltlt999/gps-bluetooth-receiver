package com.gpsbt.receiver.service;

import static org.junit.Assert.assertEquals;

import com.gpsbt.receiver.nmea.NmeaParser;

import org.junit.Test;

public class MockLocationInjectorTest {

    private static NmeaParser.Fix fix(double lat, double lon, double speedMps, double courseDeg) {
        NmeaParser.Fix fix = new NmeaParser.Fix();
        fix.valid = true;
        fix.latitude = lat;
        fix.longitude = lon;
        fix.speedMps = speedMps;
        fix.courseDeg = courseDeg;
        return fix;
    }

    private static NmeaParser.Fix project(NmeaParser.Fix base, long elapsedMs) {
        NmeaParser.Fix out = new NmeaParser.Fix();
        MockLocationInjector.extrapolate(base, elapsedMs, out);
        return out;
    }

    @Test
    public void movingNorthExtrapolatesLatitude() {
        // 20 m/s 向北 10 秒 = 200 米
        NmeaParser.Fix out = project(fix(30.0, 120.0, 20.0, 0.0), 10_000L);
        assertEquals(30.0 + 200.0 / 111320.0, out.latitude, 1e-6);
        assertEquals(120.0, out.longitude, 1e-9);
        assertEquals(20.0, out.speedMps, 1e-9);
    }

    @Test
    public void movingEastExtrapolatesLongitude() {
        // 赤道上 20 m/s 向东 10 秒 = 200 米
        NmeaParser.Fix out = project(fix(0.0, 120.0, 20.0, 90.0), 10_000L);
        assertEquals(0.0, out.latitude, 1e-9);
        assertEquals(120.0 + 200.0 / 111320.0, out.longitude, 1e-6);
    }

    @Test
    public void stationaryFreezesPosition() {
        NmeaParser.Fix out = project(fix(30.0, 120.0, 0.2, 45.0), 30_000L);
        assertEquals(30.0, out.latitude, 1e-9);
        assertEquals(120.0, out.longitude, 1e-9);
    }

    @Test
    public void missingSpeedOrCourseFreezesPosition() {
        NmeaParser.Fix noSpeed = fix(30.0, 120.0, Double.NaN, 0.0);
        NmeaParser.Fix out = project(noSpeed, 30_000L);
        assertEquals(30.0, out.latitude, 1e-9);

        NmeaParser.Fix noCourse = fix(30.0, 120.0, 20.0, Double.NaN);
        out = project(noCourse, 30_000L);
        assertEquals(30.0, out.latitude, 1e-9);
    }

    @Test
    public void extrapolationCapsAtMaxDuration() {
        // 20 m/s 外推 200 秒，只按 90 秒上限计算 = 1800 米
        NmeaParser.Fix out = project(fix(30.0, 120.0, 20.0, 0.0), 200_000L);
        assertEquals(30.0 + 1800.0 / 111320.0, out.latitude, 1e-6);
    }
}
