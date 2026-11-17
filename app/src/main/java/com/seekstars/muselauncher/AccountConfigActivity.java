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
    private View saveBtn;
    private ProxyProbeClient probe;
    private boolean testing;
    private boolean detecting;
    private boolean active;
    private int detectGeneration;
    private String testedProxy;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);
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
        saveBtn = findViewById(R.id.cfgSave);
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
            testStatus.setText("");
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
        detecting = false;
        if (probe != null) {
            probe.cancel();
            if (testing) {
                setTesting(false);
                testStatus.setText("测活已取消，请重新测试");
            }
            detectBtn.setEnabled(true);
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
        } catch (IllegalArgumentException e) { testStatus.setText(e.getMessage()); }
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
            testStatus.setText(R.string.config_proxy_saved);
            if (close) finish();
        } catch (RuntimeException e) {
            testStatus.setText("保存失败，请检查可用存储空间后重试");
        }
    }

    private void setTesting(boolean value) {
        testing = value;
        avatarInput.setEnabled(!value);
        nameInput.setEnabled(!value);
        proxyInput.setEnabled(!value);
        tagsInput.setEnabled(!value);
        saveBtn.setEnabled(!value);
        testBtn.setEnabled(!value);
        detectBtn.setEnabled(!value && !detecting);
        testBtn.setText(value ? R.string.config_proxy_testing : R.string.config_proxy_test);
    }

    private void testProxy(boolean saveAfter, boolean closeAfter) {
        if (testing || detecting || !active) return;
        final String candidate;
        try { candidate = ProxySpec.parse(proxyInput.getText().toString()).value; }
        catch (IllegalArgumentException e) { testStatus.setText(e.getMessage()); return; }
        if (candidate.isEmpty()) { testStatus.setText(R.string.config_proxy_empty); return; }
        setTesting(true);
        testedProxy = null;
        testStatus.setText("正在通过代理访问 muse.ai…");
        probe.test(candidate, (ok, error, elapsed) -> {
            if (!active || isFinishing() || isDestroyed()) return;
            setTesting(false);
            String current;
            try { current = ProxySpec.parse(proxyInput.getText().toString()).value; }
            catch (IllegalArgumentException e) { return; }
            if (!candidate.equals(current)) return;
            if (ok) {
                testedProxy = candidate;
                testStatus.setText(getString(R.string.config_proxy_ok) + "（" + elapsed + " ms）");
                if (saveAfter) persist(candidate, closeAfter);
            } else {
                testedProxy = null;
                testStatus.setText(getString(R.string.config_proxy_fail) + error);
            }
        });
    }

    private void detectProxy() {
        if (detecting || testing) return;
        detecting = true;
        int token = ++detectGeneration;
        detectBtn.setEnabled(false);
        testStatus.setText(R.string.config_proxy_detecting);
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
                detectBtn.setEnabled(true);
                if (port > 0) {
                    proxyInput.setText((port == 7892 ? "socks5://" : "http://") + "127.0.0.1:" + port);
                    testStatus.setText(getString(R.string.config_proxy_detect_found, port));
                } else testStatus.setText(R.string.config_proxy_detect_none);
            });
        }, "proxy-port-probe").start();
    }
}
