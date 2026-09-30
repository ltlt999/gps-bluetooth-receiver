package com.gpsbt.receiver.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReceiverServiceRetryTest {

    @Test
    public void firstRetryIsTwoSeconds() {
        assertEquals(2L, ReceiverService.retryDelaySeconds(1));
    }

    @Test
    public void retryDelayGrows() {
        assertEquals(4L, ReceiverService.retryDelaySeconds(2));
        assertEquals(10L, ReceiverService.retryDelaySeconds(5));
    }

    @Test
    public void retryDelayCapsAtThirtySeconds() {
        assertEquals(30L, ReceiverService.retryDelaySeconds(15));
        assertEquals(30L, ReceiverService.retryDelaySeconds(1000));
    }

    @Test
    public void retryDelayNeverZero() {
        assertTrue(ReceiverService.retryDelaySeconds(0) >= 2L);
    }
}
