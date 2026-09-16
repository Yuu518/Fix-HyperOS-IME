package io.github.yuu518.hyperosime;

import org.junit.Test;

import static org.junit.Assert.*;

public class SampleScheduleTest {
    private SampleSchedule copying() {
        SampleSchedule schedule = new SampleSchedule();
        assertEquals(50, schedule.onDraw(0));
        assertTrue(schedule.beginSample());
        schedule.copyStarted(50);
        assertFalse(schedule.canReload());
        return schedule;
    }

    @Test
    public void drawsDuringCopyProduceOneTrailingSample() {
        SampleSchedule schedule = copying();
        assertEquals(-1, schedule.onDraw(60));
        assertEquals(-1, schedule.onDraw(70));
        assertEquals(-1, schedule.onDraw(80));
        assertEquals(710, schedule.copyFinished(90));
        assertTrue(schedule.canReload());
        assertEquals(-1, schedule.onDraw(100));

        assertTrue(schedule.beginSample());
        schedule.copyStarted(800);
        assertEquals(-1, schedule.copyFinished(810));
    }

    @Test
    public void unchangedCopyDoesNotStartPolling() {
        SampleSchedule schedule = copying();
        assertEquals(-1, schedule.copyFinished(60));
        assertEquals(730, schedule.onDraw(70));
        assertEquals(-1, schedule.onDraw(80));
    }

    @Test
    public void longCopyStillWaitsForDrawToSettle() {
        SampleSchedule schedule = copying();
        assertEquals(-1, schedule.onDraw(900));
        assertEquals(50, schedule.copyFinished(1000));
    }

    @Test
    public void closeDuringCopyRejectsResultsAndTrailingSample() {
        SampleSchedule schedule = copying();
        schedule.onDraw(60);
        schedule.close();
        assertTrue(schedule.isClosed());
        assertFalse(schedule.canReload());
        assertEquals(-1, schedule.copyFinished(70));
        assertTrue(schedule.canReload());
        assertEquals(-1, schedule.onDraw(80));
        assertFalse(schedule.beginSample());
    }

    @Test
    public void closeCancelsPendingTrailingSample() {
        SampleSchedule schedule = copying();
        schedule.onDraw(60);
        assertEquals(730, schedule.copyFinished(70));
        schedule.close();
        schedule.close();
        assertFalse(schedule.beginSample());
        assertEquals(-1, schedule.onDraw(1000));
    }

    @Test
    public void skippedSampleCanBeScheduledByNextDraw() {
        SampleSchedule schedule = new SampleSchedule();
        assertEquals(50, schedule.onDraw(0));
        assertEquals(-1, schedule.onDraw(10));
        assertTrue(schedule.beginSample());
        assertTrue(schedule.canReload());
        assertEquals(50, schedule.onDraw(60));
    }

    @Test
    public void failedCopyAllowsNextDrawWithoutBypassingThrottle() {
        SampleSchedule schedule = copying();
        assertEquals(-1, schedule.copyFinished(50));
        assertTrue(schedule.canReload());
        assertEquals(740, schedule.onDraw(60));
    }
}
