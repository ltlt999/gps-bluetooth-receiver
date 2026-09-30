package com.gpsbt.receiver.nmea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NmeaChecksumTest {

    @Test
    public void compute_matchesKnownSentence() {
        // $GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47
        assertEquals("47", NmeaChecksum.compute(
                "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"));
    }

    @Test
    public void isValid_acceptsValidSentence() {
        assertTrue(NmeaChecksum.isValid(
                "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"));
    }

    @Test
    public void isValid_rejectsCorruptedSentence() {
        assertFalse(NmeaChecksum.isValid(
                "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48"));
    }

    @Test
    public void isValid_rejectsMissingChecksum() {
        assertFalse(NmeaChecksum.isValid("$GPGGA,123519,4807.038,N"));
    }

    @Test
    public void appendChecksum_roundTrips() {
        String sentence = NmeaChecksum.appendChecksum("GPRMC,123519,A,4807.038,N");
        assertTrue(sentence.startsWith("$GPRMC,123519,A,4807.038,N*"));
        assertTrue(NmeaChecksum.isValid(sentence));
    }
}
