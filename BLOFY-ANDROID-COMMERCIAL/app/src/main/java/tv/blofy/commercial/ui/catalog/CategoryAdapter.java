package tv.blofy.commercial.ui.catalog;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import tv.blofy.commercial.data.CategoryRecord;
import tv.blofy.commercial.databinding.ItemCategoryBinding;

final class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.Holder> {
    interface Selected { void onSelected(CategoryRecord category); }
    interface MoveRight { boolean move(); }

    private final Selected selected;
    private final MoveRight moveRight;
    private final List<CategoryRecord> rows = new ArrayList<>();
    private String selectedId = "";

    CategoryAdapter(Selected selected, MoveRight moveRight) {
        this.selected = selected;
        this.moveRight = moveRight;
        setHasStableIds(true);
    }

    void submit(List<CategoryRecord> values, String wantedId) {
        rows.clear();
        if (values != null) rows.addAll(values);
        selectedId = wantedId == null ? "" : wantedId;
        notifyDataSetChanged();
    }

    void select(String id) {
        String next = id == null ? "" : id;
        if (next.equals(selectedId)) return;
        String old = selectedId;
        selectedId = next;
        int oldPosition = positionOf(old);
        int newPosition = positionOf(next);
        if (oldPosition >= 0) notifyItemChanged(oldPosition);
        if (newPosition >= 0) notifyItemChanged(newPosition);
    }

    boolean hasSelection() { return positionOf(selectedId) >= 0; }

    boolean focusSelected(RecyclerView recycler) {
        if (rows.isEmpty()) return false;
        int position = Math.max(0, positionOf(selectedId));
        RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(position);
        if (holder != null) return holder.itemView.requestFocus();
        recycler.scrollToPosition(position);
        recycler.post(() -> {
            RecyclerView.ViewHolder next = recycler.findViewHolderForAdapterPosition(position);
            if (next != null) next.itemView.requestFocus();
        });
        return true;
    }

    private int positionOf(String id) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) return i;
        return -1;
    }

    @Override public long getItemId(int position) { return rows.get(position).id.hashCode(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemCategoryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        CategoryRecord row = rows.get(position);
        boolean active = row.id.equals(selectedId);
        holder.binding.name.setText(row.name.isEmpty() ? "بدون تصنيف" : row.name);
        holder.binding.count.setText(row.count > 0 ? compact(row.count) : "");
        holder.binding.count.setVisibility(row.count > 0 ? View.VISIBLE : View.GONE);
        holder.binding.getRoot().setActivated(active);
        holder.binding.indicator.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        holder.binding.getRoot().setOnClickListener(view -> selected.onSelected(row));
        holder.binding.getRoot().setOnKeyListener((view, keyCode, event) ->
                event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        && moveRight.move());
    }

    @Override public int getItemCount() { return rows.size(); }

    private static String compact(int value) {
        if (value < 1000) return String.valueOf(value);
        if (value < 1_000_000) return String.format(java.util.Locale.US, "%.1fK", value / 1000f);
        return String.format(java.util.Locale.US, "%.1fM", value / 1_000_000f);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemCategoryBinding binding;
        Holder(ItemCategoryBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
