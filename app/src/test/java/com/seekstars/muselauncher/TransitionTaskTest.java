package com.seekstars.muselauncher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransitionTaskTest {
    @Test public void closingDrawerActivatesOnlyLatestSelectedAccount() {
        boolean[] closed = {false};
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        List<Integer> activated = new ArrayList<>();
        TransitionTask task = new TransitionTask(() -> closed[0], callbacks::add, callbacks::remove);
        task.submit(() -> activated.add(1));
        task.submit(() -> activated.add(2));
        assertTrue(callbacks.isEmpty());
        closed[0] = true;
        task.onTransitionChanged();
        task.onTransitionChanged();
        assertEquals(1, callbacks.size());
        assertTrue(activated.isEmpty());
        callbacks.remove().run();
        assertEquals(Arrays.asList(2), activated);
    }

    @Test public void reopeningDrawerBeforeCallbackDelaysActivationAgain() {
        boolean[] closed = {true};
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        List<Integer> activated = new ArrayList<>();
        TransitionTask task = new TransitionTask(() -> closed[0], callbacks::add, callbacks::remove);
        task.submit(() -> activated.add(1));
        closed[0] = false;
        callbacks.remove().run();
        assertTrue(activated.isEmpty());
        closed[0] = true;
        task.onTransitionChanged();
        callbacks.remove().run();
        assertEquals(Arrays.asList(1), activated);
    }

    @Test public void pauseOrNewSwitchCancelsBothWaitingAndPostedActivation() {
        boolean[] closed = {false};
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        List<Integer> activated = new ArrayList<>();
        TransitionTask task = new TransitionTask(() -> closed[0], callbacks::add, callbacks::remove);
        task.submit(() -> activated.add(1));
        task.cancel();
        closed[0] = true;
        task.onTransitionChanged();
        assertTrue(callbacks.isEmpty());
        task.submit(() -> activated.add(2));
        assertEquals(1, callbacks.size());
        task.cancel();
        assertTrue(callbacks.isEmpty());
        assertTrue(activated.isEmpty());
        task.submit(() -> activated.add(3));
        callbacks.remove().run();
        assertEquals(Arrays.asList(3), activated);
    }
}
