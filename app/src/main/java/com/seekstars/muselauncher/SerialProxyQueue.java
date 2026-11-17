package com.seekstars.muselauncher;

import java.util.ArrayDeque;

/** 主线程串行调度；驱动完成后才通知调用方。 */
final class SerialProxyQueue {
    interface Completion { void done(String error); }
    interface Driver { void apply(String value, Completion completion); }

    private static final class Request {
        final String value;
        final Completion completion;
        Request(String value, Completion completion) {
            this.value = value;
            this.completion = completion;
        }
    }

    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private final Driver driver;
    private boolean running;

    SerialProxyQueue(Driver driver) { this.driver = driver; }

    void apply(String value, Completion completion) {
        pending.add(new Request(value, completion));
        next();
    }

    private void next() {
        if (running || pending.isEmpty()) return;
        running = true;
        Request request = pending.remove();
        boolean[] completed = {false};
        driver.apply(request.value, error -> {
            if (completed[0]) return;
            completed[0] = true;
            try {
                request.completion.done(error);
            } finally {
                running = false;
                next();
            }
        });
    }
}
