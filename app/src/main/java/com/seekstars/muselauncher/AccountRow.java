package com.seekstars.muselauncher;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Account card with separate navigation and management actions. */
final class AccountRow {
    static View create(ViewGroup parent, Account account, boolean current,
                       View.OnClickListener select, View.OnClickListener options) {
        View row = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_account, parent, false);
        row.findViewById(R.id.accountOptions).setOnClickListener(options);
        row.setOnClickListener(select);
        bind(row, account, current);
        return row;
    }

    static void bind(View row, Account account, boolean current) {
        boolean changedSelection = row.isSelected() != current;
        if (changedSelection) {
            row.setBackgroundResource(current ? R.drawable.bg_row_active : R.drawable.bg_row);
            row.setSelected(current);
        }
        setText(row.findViewById(R.id.accountAvatar), TextUtils.isEmpty(account.avatar) ? "m" : account.avatar);
        setText(row.findViewById(R.id.accountName), account.name);
        row.findViewById(R.id.accountCurrent).setVisibility(current ? View.VISIBLE : View.GONE);
        TextView status = row.findViewById(R.id.accountStatus);
        setText(status, row.getContext().getString(account.logged ? R.string.account_session : R.string.account_no_session));
        TextView tags = row.findViewById(R.id.accountTags);
        setText(tags, TextUtils.join(" · ", account.tags));
        tags.setContentDescription(row.getContext().getString(R.string.account_tags_fmt, tags.getText()));
        tags.setVisibility(account.tags.isEmpty() ? View.GONE : View.VISIBLE);
        View menu = row.findViewById(R.id.accountOptions);
        menu.setContentDescription(row.getContext().getString(R.string.account_options_fmt, account.name));
    }

    static void setText(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }
}
