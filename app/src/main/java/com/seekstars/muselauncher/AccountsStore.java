package com.seekstars.muselauncher;

import android.content.Context;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 账号元数据、恢复留底和待删除 Profile 的原子持久化。 */
public class AccountsStore {
    private static final String PREFS = "accounts";
    private final KeyValueStore prefs;
    private final List<Account> accounts = new ArrayList<>();
    private final Set<Long> pendingDeletes = new LinkedHashSet<>();
    private long nextId = 1;
    private long currentId = -1;
    private String loadNotice;

    public AccountsStore(Context ctx) { this(new SharedPreferencesStore(ctx, PREFS)); }
    public AccountsStore(KeyValueStore store) { prefs = store; load(); }
    public synchronized void reload() { load(); }

    private void load() {
        loadNotice = null;
        String raw = prefs.getString("list", "[]");
        AccountJsonCodec.Decoded decoded = AccountJsonCodec.decode(raw);
        AccountJsonCodec.Decoded backup = AccountJsonCodec.decode(prefs.getString("list_prev", null));
        if (decoded == null || decoded.damaged) {
            if (raw != null && !raw.isEmpty()) prefs.putString("list_corrupt", raw);
            loadNotice = "partial";
            if (decoded == null) {
                decoded = backup;
                loadNotice = decoded == null ? "corrupt" : "recovered";
            }
        }
        long highest = decoded == null ? 0 : decoded.highestId;
        if (backup != null) highest = Math.max(highest, backup.highestId);
        Set<Long> deleting = readPending();
        for (long id : deleting) highest = Math.max(highest, id);
        List<Account> loaded = decoded == null ? new ArrayList<>() : decoded.accounts;
        loaded.removeIf(a -> deleting.contains(a.id));
        long storedNext = prefs.getLong("nextId", 1);
        long repairedNext = Math.max(1, Math.max(storedNext,
                highest == Long.MAX_VALUE ? Long.MAX_VALUE : highest + 1));
        long loadedCurrent = prefs.getLong("currentId", -1);
        boolean found = false;
        for (Account a : loaded) if (a.id == loadedCurrent) found = true;
        if (!found) loadedCurrent = loaded.isEmpty() ? -1 : loaded.get(0).id;
        // 读取与修复完成后才替换内存，读取失败不得清空账号。
        Map<Long, Boolean> sessions = new HashMap<>();
        for (Account account : accounts) sessions.put(account.id, account.logged);
        for (Account account : loaded) account.logged = sessions.getOrDefault(account.id, false);
        accounts.clear();
        accounts.addAll(loaded);
        pendingDeletes.clear();
        pendingDeletes.addAll(deleting);
        nextId = repairedNext;
        currentId = loadedCurrent;
        if (loadNotice != null || storedNext != nextId) {
            Map<String, Object> values = state();
            // 保留 list_prev 与 list_corrupt，修复主副本不会冲掉恢复依据。
            prefs.putAll(values);
        }
    }

    private Set<Long> readPending() {
        try {
            JSONArray array = new JSONArray(prefs.getString("pending_deletes", "[]"));
            Set<Long> result = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                long id = array.getLong(i);
                if (id <= 0) throw new IllegalArgumentException();
                result.add(id);
            }
            return result;
        } catch (Exception e) {
            // 删除记录损坏时停止，不能复活已删除账号或丢弃重试任务。
            throw new IllegalStateException("无法读取待清理账号记录", e);
        }
    }

    private Map<String, Object> state() {
        String json = AccountJsonCodec.toJson(accounts);
        if (json == null) throw new IllegalStateException("无法保存账号数据");
        Map<String, Object> values = new HashMap<>();
        values.put("list", json);
        values.put("nextId", nextId);
        values.put("currentId", currentId);
        values.put("pending_deletes", new JSONArray(pendingDeletes).toString());
        return values;
    }

    public synchronized void save() {
        Map<String, Object> values = state();
        String previous = prefs.getString("list", null);
        AccountJsonCodec.Decoded decoded = AccountJsonCodec.decode(previous);
        if (decoded != null && !decoded.damaged) values.put("list_prev", previous);
        prefs.putAll(values);
    }

    public synchronized String takeLoadNotice() {
        String notice = loadNotice;
        loadNotice = null;
        return notice;
    }

    public synchronized List<Account> all() {
        return Collections.unmodifiableList(new ArrayList<>(accounts));
    }

    public synchronized Account findById(long id) {
        for (Account a : accounts) if (a.id == id) return a;
        return null;
    }

    public synchronized long getCurrentId() { return currentId; }
    public synchronized Account addDefault() { return addDefault(null); }

    public synchronized Account addDefault(String proxy) {
        if (nextId == Long.MAX_VALUE) throw new IllegalStateException("账号编号已用尽，无法创建新账号");
        Account a = new Account(nextId, "Muse " + nextId);
        a.proxy = proxy == null ? "" : proxy;
        long before = currentId;
        nextId++;
        currentId = a.id;
        accounts.add(a);
        try {
            save();
        } catch (RuntimeException e) {
            accounts.remove(a);
            nextId--;
            currentId = before;
            throw e;
        }
        return a;
    }

    /** 已存在的孤立 Profile 和删除任务也占用 ID，绝不能分配给新账号。 */
    public synchronized void reserveProfileIds(List<String> names) {
        long max = nextId - 1;
        for (String name : names) {
            if (!name.startsWith("acct_")) continue;
            try { max = Math.max(max, Long.parseLong(name.substring(5))); }
            catch (NumberFormatException ignored) { }
        }
        long candidate = max == Long.MAX_VALUE ? Long.MAX_VALUE : max + 1;
        if (candidate > nextId) {
            prefs.putLong("nextId", candidate);
            nextId = candidate;
        }
    }

    public synchronized void rename(long id, String name) {
        Account a = findById(id);
        if (a == null) return;
        String before = a.name;
        a.name = name;
        try { save(); } catch (RuntimeException e) { a.name = before; throw e; }
    }

    public synchronized void remove(long id) {
        Account a = findById(id);
        if (a == null) return;
        int position = accounts.indexOf(a);
        long before = currentId;
        accounts.remove(a);
        if (currentId == id) currentId = accounts.isEmpty() ? -1 : accounts.get(0).id;
        pendingDeletes.add(id);
        try {
            Map<String, Object> values = state();
            // 删除不能在滚动备份中保留账号/账密，也不能通过恢复复活账号。
            values.put("list_prev", values.get("list"));
            values.put("list_corrupt", "");
            prefs.putAll(values);
        } catch (RuntimeException e) {
            accounts.add(position, a);
            currentId = before;
            pendingDeletes.remove(id);
            throw e;
        }
    }

    public synchronized List<Long> pendingDeletionIds() { return new ArrayList<>(pendingDeletes); }

    public synchronized void completeDeletion(long id) {
        if (!pendingDeletes.remove(id)) return;
        try {
            prefs.putString("pending_deletes", new JSONArray(pendingDeletes).toString());
        } catch (RuntimeException e) { pendingDeletes.add(id); throw e; }
    }

    public synchronized void setCurrent(long id) {
        if (currentId == id) return;
        if (findById(id) == null) throw new IllegalArgumentException("账号不存在");
        prefs.putLong("currentId", id);
        currentId = id;
    }

    public boolean isFullscreen() { return prefs.getBoolean("fullscreen", false); }
    public void setFullscreen(boolean value) { prefs.putBoolean("fullscreen", value); }
}
