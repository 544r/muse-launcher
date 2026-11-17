package com.seekstars.muselauncher;

import java.util.LinkedHashSet;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Deduplicate requests and inspect one profile per idle slot supplied by the scheduler. */
final class SessionChecks {
    private final LinkedHashSet<Long> pending = new LinkedHashSet<>();
    private final Consumer<Runnable> schedule, unschedule;
    private final LongConsumer inspect;
    private final Runnable step = this::next;
    private boolean scheduled;

    SessionChecks(Consumer<Runnable> schedule, Consumer<Runnable> unschedule, LongConsumer inspect) {
        this.schedule = schedule;
        this.unschedule = unschedule;
        this.inspect = inspect;
    }

    void request(long id) {
        pending.add(id);
        scheduleNext();
    }

    void cancel() {
        pending.clear();
        if (scheduled) unschedule.accept(step);
        scheduled = false;
    }

    private void scheduleNext() {
        if (!scheduled && !pending.isEmpty()) {
            scheduled = true;
            schedule.accept(step);
        }
    }

    private void next() {
        scheduled = false;
        if (pending.isEmpty()) return;
        long id = pending.iterator().next();
        pending.remove(id);
        try { inspect.accept(id); }
        finally { scheduleNext(); }
    }
}
