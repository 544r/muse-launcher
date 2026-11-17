package com.seekstars.muselauncher;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** 独立进程中的测活，既不读账号 Cookie，也不修改账号进程的代理。 */
public final class ProxyProbeService extends Service {
    static final int TEST = 1;
    static final int RESULT = 2;
    private static final String TEST_URL = "https://muse.ai/";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == TEST) startTest(message);
        return true;
    }));
    private WebView webView;
    private Messenger reply;
    private long generation;
    private int requestId;
    private long started;

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private void startTest(Message message) {
        cleanup();
        handler.removeCallbacksAndMessages(null);
        long token = ++generation;
        requestId = message.arg1;
        reply = message.replyTo;
        started = SystemClock.elapsedRealtime();
        final ProxySpec spec;
        try {
            spec = ProxySpec.parse(message.getData().getString("proxy"));
        } catch (IllegalArgumentException e) {
            finishTest(token, false, e.getMessage());
            return;
        }
        handler.postDelayed(() -> finishTest(token, false, "连接超时（10 秒）"), 10000);
        ProxyHelper.apply(spec.value, error -> {
            if (token != generation || reply == null) return;
            if (error != null) { finishTest(token, false, error); return; }
            webView = new WebView(this);
            String ua = webView.getSettings().getUserAgentString();
            if (ua != null) webView.getSettings().setUserAgentString(
                    ua.replace("; wv", "").replaceAll("\\s*Version/[\\d.]+", ""));
            webView.clearCache(true);
            webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
            webView.getSettings().setAllowFileAccess(false);
            webView.getSettings().setAllowContentAccess(false);
            webView.setWebViewClient(new WebViewClient() {
                private boolean authenticated;

                @Override public void onPageFinished(WebView view, String url) {
                    if (token != generation) return;
                    Uri target = Uri.parse(url);
                    boolean expected = "https".equals(target.getScheme())
                            && "muse.ai".equalsIgnoreCase(target.getHost());
                    finishTest(token, expected, expected ? null : "测活被重定向到其他站点");
                }

                @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                      WebResourceError error) {
                    if (request.isForMainFrame()) finishTest(token, false, "无法通过该代理访问 muse.ai");
                }

                @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                          WebResourceResponse response) {
                    if (request.isForMainFrame()) finishTest(token, false, "目标站点返回 HTTP " + response.getStatusCode());
                }

                @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler auth,
                                                                String host, String realm) {
                    if (!authenticated && spec.authenticates(host)) {
                        authenticated = true;
                        auth.proceed(spec.username, spec.password);
                    } else {
                        auth.cancel();
                        finishTest(token, false, "代理认证失败或认证来源不匹配");
                    }
                }

                @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    if (webView == view) webView = null;
                    view.destroy();
                    finishTest(token, false, "测活页面异常，请重试");
                    return true;
                }
            });
            webView.loadUrl(TEST_URL + "?launcher_probe=" + started);
        });
    }

    private void finishTest(long token, boolean ok, String error) {
        if (token != generation || reply == null) return;
        Messenger target = reply;
        reply = null;
        ++generation;
        Message response = Message.obtain(null, RESULT, requestId, 0);
        Bundle data = new Bundle();
        data.putBoolean("ok", ok);
        data.putString("error", error);
        data.putLong("elapsed", SystemClock.elapsedRealtime() - started);
        response.setData(data);
        handler.removeCallbacksAndMessages(null);
        // 不在 WebView 的回调栈内 destroy，防止错误结束后再触发页面完成回调。
        handler.post(this::cleanup);
        try { target.send(response); } catch (RemoteException ignored) { }
    }

    private void cleanup() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(new WebViewClient());
            webView.destroy();
            webView = null;
        }
    }

    @Override public void onDestroy() {
        ++generation;
        reply = null;
        handler.removeCallbacksAndMessages(null);
        cleanup();
        super.onDestroy();
        // 服务解绑后结束专用进程，清除 Chromium 连接池/代理认证内存缓存。
        // 下一次测活不能复用上一组凭据成功建立的连接。
        if (android.app.Application.getProcessName().endsWith(":proxy_probe")) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
