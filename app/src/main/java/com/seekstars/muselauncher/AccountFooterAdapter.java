package com.seekstars.muselauncher;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

final class AccountFooterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private boolean emptySearch;
    AccountFooterAdapter() { setHasStableIds(true); }
    void setEmptySearch(boolean empty) {
        if (emptySearch == empty) return;
        emptySearch = empty;
        notifyItemChanged(0);
    }
    @Override public int getItemCount() { return 1; }
    @Override public long getItemId(int position) { return 0; }
    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_account_footer, parent, false)) {};
    }
    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        holder.itemView.findViewById(R.id.drawerEmpty).setVisibility(emptySearch ? View.VISIBLE : View.GONE);
    }
}
