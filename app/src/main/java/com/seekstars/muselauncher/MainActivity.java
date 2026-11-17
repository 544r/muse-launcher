package com.seekstars.muselauncher;

import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.LongSparseArray;
import android.webkit.HttpAuthHandler;
import android.webkit.WebResourceResponse;
import java.util.Locale;
import java.util.Objects;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import android.view.inputmethod.InputMethodManager;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

public class MainActivity extends AppCompatActivity {

    private static final String START_URL = "https://muse.ai/";
    private static final int REQ_FILE = 10001;
    private static final int REQ_CONFIG = 10002;
    private static final long MIN_SPLASH_MS = 150;
    private static final long SPLASH_TIMEOUT_MS = 8000;
    /** muse.ai 登录会话 cookie（httpOnly）。匿名访问只会落下 hatch_native_auth_* 等流程 cookie。 */


    private AccountsStore store;
    private ProfileStore profileStore;
    private boolean initialized;
    private boolean resumed;
    private long switchGeneration;
    private String appliedProxy;
    private final java.util.Set<Long> interruptedLoads = new java.util.HashSet<>();
    private final Runnable renderAccountsRunnable = this::renderAccounts;

    private DrawerLayout drawerLayout;
    private View drawerContent;
    private View rail;
    private View edgeSwipe;
    private LinearLayout railAvatars;
    private LinearLayout accountList;
    private EditText searchInput;
    private View drawerEmpty;
    private TextView accCount;
    private FrameLayout webContainer;
    private View emptyState;
    private View splash;
    private TextView splashName;
    private View errorOverlay;
    private TextView errorMsg;

    /** 每个账号一个独立 WebView（Profile 只能在 WebView 初始化前设置一次） */
    private static final int MAX_ALIVE_WEBVIEWS = 4; // LRU：最多同时保留的账号页面数（含当前）
    private final LruAccountRegistry<WebView> webViews = new LruAccountRegistry<>(MAX_ALIVE_WEBVIEWS);
    private final LongSparseArray<Boolean> webLoaded = new LongSparseArray<>();
    /** 主框架加载出错标记（错误遮罩依据），重试成功后清除 */
    private final LongSparseArray<Boolean> webErrored = new LongSparseArray<>();
    /** 加载耗时统计起点 */
    private final LongSparseArray<Long> loadStartAt = new LongSparseArray<>();
    private WebView currentWebView;

    private long splashShownAt;
    private final Runnable hideSplashRunnable = this::hideSplash;
    private final FileSelection fileSelection = new FileSelection();
    private boolean fullscreen;
    private float edgeStartX;
    private long edgeDownAt;

    /** M4: 登录态分块探测。androidx Profile API 强制主线程调用，无法后台化；
     *  改为主线程分块（每块 20 个账号，块间让出一帧），账号多时不阻塞 UI，脏标记合并请求 */
    private static final int PROBE_CHUNK = 20;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean probeRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean probeDirty =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private List<Account> probeQueue;
    private int probeIndex;
    private boolean probeChanged;

