package com.seekstars.muselauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** 账号列表 JSON 编解码（纯 Java，可单元测试）。读取对损坏数据有容错。 */
final class AccountJsonCodec {

    static final class Decoded {
        final List<Account> accounts = new ArrayList<>();
        boolean damaged;
        long highestId;
    }

    private AccountJsonCodec() {
    }

    /** 序列化失败（理论上不会发生）返回 null，调用方应跳过本次写盘以保留旧数据 */
    static String toJson(List<Account> accounts) {
        try {
            JSONArray arr = new JSONArray();
            Set<Long> ids = new HashSet<>();
            for (Account a : accounts) {
                if (a == null || a.id <= 0 || !ids.add(a.id) || a.name == null || a.tags == null) {
                    return null;
                }
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("name", a.name);
                o.put("avatar", a.avatar);
                o.put("proxy", a.proxy);
                JSONArray tagArr = new JSONArray();
                for (String t : a.tags) tagArr.put(t);
                o.put("tags", tagArr);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析账号列表。
     *
     * @return 顶层不是合法 JSON 数组时返回 null（由调用方走备份恢复）；
     *         数组合法但某一条目字段损坏时跳过该条目，其余照常返回。
     */
    static List<Account> fromJsonOrNull(String raw) {
        Decoded result = decode(raw);
        return result == null ? null : result.accounts;
    }

    static Decoded decode(String raw) {
        if (raw == null) return null;
        JSONArray arr;
        try {
            arr = new JSONArray(raw);
        } catch (Exception e) {
            return null;
        }
        Decoded out = new Decoded();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                // 即使其余字段损坏，也保留 ID 高水位，防止复用旧 Profile。
                Object rawId = o.get("id");
                long id = Long.parseLong(rawId.toString());
                if (id <= 0) throw new IllegalArgumentException("Invalid account id");
                out.highestId = Math.max(out.highestId, id);
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate account id");
                Account a = new Account(id, o.getString("name"));
                a.avatar = o.optString("avatar", "m");
                a.proxy = o.optString("proxy", "");
                JSONArray tagArr = o.optJSONArray("tags");
                if (tagArr != null) {
                    for (int j = 0; j < tagArr.length(); j++) {
                        String t = tagArr.optString(j);
                        if (!t.isEmpty()) a.tags.add(t);
                    }
                }
                out.accounts.add(a);
            } catch (Exception ignoredEntry) {
                out.damaged = true;
            }
        }
        return out;
    }
}
