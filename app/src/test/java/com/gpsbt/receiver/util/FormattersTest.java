package com.gpsbt.receiver.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FormattersTest {

    @Test
    public void latitudeFormatsNorthAndSouth() {
        assertEquals("31.230400° N", Formatters.latitude(31.2304));
        assertEquals("31.230400° S", Formatters.latitude(-31.2304));
        assertEquals("--", Formatters.latitude(Double.NaN));
    }

    @Test
    public void longitudeFormatsEastAndWest() {
        assertEquals("121.473700° E", Formatters.longitude(121.4737));
        assertEquals("121.473700° W", Formatters.longitude(-121.4737));
    }

    @Test
    public void bytesScales() {
        assertEquals("123 B", Formatters.bytes(123));
        assertEquals("4.5 KB", Formatters.bytes((long) (4.5 * 1024)));
        assertEquals("1.50 MB", Formatters.bytes((long) (1.5 * 1024 * 1024)));
    }

    @Test
    public void durationFormats() {
        assertEquals("5秒", Formatters.duration(5000));
        assertEquals("3分04秒", Formatters.duration(184_000));
        assertEquals("1小时02分", Formatters.duration(3_720_000));
        assertEquals("--", Formatters.duration(-1));
    }

    @Test
    public void oneDecimalHandlesNaN() {
        assertEquals("3.1", Formatters.oneDecimal(3.14));
        assertEquals("--", Formatters.oneDecimal(Double.NaN));
    }
}
