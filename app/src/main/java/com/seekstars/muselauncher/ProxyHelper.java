package com.seekstars.muselauncher;

import android.os.Handler;
import android.os.Looper;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

/** WebView 代理为进程级设置；所有完成通知均在主线程串行执行。 */
public final class ProxyHelper {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SerialProxyQueue QUEUE = new SerialProxyQueue(ProxyHelper::applyNow);

    private ProxyHelper() {}

    public static boolean supported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
    }

    public static void apply(String proxy, SerialProxyQueue.Completion completion) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> QUEUE.apply(proxy, completion));
        } else {
            QUEUE.apply(proxy, completion);
        }
    }

    private static void applyNow(String value, SerialProxyQueue.Completion completion) {
        try {
            ProxySpec spec = ProxySpec.parse(value);
            if (!supported()) {
                completion.done(spec.rule.isEmpty() ? null : "当前系统 WebView 不支持代理，请先更新");
                return;
            }
            ProxyController controller = ProxyController.getInstance();
            if (spec.rule.isEmpty()) {
                controller.clearProxyOverride(MAIN::post, () -> completion.done(null));
            } else {
                // 代理不可用时失败，不能静默回退直连。
                ProxyConfig config = new ProxyConfig.Builder().addProxyRule(spec.rule).build();
                controller.setProxyOverride(config, MAIN::post, () -> completion.done(null));
            }
        } catch (IllegalArgumentException e) {
            completion.done("代理配置无效，请检查协议、主机和端口");
        } catch (RuntimeException e) {
            completion.done("无法应用代理，请更新系统 WebView 后重试");
        }
    }
}
