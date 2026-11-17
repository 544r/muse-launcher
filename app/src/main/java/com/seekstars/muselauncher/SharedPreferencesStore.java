package com.seekstars.muselauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** KeyValueStore 的 SharedPreferences 实现 */
public class SharedPreferencesStore implements KeyValueStore {

    private final SharedPreferences prefs;

    public SharedPreferencesStore(Context ctx, String name) {
        prefs = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @Override
    public String getString(String key, String defValue) {
        return prefs.getString(key, defValue);
    }

    @Override
    public long getLong(String key, long defValue) {
        return prefs.getLong(key, defValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return prefs.getBoolean(key, defValue);
    }

    @Override
    public void putAll(Map<String, Object> values) {
        Map<String, ?> before = prefs.getAll();
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, Object> en : values.entrySet()) {
            Object v = en.getValue();
            if (v instanceof Long) e.putLong(en.getKey(), (Long) v);
            else if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
            else if (v instanceof String) e.putString(en.getKey(), (String) v);
        }
        if (!e.commit()) {
            // commit 失败仍会改变 SharedPreferences 的进程内缓存，恢复这次涉及的键。
            SharedPreferences.Editor rollback = prefs.edit();
            for (String key : values.keySet()) {
                Object old = before.get(key);
                if (old instanceof Long) rollback.putLong(key, (Long) old);
                else if (old instanceof Boolean) rollback.putBoolean(key, (Boolean) old);
                else if (old instanceof String) rollback.putString(key, (String) old);
                else rollback.remove(key);
            }
            rollback.commit();
            throw new IllegalStateException("账号数据写入失败");
        }
    }

    @Override
    public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    @Override
    public void putLong(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }

    @Override
    public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }
}
