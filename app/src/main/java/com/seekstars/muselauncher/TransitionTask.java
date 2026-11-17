package com.seekstars.muselauncher;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Run only the latest requested task after the UI transition has settled. Main thread only. */
final class TransitionTask {
    private final BooleanSupplier ready;
    private final Consumer<Runnable> post, remove;
    private final Runnable run = this::run;
    private Runnable pending;
    private boolean posted;

    TransitionTask(BooleanSupplier ready, Consumer<Runnable> post, Consumer<Runnable> remove) {
        this.ready = ready;
        this.post = post;
        this.remove = remove;
    }

    void submit(Runnable task) {
        pending = task;
        onTransitionChanged();
    }

    void onTransitionChanged() {
        if (pending != null && !posted && ready.getAsBoolean()) {
            posted = true;
            post.accept(run);
        }
    }

    void cancel() {
        pending = null;
        if (posted) remove.accept(run);
        posted = false;
    }

    private void run() {
        posted = false;
        if (pending == null || !ready.getAsBoolean()) return;
        Runnable task = pending;
        pending = null;
        task.run();
    }
}
