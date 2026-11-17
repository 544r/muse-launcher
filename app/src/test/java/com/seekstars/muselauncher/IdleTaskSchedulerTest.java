package com.seekstars.muselauncher;

import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class IdleTaskSchedulerTest {
    private void advance(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    @Test public void checksWaitForTouchAndDrawerToBothFinishThenSpaceOut() {
        IdleTaskScheduler scheduler = new IdleTaskScheduler();
        List<Long> checked = new ArrayList<>();
        SessionChecks checks = new SessionChecks(scheduler::schedule, scheduler::cancel, checked::add);
        Object touch = new Object(), drawer = new Object();
        scheduler.setBusy(touch, true);
        scheduler.setBusy(drawer, true);
        checks.request(7);
        checks.request(7);
        checks.request(8);
        advance(500);
        assertTrue(checked.isEmpty());
        scheduler.setBusy(touch, false);
        advance(500);
        assertTrue(checked.isEmpty());
        scheduler.setBusy(drawer, false);
        advance(119);
        assertTrue(checked.isEmpty());
        advance(1);
        assertEquals(Arrays.asList(7L), checked);
        advance(31);
        assertEquals(1, checked.size());
        advance(1);
        assertEquals(Arrays.asList(7L, 8L), checked);
        scheduler.clear();
    }

    @Test public void continuedFlingDefersWorkAndPauseCancelsIt() {
        IdleTaskScheduler scheduler = new IdleTaskScheduler();
        List<Long> checked = new ArrayList<>();
        SessionChecks checks = new SessionChecks(scheduler::schedule, scheduler::cancel, checked::add);
        scheduler.defer();
        checks.request(1);
        for (int i = 0; i < 10; i++) {
            advance(50);
            scheduler.defer();
        }
        advance(119);
        assertTrue(checked.isEmpty());
        checks.cancel();
        scheduler.clear();
        advance(500);
        assertTrue(checked.isEmpty());
        checks.request(2);
        advance(1);
        assertEquals(Arrays.asList(2L), checked);
        scheduler.clear();
    }
}
