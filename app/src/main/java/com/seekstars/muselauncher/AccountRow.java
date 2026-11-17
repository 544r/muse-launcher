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
        row.setBackgroundResource(current ? R.drawable.bg_row_active : R.drawable.bg_row);
        row.setSelected(current);
        ((TextView) row.findViewById(R.id.accountAvatar)).setText(
                TextUtils.isEmpty(account.avatar) ? "m" : account.avatar);
        ((TextView) row.findViewById(R.id.accountName)).setText(account.name);
        row.findViewById(R.id.accountCurrent).setVisibility(current ? View.VISIBLE : View.GONE);
        TextView status = row.findViewById(R.id.accountStatus);
        status.setText(account.logged ? R.string.account_session : R.string.account_no_session);
        TextView tags = row.findViewById(R.id.accountTags);
        tags.setText(TextUtils.join(" · ", account.tags));
        tags.setContentDescription(parent.getContext().getString(R.string.account_tags_fmt, tags.getText()));
        tags.setVisibility(account.tags.isEmpty() ? View.GONE : View.VISIBLE);
        View menu = row.findViewById(R.id.accountOptions);
        menu.setContentDescription(parent.getContext().getString(R.string.account_options_fmt, account.name));
        menu.setOnClickListener(options);
        row.setOnClickListener(select);
        return row;
    }
}
