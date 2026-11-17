package com.seekstars.muselauncher;

import android.view.View;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Keep account views attached during status changes; only change structure for filtering/add/remove. */
final class AccountViews {
    interface Factory { View create(Account account, boolean selected); }
    interface Binder { void bind(View view, Account account, boolean selected); }

    private static final class Entry {
        final View view;
        String name, avatar;
        List<String> tags;
        boolean logged, selected;
        Entry(View view, Account account, boolean selected) {
            this.view = view;
            remember(account, selected);
        }
        boolean changed(Account account, boolean current) {
            return selected != current || logged != account.logged
                    || !Objects.equals(name, account.name) || !Objects.equals(avatar, account.avatar)
                    || !tags.equals(account.tags);
        }
        void remember(Account account, boolean current) {
            name = account.name;
            avatar = account.avatar;
            tags = new ArrayList<>(account.tags);
            logged = account.logged;
            selected = current;
        }
    }

    private final LinearLayout parent;
    private final Factory factory;
    private final Binder binder;
    private final Map<Long, Entry> entries = new HashMap<>();

    AccountViews(LinearLayout parent, Factory factory, Binder binder) {
        this.parent = parent;
        this.factory = factory;
        this.binder = binder;
    }

    int update(List<Account> accounts, long currentId, Predicate<Account> matches) {
        Set<Long> live = new HashSet<>();
        Set<View> visible = new HashSet<>();
        List<View> ordered = new ArrayList<>();
        for (Account account : accounts) {
            live.add(account.id);
            if (!matches.test(account)) continue;
            boolean selected = account.id == currentId;
            Entry entry = entries.get(account.id);
            if (entry == null) {
                entry = new Entry(factory.create(account, selected), account, selected);
                entries.put(account.id, entry);
            } else if (entry.changed(account, selected)) {
                binder.bind(entry.view, account, selected);
                entry.remember(account, selected);
            }
            visible.add(entry.view);
            ordered.add(entry.view);
        }
        entries.keySet().retainAll(live);
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            if (!visible.contains(parent.getChildAt(i))) parent.removeViewAt(i);
        }
        for (int i = 0; i < ordered.size(); i++) {
            View view = ordered.get(i);
            if (parent.getChildAt(i) == view) continue;
            if (view.getParent() == parent) parent.removeView(view);
            parent.addView(view, i);
        }
        return ordered.size();
    }
}
