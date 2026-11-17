package com.seekstars.muselauncher;

import android.os.Looper;
import android.view.View;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class LoadingOverlayTest {
    private void advance(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    @Test public void fastPageNeverFlashesAndReadyPageDoesNotWait() {
        View view = new View(RuntimeEnvironment.getApplication());
        LoadingOverlay overlay = new LoadingOverlay(view);
        overlay.begin();
        advance(100);
        assertEquals(View.INVISIBLE, view.getVisibility());
        overlay.hide();
        advance(500);
        assertEquals(View.INVISIBLE, view.getVisibility());
        overlay.begin();
        advance(120);
        assertEquals(View.VISIBLE, view.getVisibility());
        overlay.hide();
        assertEquals(View.INVISIBLE, view.getVisibility());
    }

    @Test public void newSwitchCancelsPreviousTimeoutAndPauseCancelsPendingShow() {
        View view = new View(RuntimeEnvironment.getApplication());
        LoadingOverlay overlay = new LoadingOverlay(view);
        overlay.begin();
        advance(7900);
        overlay.begin();
        advance(120);
        assertEquals(View.VISIBLE, view.getVisibility());
        advance(7880);
        assertEquals(View.INVISIBLE, view.getVisibility());
        overlay.begin();
        overlay.hide();
        advance(8000);
        assertEquals(View.INVISIBLE, view.getVisibility());
    }
}
