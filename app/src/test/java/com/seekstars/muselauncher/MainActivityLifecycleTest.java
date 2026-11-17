package com.seekstars.muselauncher;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityLifecycleTest {
    public static class UnsupportedWebViewActivity extends MainActivity {
        @Override boolean supportsProfiles() { return false; }
    }

    @Test public void unsupportedWebViewCanResumePauseAndDestroyWithUpdateDialog() {
        ActivityController<UnsupportedWebViewActivity> controller =
                Robolectric.buildActivity(UnsupportedWebViewActivity.class).create().start().resume();
        assertNotNull(ShadowDialog.getLatestDialog());
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        controller.pause().stop().destroy();
    }

    @Test public void unsupportedWebViewCanExitWithoutTouchingUninitializedViews() {
        ActivityController<UnsupportedWebViewActivity> controller =
                Robolectric.buildActivity(UnsupportedWebViewActivity.class).create().start().resume();
        controller.get().onBackPressed();
        assertTrue(controller.get().isFinishing());
        controller.pause().stop().destroy();
    }
}
