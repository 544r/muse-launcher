package com.seekstars.muselauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable metadata for background list comparisons. Session/selection remain live UI state. */
final class AccountItem {
    final long id;
    final String name, avatar, tagsText;
    final List<String> tags;
    private final String searchText;

    AccountItem(Account account) {
        id = account.id;
        name = account.name;
        avatar = account.avatar == null || account.avatar.isEmpty() ? "m" : account.avatar;
        tags = Collections.unmodifiableList(new ArrayList<>(account.tags));
        tagsText = String.join(" · ", tags);
        searchText = (name + "\n" + String.join("\n", tags)).toLowerCase(Locale.ROOT);
    }

    boolean matches(String normalizedQuery) { return searchText.contains(normalizedQuery); }

    boolean sameContent(AccountItem other) {
        return Objects.equals(name, other.name) && Objects.equals(avatar, other.avatar)
                && tags.equals(other.tags);
    }
}
