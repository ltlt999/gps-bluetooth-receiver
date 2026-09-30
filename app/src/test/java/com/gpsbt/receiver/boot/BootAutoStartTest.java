package com.gpsbt.receiver.boot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BootAutoStartTest {

    @Test
    public void startsWhenAllConditionsMet() {
        assertTrue(BootAutoStart.shouldAutoStart(true, false, false, true));
    }

    @Test
    public void doesNotStartWhenSwitchOff() {
        assertFalse(BootAutoStart.shouldAutoStart(false, false, false, true));
    }

    @Test
    public void doesNotStartTwicePerProcess() {
        assertFalse(BootAutoStart.shouldAutoStart(true, true, false, true));
    }

    @Test
    public void doesNotStartWhenPrerequisitesMissing() {
        assertFalse(BootAutoStart.shouldAutoStart(true, false, false, false));
    }

    @Test
    public void doesNotStartWhenServiceAlreadyRunning() {
        assertFalse(BootAutoStart.shouldAutoStart(true, false, true, true));
    }
}
