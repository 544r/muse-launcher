package com.seekstars.muselauncher;

import android.app.Application;
import android.webkit.WebView;

public final class MuseApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        String process = getProcessName();
        if (process != null && process.endsWith(":proxy_probe")) {
            // 必须早于任何 WebView/AndroidX 特性探测，避免与账号进程争用数据目录。
            WebView.setDataDirectorySuffix("proxy_probe");
        }
    }
}
