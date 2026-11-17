package com.seekstars.muselauncher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionChecksTest {
    @Test public void schedulesOneUniqueAccountPerFrameAndNeverChecksInline() {
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        List<Long> checked = new ArrayList<>();
        SessionChecks checks = new SessionChecks(frames::add, frames::remove, checked::add);
        checks.request(7);
        checks.request(7);
        checks.request(8);
        assertTrue(checked.isEmpty());
        assertEquals(1, frames.size());
        frames.remove().run();
        assertEquals(Arrays.asList(7L), checked);
        assertEquals(1, frames.size());
        frames.remove().run();
        assertEquals(Arrays.asList(7L, 8L), checked);
        assertTrue(frames.isEmpty());
    }

    @Test public void pauseCancelsQueuedWorkAndNewRequestsCanResume() {
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        List<Long> checked = new ArrayList<>();
        SessionChecks checks = new SessionChecks(frames::add, frames::remove, checked::add);
        checks.request(1);
        checks.request(2);
        frames.remove().run();
        checks.cancel();
        assertTrue(frames.isEmpty());
        checks.request(3);
        frames.remove().run();
        assertEquals(Arrays.asList(1L, 3L), checked);
    }

    @Test public void requestArrivingDuringInspectionIsNotLost() {
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        List<Long> checked = new ArrayList<>();
        SessionChecks[] checks = new SessionChecks[1];
        checks[0] = new SessionChecks(frames::add, frames::remove, id -> {
            checked.add(id);
            if (checked.size() == 1) checks[0].request(id);
        });
        checks[0].request(1);
        frames.remove().run();
        assertEquals(1, frames.size());
        frames.remove().run();
        assertEquals(Arrays.asList(1L, 1L), checked);
        assertTrue(frames.isEmpty());
    }
}
