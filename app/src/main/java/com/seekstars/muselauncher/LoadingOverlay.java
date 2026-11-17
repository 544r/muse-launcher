package com.seekstars.muselauncher;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

/** Fast transitions have no spinner flash; a ready page never waits for a minimum display time. */
final class LoadingOverlay {
    private static final long SHOW_DELAY_MS = 120;
    private static final long TIMEOUT_MS = 8000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View view;
    private final Runnable show;
    private final Runnable timeout = this::hide;

    LoadingOverlay(View view) {
        this.view = view;
        show = () -> view.setVisibility(View.VISIBLE);
        view.setVisibility(View.INVISIBLE);
    }

    void begin() {
        hide();
        handler.postDelayed(show, SHOW_DELAY_MS);
        handler.postDelayed(timeout, TIMEOUT_MS);
    }

    void hide() {
        handler.removeCallbacks(show);
        handler.removeCallbacks(timeout);
        view.setVisibility(View.INVISIBLE);
    }
}
