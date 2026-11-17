package com.seekstars.muselauncher;

import android.net.Uri;
import android.webkit.WebView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileSelectionTest {
    @Test public void resultWaitsForPageToResumeAndIsDeliveredOnce() {
        WebView view = new WebView(RuntimeEnvironment.getApplication());
        FileSelection selection = new FileSelection();
        List<Uri[]> results = new ArrayList<>();
        selection.begin(view, results::add);
        Uri[] files = {Uri.parse("content://test/document")};
        selection.receive(files);
        assertTrue(results.isEmpty());
        selection.deliverTo(view);
        selection.deliverTo(view);
        assertEquals(1, results.size());
        assertSame(files, results.get(0));
        view.destroy();
    }

    @Test public void switchingAccountCancelsInsteadOfDeliveringFilesToAnotherPage() {
        WebView first = new WebView(RuntimeEnvironment.getApplication());
        WebView second = new WebView(RuntimeEnvironment.getApplication());
        FileSelection selection = new FileSelection();
        List<Uri[]> results = new ArrayList<>();
        selection.begin(first, results::add);
        selection.receive(new Uri[]{Uri.parse("content://test/document")});
        selection.deliverTo(second);
        assertEquals(1, results.size());
        assertNull(results.get(0));
        first.destroy();
        second.destroy();
    }

    @Test public void destroyedPageCancelsPendingCallbackOnlyOnce() {
        WebView view = new WebView(RuntimeEnvironment.getApplication());
        FileSelection selection = new FileSelection();
        List<Uri[]> results = new ArrayList<>();
        selection.begin(view, results::add);
        selection.cancelFor(view);
        selection.cancel();
        assertEquals(1, results.size());
        assertNull(results.get(0));
        view.destroy();
    }
}
