package com.seekstars.muselauncher;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/** Bounded view pool; immutable metadata diffs run off the UI thread. */
class AccountAdapter extends ListAdapter<AccountItem, AccountAdapter.Holder> {
    private static final Object STATE = new Object();
    private static final DiffUtil.ItemCallback<AccountItem> DIFF = new DiffUtil.ItemCallback<AccountItem>() {
        @Override public boolean areItemsTheSame(@NonNull AccountItem a, @NonNull AccountItem b) { return a.id == b.id; }
        @Override public boolean areContentsTheSame(@NonNull AccountItem a, @NonNull AccountItem b) { return a.sameContent(b); }
    };
    private final boolean rail;
    private final LongPredicate logged;
    private final LongConsumer select;
    private final BiConsumer<View, Long> options;
    private final Map<Long, Integer> positions = new HashMap<>();
    private long currentId = -1;

    AccountAdapter(boolean rail, LongPredicate logged, LongConsumer select, BiConsumer<View, Long> options) {
        super(DIFF);
        this.rail = rail;
        this.logged = logged;
        this.select = select;
        this.options = options;
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    @Override public long getItemId(int position) { return getItem(position).id; }

    @Override public void onCurrentListChanged(@NonNull List<AccountItem> previous, @NonNull List<AccountItem> current) {
        positions.clear();
        for (int i = 0; i < current.size(); i++) positions.put(current.get(i).id, i);
    }

    void setCurrent(long id) {
        if (currentId == id) return;
        long old = currentId;
        currentId = id;
        refreshState(old);
        refreshState(id);
    }

    void refreshState(long id) {
        Integer position = positions.get(id);
        if (position != null) notifyItemChanged(position, STATE);
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
                rail ? R.layout.item_account_rail : R.layout.item_account, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        AccountItem item = getItem(position);
        holder.id = item.id;
        AccountRow.setText(holder.avatar, item.avatar);
        if (rail) {
            holder.target.setTooltipText(item.name);
            holder.nameValue = item.name;
        } else {
            AccountRow.setText(holder.name, item.name);
            AccountRow.setText(holder.tags, item.tagsText);
            holder.tags.setVisibility(item.tags.isEmpty() ? View.GONE : View.VISIBLE);
            holder.tags.setContentDescription(holder.itemView.getContext().getString(R.string.account_tags_fmt, item.tagsText));
            holder.menu.setContentDescription(holder.itemView.getContext().getString(R.string.account_options_fmt, item.name));
        }
        holder.bindState();
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && holder.id == getItemId(position)) holder.bindState();
        else onBindViewHolder(holder, position);
    }

    final class Holder extends RecyclerView.ViewHolder {
        final View target, menu, dot, current;
        final TextView avatar, name, tags, status;
        long id = RecyclerView.NO_ID;
        String nameValue;
        Holder(View view) {
            super(view);
            target = rail ? view.findViewById(R.id.railButton) : view;
            avatar = view.findViewById(R.id.accountAvatar);
            name = view.findViewById(R.id.accountName);
            tags = view.findViewById(R.id.accountTags);
            status = view.findViewById(R.id.accountStatus);
            current = view.findViewById(R.id.accountCurrent);
            menu = view.findViewById(R.id.accountOptions);
            dot = view.findViewById(R.id.accountDot);
            target.setOnClickListener(v -> { if (getBindingAdapterPosition() != RecyclerView.NO_POSITION) select.accept(id); });
            if (menu != null) menu.setOnClickListener(v -> {
                if (getBindingAdapterPosition() != RecyclerView.NO_POSITION) options.accept(v, id);
            });
        }
        void bindState() {
            boolean selected = id == currentId;
            boolean hasSession = logged.test(id);
            if (target.isSelected() != selected) {
                target.setSelected(selected);
                target.setBackgroundResource(rail
                        ? (selected ? R.drawable.bg_rail_active : R.drawable.bg_rail_btn)
                        : (selected ? R.drawable.bg_row_active : R.drawable.bg_row));
            }
            if (rail) {
                dot.setBackgroundResource(hasSession ? R.drawable.bg_dot_on : R.drawable.bg_dot_off);
                target.setContentDescription(nameValue + "，" + target.getContext().getString(
                        hasSession ? R.string.status_logged : R.string.status_not_logged));
            } else {
                current.setVisibility(selected ? View.VISIBLE : View.GONE);
                AccountRow.setText(status, target.getContext().getString(
                        hasSession ? R.string.account_session : R.string.account_no_session));
            }
        }
    }
}
