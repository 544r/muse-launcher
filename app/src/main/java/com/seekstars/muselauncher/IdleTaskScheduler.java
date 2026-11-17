package com.seekstars.muselauncher;

import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.SystemClock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Main-thread auxiliary work waits for input/animations to settle and the message queue to idle. */
final class IdleTaskScheduler {
    private static final long QUIET_MS = 120;
    private static final long BETWEEN_TASKS_MS = 32;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MessageQueue queue = Looper.getMainLooper().getQueue();
    private final Set<Object> busy = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Runnable arm = this::arm;
    private final MessageQueue.IdleHandler idle = this::runIdle;
    private Runnable pending;
    private boolean armed, registered;
    private long quietUntil;

    void schedule(Runnable task) {
        if (pending != null && pending != task) throw new IllegalStateException("Only one auxiliary task may be pending");
        pending = task;
        scheduleArm();
    }

    void cancel(Runnable task) {
        if (pending != task) return;
        pending = null;
        disarm();
    }

    void setBusy(Object owner, boolean active) {
        if (active) {
            if (busy.add(owner)) disarm();
        } else if (busy.remove(owner)) {
            defer();
        }
    }

    void defer() {
        quietUntil = SystemClock.uptimeMillis() + QUIET_MS;
        if (registered) {
            queue.removeIdleHandler(idle);
            registered = false;
        }
        // An existing timer rechecks the deadline; scrolling does not allocate a timer per event.
        scheduleArm();
    }

    void clear() {
        pending = null;
        busy.clear();
        disarm();
    }

    private void scheduleArm() {
        if (pending == null || !busy.isEmpty() || armed || registered) return;
        armed = true;
        handler.postDelayed(arm, Math.max(0, quietUntil - SystemClock.uptimeMillis()));
    }

    private void arm() {
        armed = false;
        if (pending == null || !busy.isEmpty()) return;
        if (SystemClock.uptimeMillis() < quietUntil) {
            scheduleArm();
            return;
        }
        registered = true;
        queue.addIdleHandler(idle);
    }

    private boolean runIdle() {
        registered = false;
        if (pending == null || !busy.isEmpty()) return false;
        if (SystemClock.uptimeMillis() < quietUntil) {
            scheduleArm();
            return false;
        }
        Runnable task = pending;
        pending = null;
        quietUntil = SystemClock.uptimeMillis() + BETWEEN_TASKS_MS;
        task.run();
        return false;
    }

    private void disarm() {
        handler.removeCallbacks(arm);
        armed = false;
        if (registered) queue.removeIdleHandler(idle);
        registered = false;
    }
}
