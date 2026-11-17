package com.seekstars.muselauncher;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebView;

/** 文件选择结果等到所属页面恢复网络后再交付，避免上传在 onResume 前被阻断。 */
final class FileSelection {
    private WebView owner;
    private ValueCallback<Uri[]> callback;
    private Uri[] result;
    private boolean ready;

    void begin(WebView view, ValueCallback<Uri[]> next) {
        cancel();
        owner = view;
        callback = next;
    }

    boolean pending() { return callback != null; }

    void receive(Uri[] files) { result = files; ready = true; }

    void deliverTo(WebView active) {
        if (!ready || callback == null) return;
        ValueCallback<Uri[]> target = callback;
        Uri[] files = active == owner ? result : null;
        clear();
        target.onReceiveValue(files);
    }

    void cancelFor(WebView view) { if (owner == view) cancel(); }

    void cancel() {
        ValueCallback<Uri[]> target = callback;
        clear();
        if (target != null) target.onReceiveValue(null);
    }

    private void clear() {
        owner = null;
        callback = null;
        result = null;
        ready = false;
    }
}