    private final Runnable probeStep = this::probeStep;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!supportsProfiles()) {
            showWebViewUpgradeDialog();
            return;
        }

        setContentView(R.layout.activity_main);
        try {
            store = new AccountsStore(this);
            checkStoreNotice();
            profileStore = ProfileStore.getInstance();
            // 清理必须发生在任何 getProfile/setProfile/登录态探测之前。
            processPendingDeletes();
            store.reserveProfileIds(profileStore.getAllProfileNames());
        } catch (RuntimeException e) {
            new AlertDialog.Builder(this).setTitle("无法读取账号数据")
                    .setMessage("请检查可用存储空间后重试。原有数据不会被覆盖。")
                    .setPositiveButton(R.string.btn_exit, (dialog, which) -> finish()).show();
            return;
        }

        drawerLayout = findViewById(R.id.drawerLayout);
        drawerContent = findViewById(R.id.drawerContent);
        drawerLayout.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(dp(360), Math.max(0, r - l - dp(64)));
            if (width > 0 && drawerContent.getLayoutParams().width != width) {
                ViewGroup.LayoutParams params = drawerContent.getLayoutParams();
                params.width = width;
                drawerContent.setLayoutParams(params);
            }
        });
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerClosed(View drawer) { dismissSearchKeyboard(); }
        });
        rail = findViewById(R.id.rail);
        edgeSwipe = findViewById(R.id.edgeSwipe);
        railAvatars = findViewById(R.id.railAvatars);
        accountList = findViewById(R.id.accountList);
        searchInput = findViewById(R.id.drawerSearch);
        drawerEmpty = findViewById(R.id.drawerEmpty);
        accCount = findViewById(R.id.accCount);
        webContainer = findViewById(R.id.webContainer);
        errorOverlay = findViewById(R.id.errorOverlay);
        errorMsg = findViewById(R.id.errorMsg);
        emptyState = findViewById(R.id.emptyState);
        splash = findViewById(R.id.splash);
        splashName = findViewById(R.id.splashName);

        findViewById(R.id.btnRailExpand).setOnClickListener(v ->
                drawerLayout.openDrawer(drawerContent));
        findViewById(R.id.btnCollapse).setOnClickListener(v ->
                drawerLayout.closeDrawer(drawerContent));
        findViewById(R.id.btnRailAdd).setOnClickListener(v -> addAccount());
        findViewById(R.id.btnAddAccount).setOnClickListener(v -> addAccount());
        findViewById(R.id.btnEmptyAdd).setOnClickListener(v -> addAccount());
        findViewById(R.id.btnFullscreen).setOnClickListener(v -> enterFullscreen());

        findViewById(R.id.btnRetry).setOnClickListener(v -> {
            if (store.getCurrentId() > 0) {
                webErrored.put(store.getCurrentId(), true);
                switchTo(store.getCurrentId(), true);
            }
        });

        findViewById(R.id.btnErrorConfig).setOnClickListener(v -> {
            if (store.getCurrentId() > 0) openConfig(store.getCurrentId());
        });
        findViewById(R.id.searchClear).setOnClickListener(v -> searchInput.setText(""));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            dismissSearchKeyboard();
            return true;
        });

        // 搜索框：输入即时按名称 + 标签过滤
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                findViewById(R.id.searchClear).setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                searchInput.setPaddingRelative(dp(40), dp(11), dp(s.length() == 0 ? 12 : 48), dp(11));
                mainHandler.removeCallbacks(renderAccountsRunnable);
                mainHandler.postDelayed(renderAccountsRunnable, 100);
            }
        });

        edgeSwipe.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    edgeStartX = event.getX();
                    edgeDownAt = SystemClock.uptimeMillis();
                    return true;
                case MotionEvent.ACTION_UP: {
                    float dx = event.getX() - edgeStartX;
                    boolean swipeIn = dx > dp(24);
                    boolean tap = Math.abs(dx) < dp(8)
                            && (SystemClock.uptimeMillis() - edgeDownAt) < 500;
                    if (swipeIn || tap) exitFullscreen();
                    return true;
                }
                default:
                    return true;
            }
        });

        initialized = true;
        renderAccounts();

        // 恢复上次的全屏状态；调试 extra 优先
        if (store.isFullscreen()) enterFullscreen();

        // 调试入口：am start --ez show_drawer true / --el start_account <id> / --ez fullscreen true
        Intent launchIntent = getIntent();
        if (initialized && launchIntent != null) handleLaunchExtras(launchIntent);
    }

    boolean supportsProfiles() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
    }

    @Override
    protected void onNewIntent(Intent launchIntent) {
        super.onNewIntent(launchIntent);
        // 应用已在前台时 am start 的 extra 走这里，同样要生效
        setIntent(launchIntent);
        if (initialized && launchIntent != null) handleLaunchExtras(launchIntent);
    }

    private void handleLaunchExtras(Intent launchIntent) {
        // M1: 调试/自动化参数仅 debug 构建响应；release 对 intent extra 不做任何动作，
        // 避免第三方应用通过显式 intent 操纵账号配置（如改写代理）。
        if (!LaunchCommands.enabled(BuildConfig.DEBUG)) return;
        LaunchCommands cmd = LaunchCommands.fromIntent(launchIntent);
        // 先切账号（switchTo 会关抽屉），再处理全屏，最后开抽屉，保证终态符合预期
        if (cmd.startAccount >= 0 && store.findById(cmd.startAccount) != null) {
            switchTo(cmd.startAccount, true);
        }
        if (cmd.hasFullscreen) {
            if (cmd.fullscreen) {
                enterFullscreen();
            } else {
                exitFullscreen();
            }
        }
        if (cmd.showDrawer) {
            drawerLayout.openDrawer(drawerContent);
        }
        if (cmd.hasOpenConfig) {
            long cfgId = cmd.openConfigId >= 0 ? cmd.openConfigId : store.getCurrentId();
            if (cfgId >= 0) {
                openConfig(cfgId, cmd.proxyPreset, cmd.proxyAutoTest, cmd.proxySave);
            }
        }
        if (cmd.searchQuery != null) {
            searchInput.setText(cmd.searchQuery);
        }
        if (cmd.dumpAuth) {
            dumpAuthState();
        }
        if (cmd.addAccount) {
            addAccount();
        }
        if (cmd.hasDelAccount && cmd.delAccountId >= 0 && store.findById(cmd.delAccountId) != null) {
            deleteAccount(cmd.delAccountId);
        }
    }

    private void dumpAuthState() {
        for (Account a : store.all()) {
            Profile p = profileStore.getProfile(profileName(a.id));
            String cookies = null;
            try {
                cookies = p != null ? p.getCookieManager().getCookie("https://muse.ai/") : null;
            } catch (Exception ignored) {
            }
            Log.i("MuseAuth", "acct=" + a.id + " name=" + a.name
                    + " cookies=" + cookieNames(cookies));
            WebView wv = webViews.get(a.id);
            if (wv != null) {
                wv.evaluateJavascript(
                        "(function(){try{return 'LS:'+(Object.keys(localStorage).join(',')||'-')"
                                + "+'|CK:'+document.cookie.split(';').map(function(c){return c.split('=')[0].trim()}).join(',')"
                                + "}catch(e){return 'ERR'}})()",
                        v -> Log.i("MuseAuth", "acct=" + a.id + " js=" + v));
            } else {
                Log.i("MuseAuth", "acct=" + a.id + " js=<no webview>");
            }
        }
    }

    private static String cookieNames(String header) {
        if (header == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (String c : header.split(";")) {
            c = c.trim();
            int eq = c.indexOf('=');
            sb.append(eq > 0 ? c.substring(0, eq) : c).append(' ');
        }
        return sb.append(']').toString();
    }

    // ---------- 全屏模式 ----------

    private void enterFullscreen() {
        if (fullscreen) return;
        fullscreen = true;
        store.setFullscreen(true);
        rail.setVisibility(View.GONE);
        edgeSwipe.setVisibility(View.VISIBLE);
        // 锁定抽屉，避免左缘手势先被抽屉拦截
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        drawerLayout.closeDrawer(drawerContent);
    }

    private void exitFullscreen() {
        if (!fullscreen) return;
        fullscreen = false;
        store.setFullscreen(false);
        rail.setVisibility(View.VISIBLE);
        edgeSwipe.setVisibility(View.GONE);
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
    }

    // ---------- WebView（每账号独立实例） ----------

    private WebView getOrCreateWebView(final Account a, final ProxySpec proxy) {
        WebView wv = webViews.get(a.id);
        if (wv != null) return wv;

        wv = new WebView(this);
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wv.setBackgroundColor(0xFF121316);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // 去掉 WebView 标识（; wv / Version/4.0），否则 muse.ai 会把页面重定向到 Play 商店
        String ua = s.getUserAgentString();
        if (ua != null) {
            String chromeUa = ua.replace("; wv", "");
            chromeUa = chromeUa.replaceAll("\\s*Version/[\\d.]+", "");
            s.setUserAgentString(chromeUa);
        }

        wv.setWebViewClient(new WebViewClient() {
            private boolean authenticated;

            private boolean live(WebView view) {
                return initialized && webViews.snapshot().get(a.id) == view && store.findById(a.id) != null;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!live(view)) return;
                authenticated = false;
                webLoaded.put(a.id, false);
                if (webErrored.get(a.id, false)) {
                    // 新一次加载开始：清错误标记，失败会由 onReceivedError 重新置位
                    webErrored.put(a.id, false);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!live(view) || interruptedLoads.contains(a.id)) return;
                webLoaded.put(a.id, !webErrored.get(a.id, false));
                Long st = loadStartAt.get(a.id);
                if (st != null) {
                    Log.i("MusePerf", "load end id=" + a.id + " cost="
                            + (SystemClock.uptimeMillis() - st) + "ms errored="
                            + webErrored.get(a.id, false));
                    loadStartAt.remove(a.id);
                }
                refreshLogged();
                if (a.id != store.getCurrentId() || !resumed) return;
                if (webErrored.get(a.id, false)) {
                    showErrorOverlay(null); // 错误页已渲染，保持遮罩等用户重试
                } else {
                    hideErrorOverlay();
                }
                long elapsed = SystemClock.uptimeMillis() - splashShownAt;
                webContainer.postDelayed(hideSplashRunnable,
                        Math.max(0, MIN_SPLASH_MS - elapsed));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (!live(view) || !request.isForMainFrame()) return;
                webErrored.put(a.id, true);
                Log.i("MusePerf", "load error id=" + a.id
                        + " desc=" + error.getDescription());
                if (a.id == store.getCurrentId()) {
                    showErrorOverlay(error.getDescription() != null
                            ? error.getDescription().toString() : null);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                             WebResourceResponse response) {
                if (!live(view) || !request.isForMainFrame()) return;
                webErrored.put(a.id, true);
                if (a.id == store.getCurrentId()) showErrorOverlay("HTTP " + response.getStatusCode());
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler auth, String host, String realm) {
                if (live(view) && !authenticated && proxy.authenticates(host)) {
                    authenticated = true;
                    auth.proceed(proxy.username, proxy.password);
                } else auth.cancel();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (!live(view)) return true;
                boolean wasCurrent = currentWebView == view;
                destroyWebView(a.id, webViews.remove(a.id), true);
                if (wasCurrent && resumed) mainHandler.post(() -> {
                    if (initialized && resumed && store.getCurrentId() == a.id) switchTo(a.id, false);
                });
                toast(getString(R.string.toast_webview_recovered));
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri u = Uri.parse(url);
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (!resumed || view != currentWebView) return false;
                fileSelection.begin(view, callback);
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileSelection.cancel();
                    toast(getString(R.string.toast_no_filechooser));
                    return true;
                }
            }
        });

        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            if (!initialized || !resumed || store.getCurrentId() != a.id || store.findById(a.id) == null) return;
            if (!"https".equalsIgnoreCase(Uri.parse(url).getScheme())) {
                toast("该链接不支持直接下载，请使用网页提供的 HTTPS 下载链接");
                return;
            }
            try {
                Profile profile = profileStore.getProfile(profileName(a.id));
                String cookie = profile == null ? null : profile.getCookieManager().getCookie(url);
                if (!proxy.rule.isEmpty() || (cookie != null && !cookie.isEmpty())) {
                    AccountDownloads.start(this, a.id, url, userAgent, contentDisposition, mimeType, proxy);
                    return;
                }
                // 公共资源交给系统；不把 Cookie 加到会跨站重定向的固定请求头中。
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                String filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                req.setTitle(filename);
                if (mimeType != null) req.setMimeType(mimeType);
                req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, filename);
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(req);
                toast(getString(R.string.toast_download_started));
            } catch (Exception e) {
                toast("无法开始下载，请检查下载链接和存储空间");
            }
        });

        // Profile 必须在任何页面加载前设置，且每个 WebView 只能设置一次
        try {
            WebViewCompat.setProfile(wv, profileName(a.id));
            WebViewCompat.getProfile(wv).getServiceWorkerController().getServiceWorkerWebSettings()
                    .setBlockNetworkLoads(false);
        } catch (Exception e) {
            wv.destroy();
            toast("创建隔离环境失败：" + e.getMessage());
            return null;
        }

        loadStartAt.put(a.id, SystemClock.uptimeMillis());
        Log.i("MusePerf", "load start id=" + a.id);
        webContainer.addView(wv);
        webViews.put(a.id, wv);
        wv.loadUrl(START_URL);
        return wv;
    }

    private void showErrorOverlay(String msg) {
        hideSplash();
        if (msg != null && !msg.isEmpty()) {
            errorMsg.setText(msg);
        } else {
            errorMsg.setText(R.string.error_generic);
        }
        errorOverlay.setVisibility(View.VISIBLE);
    }

    private void hideErrorOverlay() {
        errorOverlay.setVisibility(View.GONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileSelection.pending()) {
            fileSelection.receive(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            if (resumed && currentWebView != null && !currentWebView.getSettings().getBlockNetworkLoads()) {
                fileSelection.deliverTo(currentWebView);
            }
            return;
        }
        // 配置在 onResume 中先 reload，再渲染和应用代理。
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!initialized) return;
        resumed = true;
        try {
            store.reload();
            checkStoreNotice();
            cancelProbe();
            renderAccounts();
            long id = store.getCurrentId();
            if (id > 0) switchTo(id, false);
            refreshLogged();
        } catch (RuntimeException e) {
            freezePages();
            showErrorOverlay("无法读取账号数据，请重试");
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        ++switchGeneration;
        if (initialized) {
            freezePages();
            cancelProbe();
            // 只刷新当前已加载 Profile，避免逐个加载全部账号并同步写盘。
            if (currentWebView != null) {
                try { WebViewCompat.getProfile(currentWebView).getCookieManager().flush(); }
                catch (RuntimeException ignored) { }
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        initialized = false;
        resumed = false;
        ++switchGeneration;
        mainHandler.removeCallbacksAndMessages(null);
        if (webContainer != null) webContainer.removeCallbacks(hideSplashRunnable);
        for (Map.Entry<Long, WebView> entry : webViews.snapshot().entrySet()) {
            destroyWebView(entry.getKey(), webViews.remove(entry.getKey()));
        }
        cancelFileChooser();
        probeQueue = null;
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (!initialized || level < TRIM_MEMORY_RUNNING_LOW) return;
        for (Map.Entry<Long, WebView> entry : webViews.snapshot().entrySet()) {
            if (entry.getValue() != currentWebView) destroyWebView(entry.getKey(), webViews.remove(entry.getKey()));
        }
    }

    @Override
    public void onBackPressed() {
        if (!initialized) { finish(); return; }
        if (fullscreen) exitFullscreen();
        else if (drawerLayout.isDrawerOpen(drawerContent)) drawerLayout.closeDrawer(drawerContent);
        else if (currentWebView != null && currentWebView.canGoBack()) currentWebView.goBack();
        else moveTaskToBack(true);
    }

    private void cancelFileChooser() {
        fileSelection.cancel();
    }

    private void cancelProbe() {
        mainHandler.removeCallbacks(probeStep);
        probeQueue = null;
        probeRunning.set(false);
        probeDirty.set(false);
    }

    private void freezePages() {
        for (Map.Entry<Long, WebView> entry : webViews.snapshot().entrySet()) {
            WebView view = entry.getValue();
            if (!webLoaded.get(entry.getKey(), false)) interruptedLoads.add(entry.getKey());
            view.onPause();
            view.getSettings().setBlockNetworkLoads(true);
            view.stopLoading();
            try { WebViewCompat.getProfile(view).getServiceWorkerController().getServiceWorkerWebSettings()
                    .setBlockNetworkLoads(true); } catch (RuntimeException ignored) { }
        }
    }

    private void destroyWebView(long id, WebView view) {
        destroyWebView(id, view, false);
    }

    private void destroyWebView(long id, WebView view, boolean rendererGone) {
        webLoaded.remove(id);
        webErrored.remove(id);
        loadStartAt.remove(id);
        interruptedLoads.remove(id);
        if (view == null) return;
        fileSelection.cancelFor(view);
        if (currentWebView == view) currentWebView = null;
        webContainer.removeView(view);
        if (!rendererGone) {
            view.stopLoading();
            view.setWebViewClient(new WebViewClient());
            view.setWebChromeClient(null);
        }
        view.destroy();
    }

    // ---------- 账号操作 ----------

    private void openConfig(long id) {
        openConfig(id, null, false, false);
    }

    private void openConfig(long id, String proxyPreset, boolean autoTest, boolean autoSave) {
        Intent i = new Intent(this, AccountConfigActivity.class);
        i.putExtra("account_id", id);
        if (proxyPreset != null) {
            i.putExtra("proxy_preset", proxyPreset);
            i.putExtra("proxy_autotest", autoTest);
            i.putExtra("proxy_save", autoSave);
        }
        startActivityForResult(i, REQ_CONFIG);
    }

    private void addAccount() {
        // 新环境继承当前账号的代理：国内直连 muse.ai 不通，空代理的新环境必然卡在加载（问题 2）
        Account prev = store.findById(store.getCurrentId());
        final Account a;
        try { a = store.addDefault(prev != null ? prev.proxy : null); }
        catch (RuntimeException e) { toast("创建账号失败，请检查可用存储空间"); return; }
        switchTo(a.id, true);
        toast(getString(R.string.toast_created, a.name));
    }

    private void switchTo(long id, boolean animate) {
        if (!initialized) return;
        Account account = store.findById(id);
        if (account == null) return;
        final long token = ++switchGeneration;
        freezePages();
        for (WebView view : webViews.snapshot().values()) view.setVisibility(View.GONE);
        currentWebView = null;
        final ProxySpec proxy;
        try {
            store.setCurrent(id);
            renderAccounts();
            proxy = ProxySpec.parse(account.proxy);
        } catch (RuntimeException e) {
            showErrorOverlay("无法切换账号，请检查账号数据和代理配置");
            return;
        }
        if (!resumed) return;
        // 只有相同代理的页面可继续驻留；出口变化时先关闭旧页面连接。
        if (!Objects.equals(appliedProxy, proxy.value)) {
            for (Map.Entry<Long, WebView> entry : webViews.snapshot().entrySet()) {
                destroyWebView(entry.getKey(), webViews.remove(entry.getKey()));
            }
        }
        hideErrorOverlay();
        if (animate) showSplashFor(account);
        drawerLayout.closeDrawer(drawerContent);
        ProxyHelper.apply(proxy.value, error -> {
            if (!initialized || !resumed || token != switchGeneration || store.getCurrentId() != id) return;
            if (error != null) { showErrorOverlay(error); return; }
            appliedProxy = proxy.value;
            Account latest = store.findById(id);
            if (latest == null) return;
            WebView target = webViews.get(id);
            boolean existed = target != null;
            if (target == null) target = getOrCreateWebView(latest, proxy);
            if (target == null) { showErrorOverlay("创建隔离环境失败，请重试"); return; }
            currentWebView = target;
            target.getSettings().setBlockNetworkLoads(false);
            WebViewCompat.getProfile(target).getServiceWorkerController().getServiceWorkerWebSettings()
                    .setBlockNetworkLoads(false);
            target.setVisibility(View.VISIBLE);
            target.onResume();
            for (LruAccountRegistry.Evicted<WebView> evicted : webViews.touchAndEvict(id, target)) {
                destroyWebView(evicted.id, evicted.value);
            }
            if (existed && (interruptedLoads.contains(id) || webErrored.get(id, false) || !webLoaded.get(id, false))) {
                interruptedLoads.remove(id);
                webErrored.put(id, false);
                loadStartAt.put(id, SystemClock.uptimeMillis());
                target.reload();
            }
            if (webLoaded.get(id, false)) hideSplash();
            fileSelection.deliverTo(target);
        });
    }

    private void showAccountMenu(View anchor, Account account) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.cd_config);
        menu.getMenu().add(0, 2, 1, R.string.cd_rename);
        menu.getMenu().add(0, 3, 2, R.string.cd_delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) openConfig(account.id);
            else if (item.getItemId() == 2) showRenameDialog(account);
            else new AlertDialog.Builder(this).setTitle(R.string.cd_delete)
                    .setMessage(getString(R.string.confirm_delete_fmt, account.name))
                    .setNegativeButton(R.string.btn_cancel, null)
                    .setPositiveButton(R.string.btn_delete, (dialog, which) -> deleteAccount(account.id))
                    .show();
            return true;
        });
        menu.show();
    }

    private void showRenameDialog(Account account) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(account.name);
        input.setHint(R.string.rename_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setMinHeight(dp(52));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(24), dp(8), dp(24), 0);
        container.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.cd_rename)
                .setView(container).setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.config_save, null).create();
        dialog.setOnShowListener(ignored -> {
            View.OnClickListener save = v -> {
                String name = TextRules.limit(input.getText().toString().trim(), 16);
                if (name.isEmpty()) { input.setError(getString(R.string.rename_hint)); return; }
                if (store.findById(account.id) == null) { dialog.dismiss(); return; }
                try { store.rename(account.id, name); }
                catch (RuntimeException e) { input.setError(getString(R.string.config_save_failed)); return; }
                renderAccounts();
                toast(getString(R.string.toast_renamed, name));
                dialog.dismiss();
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save);
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                save.onClick(v);
                return true;
            });
            input.requestFocus();
            input.selectAll();
        });
        dialog.show();
    }

    private void dismissSearchKeyboard() {
        if (searchInput == null) return;
        searchInput.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
    }

    private void deleteAccount(long id) {
        if (store.findById(id) == null) return;
        try {
            // 先原子记录删除任务和删除元数据，失败时仍保留账号及页面。
            store.remove(id);
        } catch (RuntimeException e) { toast("删除失败，请检查可用存储空间后重试"); return; }
        ++switchGeneration;
        cancelProbe();
        AccountDownloads.cancelAccount(id);
        freezePages();
        destroyWebView(id, webViews.remove(id));
        processPendingDeletes();
        renderAccounts();
        hideSplash();
        hideErrorOverlay();
        toast(getString(store.pendingDeletionIds().contains(id)
                ? R.string.toast_delete_pending : R.string.toast_deleted));
        long current = store.getCurrentId();
        if (current > 0) switchTo(current, true);
        else ProxyHelper.apply("", error -> { appliedProxy = null; });
    }

    private void processPendingDeletes() {
        for (long id : store.pendingDeletionIds()) {
            try {
                // false 表示不存在，同样已完成；这里绝不能先 getProfile。
                profileStore.deleteProfile(profileName(id));
                store.completeDeletion(id);
            } catch (RuntimeException e) {
                Log.i("MuseProfiles", "Profile cleanup deferred id=" + id);
            }
        }
    }

    private void refreshLogged() {
        if (!initialized || !resumed) return;
        if (!probeRunning.compareAndSet(false, true)) {
            probeDirty.set(true); // 已有一轮在跑，结束后补一轮
            return;
        }
        probeQueue = new ArrayList<>(store.all());
        probeIndex = 0;
        probeChanged = false;
        probeStep();
    }

    private void probeStep() {
        if (!initialized || !resumed) { cancelProbe(); return; }
        if (probeQueue == null || probeIndex >= probeQueue.size()) {
            probeQueue = null;
            probeRunning.set(false);
            if (probeDirty.compareAndSet(true, false)) refreshLogged();
            return;
        }
        int end = Math.min(probeIndex + PROBE_CHUNK, probeQueue.size());
        for (int i = probeIndex; i < end; i++) {
            Account a = store.findById(probeQueue.get(i).id);
            if (a == null) continue;
            try {
                Profile p = profileStore.getProfile(profileName(a.id));
                if (p != null && (a.id != store.getCurrentId() || currentWebView == null)) {
                    p.getServiceWorkerController().getServiceWorkerWebSettings().setBlockNetworkLoads(true);
                }
                // 不能用 hasCookies()：匿名访问也会落下 Cloudflare/Meta 埋点 cookie，一律误判已登录。
                // 只认 muse.ai 的登录会话 cookie hatch_sess。
                String cookies = p != null
                        ? p.getCookieManager().getCookie("https://muse.ai/") : null;
                boolean logged = TextRules.hasSession(cookies);
                if (logged != a.logged) {
                    a.logged = logged;
                    probeChanged = true;
                }
            } catch (Exception ignored) {
            }
        }
        probeIndex = end;
        if (probeChanged) {
            renderAccounts();
            probeChanged = false;
        }
        mainHandler.postDelayed(probeStep, 16); // 让出一帧再继续下一块
    }

    private String profileName(long id) {
        return "acct_" + id;
    }

    // ---------- 渲染 ----------

    private void renderAccounts() {
        if (!initialized) return;
        railAvatars.removeAllViews();
        accountList.removeAllViews();
        String q = query();
        int shown = 0;
        for (Account a : store.all()) {
            if (!matches(a, q)) continue;
            shown++;
            railAvatars.addView(makeRailAvatar(a));
            accountList.addView(makeDrawerRow(a));
        }
        accCount.setText(q.isEmpty() ? getString(R.string.accounts_count_fmt, store.all().size())
                : getString(R.string.search_results_fmt, shown, store.all().size()));
        emptyState.setVisibility(store.all().isEmpty() ? View.VISIBLE : View.GONE);
        drawerEmpty.setVisibility(!q.isEmpty() && shown == 0 ? View.VISIBLE : View.GONE);
    }

    private String query() {
        return searchInput != null ? searchInput.getText().toString().trim() : "";
    }

    /** 按 名称 + 标签 过滤（忽略大小写，空 query 全匹配） */
    private boolean matches(Account a, String q) {
        if (q.isEmpty()) return true;
        String key = q.toLowerCase(Locale.ROOT);
        if (a.name.toLowerCase(Locale.ROOT).contains(key)) return true;
        for (String t : a.tags) {
            if (t.toLowerCase(Locale.ROOT).contains(key)) return true;
        }
        return false;
    }

    private void showSplashFor(Account a) {
        splashName.setText(a.name);
        ((TextView) findViewById(R.id.splashTile)).setText(
                a.avatar == null || a.avatar.isEmpty() ? "m" : a.avatar);
        splash.setVisibility(View.VISIBLE);
        splashShownAt = SystemClock.uptimeMillis();
        webContainer.removeCallbacks(hideSplashRunnable);
        // 已加载过的账号不会再次触发 onPageFinished，直接短延时收起
        Boolean loaded = webLoaded.get(a.id);
        if (loaded != null && loaded) {
            hideSplash();
            return;
        }
        // 兜底：加载异常时也不会卡在过渡层
        webContainer.postDelayed(hideSplashRunnable, SPLASH_TIMEOUT_MS);
    }

    private void hideSplash() {
        webContainer.removeCallbacks(hideSplashRunnable);
        splash.setVisibility(View.GONE);
    }

    private View makeRailAvatar(Account a) {
        FrameLayout wrap = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        wrap.setLayoutParams(lp);
        wrap.setBackgroundResource(a.id == store.getCurrentId()
                ? R.drawable.bg_rail_active : R.drawable.bg_rail_btn);

        TextView tile = makeTile(dp(38), 20, a.avatar);
        wrap.addView(tile, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));

        View dot = new View(this);
        dot.setBackgroundResource(a.logged ? R.drawable.bg_dot_on : R.drawable.bg_dot_off);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(10), dp(10),
                Gravity.BOTTOM | Gravity.END);
        dlp.rightMargin = dp(1);
        dlp.bottomMargin = dp(1);
        wrap.addView(dot, dlp);

        wrap.setContentDescription(a.name + "，" + getString(a.logged ? R.string.status_logged : R.string.status_not_logged));
        wrap.setSelected(a.id == store.getCurrentId());
        wrap.setFocusable(true);
        wrap.setTooltipText(a.name);
        wrap.setOnClickListener(v -> switchTo(a.id, true));
        return wrap;
    }

    private View makeDrawerRow(Account a) {
        return AccountRow.create(accountList, a, a.id == store.getCurrentId(),
                v -> switchTo(a.id, true), v -> showAccountMenu(v, a));
    }

    private TextView makeTile(int sizePx, float textSp, String text) {
        TextView t = new TextView(this);
        t.setBackgroundResource(R.drawable.bg_mtile);
        t.setText(text == null || text.isEmpty() ? "m" : text);
        t.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        t.setTextColor(0xFF2F7CF6);
        t.setTextSize(textSp);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setLayoutParams(new ViewGroup.LayoutParams(sizePx, sizePx));
        return t;
    }

    // ---------- 其他 ----------

    private void showWebViewUpgradeDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.webview_need_update_title)
                .setMessage(R.string.webview_need_update_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.webview_update_btn, (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.google.android.webview")));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                                    "https://play.google.com/store/apps/details?id=com.google.android.webview")));
                        } catch (Exception ignored) {
                        }
                    }
                    finish();
                })
                .setNegativeButton(R.string.btn_exit, (d, w) -> finish())
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** M3: 账号存储损坏/自愈提示（takeLoadNotice 取走即清，不重复弹） */
    private void checkStoreNotice() {
        String n = store.takeLoadNotice();
        if ("recovered".equals(n)) {
            toast(getString(R.string.toast_store_recovered));
        } else if ("partial".equals(n)) {
            toast("部分账号数据异常，已保留原始内容并恢复有效账号");
        } else if ("corrupt".equals(n)) {
            toast(getString(R.string.toast_store_corrupt));
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
