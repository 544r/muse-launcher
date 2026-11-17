package com.seekstars.muselauncher;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** 编辑账号；代理测活在隔离的服务进程中执行。 */
public class AccountConfigActivity extends AppCompatActivity {
    private static final int[] CLASH_PORTS = {7890, 7891, 7892};
    private AccountsStore store;
    private Account account;
    private TextView tile, detectBtn, testBtn, testStatus;
    private EditText avatarInput, nameInput, proxyInput, tagsInput;
    private TextView saveBtn;
    private View statusBox, progress;
    private ProxyProbeClient probe;
    private boolean testing;
    private boolean detecting;
    private boolean active;
    private int detectGeneration;
    private String testedProxy;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);
        findViewById(R.id.configRoot).requestFocus();
        try {
            store = new AccountsStore(this);
            account = store.findById(getIntent().getLongExtra("account_id", -1L));
        } catch (RuntimeException e) {
            Toast.makeText(this, "无法读取账号数据，请重试", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (account == null) { finish(); return; }
        probe = new ProxyProbeClient(this);
        tile = findViewById(R.id.cfgTile);
        avatarInput = findViewById(R.id.cfgAvatar);
        nameInput = findViewById(R.id.cfgName);
        proxyInput = findViewById(R.id.cfgProxy);
        tagsInput = findViewById(R.id.cfgTags);
        detectBtn = findViewById(R.id.cfgProxyDetect);
        testBtn = findViewById(R.id.cfgProxyTest);
        testStatus = findViewById(R.id.cfgProxyStatus);
        statusBox = findViewById(R.id.cfgStatusBox);
        progress = findViewById(R.id.cfgProxyProgress);
        saveBtn = findViewById(R.id.cfgSave);
        ((TextView) findViewById(R.id.cfgAccountName)).setText(account.name);
        findViewById(R.id.cfgBack).setOnClickListener(v -> finish());
        saveBtn.setOnClickListener(v -> save());
        testBtn.setOnClickListener(v -> testProxy(false, false));
        detectBtn.setOnClickListener(v -> detectProxy());
        avatarInput.setText(account.avatar);
        nameInput.setText(account.name);
        proxyInput.setText(account.proxy);
        tagsInput.setText(TextUtils.join(", ", account.tags));
        updateTile();
        avatarInput.addTextChangedListener(watcher(this::updateTile));
        proxyInput.addTextChangedListener(watcher(() -> {
            testedProxy = null;
            if (testing) {
                probe.cancel();
                setTesting(false);
            }
            showStatus("", R.color.text_muted);
        }));
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            String preset = getIntent().getStringExtra("proxy_preset");
            if (preset != null) {
                proxyInput.setText(preset);
                proxyInput.post(() -> {
                    if (!active) return;
                    if (getIntent().getBooleanExtra("proxy_save", false)) {
                        if (preset.trim().isEmpty()) persist("", false);
                        else testProxy(true, false);
                    } else if (getIntent().getBooleanExtra("proxy_autotest", false)) {
                        testProxy(false, false);
                    }
                });
            }
        }
    }

    private static TextWatcher watcher(Runnable changed) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable text) { changed.run(); }
        };
    }

    @Override protected void onStart() { super.onStart(); active = true; }
    @Override protected void onStop() {
        active = false;
        ++detectGeneration;
        boolean wasDetecting = detecting;
        detecting = false;
        if (probe != null) {
            probe.cancel();
            if (testing) {
                setTesting(false);
                showStatus(getString(R.string.config_proxy_cancelled), R.color.text_muted);
            } else if (wasDetecting) {
                showStatus(getString(R.string.config_proxy_cancelled), R.color.text_muted);
            }
            updateBusyViews();
        }
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (probe != null) probe.cancel();
        super.onDestroy();
    }

    private void updateTile() {
        String text = TextRules.limit(avatarInput.getText().toString().trim(), 2);
        tile.setText(text.isEmpty() ? "m" : text);
    }

    private void save() {
        if (testing || detecting) return;
        try {
            String proxy = ProxySpec.parse(proxyInput.getText().toString()).value;
            String previous;
            try { previous = ProxySpec.parse(account.proxy).value; }
            catch (IllegalArgumentException e) { previous = account.proxy; }
            if (proxy.isEmpty() || proxy.equals(previous) || proxy.equals(testedProxy)) persist(proxy, true);
            else testProxy(true, true);
        } catch (IllegalArgumentException e) { showStatus(e.getMessage(), R.color.error); }
    }

    private void persist(String proxy, boolean close) {
        // 只更新此账号，重新读取其他账号的最新记录，避免配置页旧快照覆盖变更。
        try {
            store.reload();
            Account latest = store.findById(account.id);
            if (latest == null) { finish(); return; }
            latest.avatar = TextRules.limit(avatarInput.getText().toString().trim(), 2);
            if (latest.avatar.isEmpty()) latest.avatar = "m";
            String name = TextRules.limit(nameInput.getText().toString().trim(), 16);
            if (!name.isEmpty()) latest.name = name;
            latest.proxy = proxy;
            List<String> tags = new ArrayList<>();
            for (String input : tagsInput.getText().toString().split("[,，]")) {
                String tag = TextRules.limit(input.trim(), 8);
                if (!tag.isEmpty() && !tags.contains(tag)) tags.add(tag);
            }
            latest.tags = tags;
            store.save();
            account = latest;
            setResult(RESULT_OK);
            showStatus(getString(R.string.config_proxy_saved), R.color.green);
            if (close) finish();
        } catch (RuntimeException e) {
            showStatus(getString(R.string.config_save_failed), R.color.error);
        }
    }

    private void setTesting(boolean value) {
        testing = value;
        updateBusyViews();
    }

    private void updateBusyViews() {
        boolean busy = testing || detecting;
        for (View field : new View[]{avatarInput, nameInput, proxyInput, tagsInput}) {
            field.setEnabled(!busy);
            field.setAlpha(busy ? 0.6f : 1f);
        }
        saveBtn.setEnabled(!busy);
        testBtn.setEnabled(!busy);
        detectBtn.setEnabled(!busy);
        testBtn.setText(testing ? R.string.config_proxy_testing : R.string.config_proxy_test);
        saveBtn.setText(busy ? R.string.config_busy : R.string.config_save);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void showStatus(CharSequence message, int color) {
        testStatus.setText(message);
        testStatus.setTextColor(getColor(color));
        statusBox.setVisibility(TextUtils.isEmpty(message) ? View.GONE : View.VISIBLE);
        if (!TextUtils.isEmpty(message)) statusBox.post(() -> {
            if (!active || isFinishing()) return;
            statusBox.requestRectangleOnScreen(new android.graphics.Rect(
                    0, 0, statusBox.getWidth(), statusBox.getHeight()), false);
        });
    }

    private void testProxy(boolean saveAfter, boolean closeAfter) {
        if (testing || detecting || !active) return;
        final String candidate;
        try { candidate = ProxySpec.parse(proxyInput.getText().toString()).value; }
        catch (IllegalArgumentException e) { showStatus(e.getMessage(), R.color.error); return; }
        if (candidate.isEmpty()) { showStatus(getString(R.string.config_proxy_empty), R.color.text_muted); return; }
        setTesting(true);
        testedProxy = null;
        showStatus(getString(R.string.config_proxy_connecting), R.color.text_muted);
        probe.test(candidate, (ok, error, elapsed) -> {
            if (!active || isFinishing() || isDestroyed()) return;
            setTesting(false);
            String current;
            try { current = ProxySpec.parse(proxyInput.getText().toString()).value; }
            catch (IllegalArgumentException e) { return; }
            if (!candidate.equals(current)) return;
            if (ok) {
                testedProxy = candidate;
                showStatus(getString(R.string.config_proxy_ok) + "（" + elapsed + " ms）", R.color.green);
                if (saveAfter) persist(candidate, closeAfter);
            } else {
                testedProxy = null;
                showStatus(getString(R.string.config_proxy_fail) + error, R.color.error);
            }
        });
    }

    private void detectProxy() {
        if (detecting || testing) return;
        detecting = true;
        int token = ++detectGeneration;
        updateBusyViews();
        showStatus(getString(R.string.config_proxy_detecting), R.color.text_muted);
        new Thread(() -> {
            int found = 0;
            for (int port : CLASH_PORTS) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
                    found = port;
                    break;
                } catch (Exception ignored) { }
            }
            final int port = found;
            runOnUiThread(() -> {
                if (!active || token != detectGeneration || isDestroyed()) return;
                detecting = false;
                updateBusyViews();
                if (port > 0) {
                    proxyInput.setText((port == 7892 ? "socks5://" : "http://") + "127.0.0.1:" + port);
                    showStatus(getString(R.string.config_proxy_detect_found, port), R.color.text_muted);
                } else showStatus(getString(R.string.config_proxy_detect_none), R.color.text_muted);
            });
        }, "proxy-port-probe").start();
    }
}
