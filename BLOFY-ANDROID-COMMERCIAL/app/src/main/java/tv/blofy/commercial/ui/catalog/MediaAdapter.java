package tv.blofy.commercial.ui.catalog;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tv.blofy.commercial.R;
import tv.blofy.commercial.core.BlofyImageLoader;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.databinding.ItemMediaBinding;

final class MediaAdapter extends ListAdapter<MediaRecord, MediaAdapter.Holder> {
    interface Open { void open(MediaRecord item, int position); }
    interface Favorite { void toggle(MediaRecord item); }
    interface Focused { void focused(int position, MediaRecord item); }
    interface MoveLeft { boolean move(View card); }
    interface MoveUp { boolean move(int position); }

    private final boolean live;
    private final Open open;
    private final Favorite favorite;
    private final Focused focused;
    private final MoveLeft moveLeft;
    private final MoveUp moveUp;

    MediaAdapter(boolean live, Open open, Favorite favorite, Focused focused,
                 MoveLeft moveLeft, MoveUp moveUp) {
        super(DIFF);
        this.live = live;
        this.open = open;
        this.favorite = favorite;
        this.focused = focused;
        this.moveLeft = moveLeft;
        this.moveUp = moveUp;
        setHasStableIds(true);
    }

    List<MediaRecord> current() { return new ArrayList<>(getCurrentList()); }

    void submit(List<MediaRecord> rows, Runnable committed) {
        submitList(rows == null ? new ArrayList<>() : new ArrayList<>(rows), committed);
    }

    @Override public long getItemId(int position) {
        MediaRecord row = getItem(position);
        String key = row.type + ':' + row.id;
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < key.length(); index++) {
            hash ^= key.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemMediaBinding binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        if (live) {
            ViewGroup.LayoutParams params = binding.poster.getLayoutParams();
            params.height = Math.round(132 * parent.getResources().getDisplayMetrics().density);
            binding.poster.setLayoutParams(params);
        }
        return new Holder(binding);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        MediaRecord item = getItem(position);
        holder.binding.name.setText(item.name);
        if (live) {
            holder.binding.meta.setText(item.extension.isEmpty() ? "LIVE" : item.extension.toUpperCase(Locale.US));
        } else {
            holder.binding.meta.setText((item.year.isEmpty() ? "" : item.year + "  •  ")
                    + (item.rating.isEmpty() ? "" : "★ " + item.rating));
        }

        if (item.image.isEmpty()) {
            Glide.with(holder.binding.poster).clear(holder.binding.poster);
            holder.binding.poster.setImageResource(R.drawable.poster_placeholder);
        } else {
            BlofyImageLoader.poster(holder.binding.poster.getContext(), holder.binding.poster, item.image);
        }

        holder.binding.getRoot().setOnClickListener(view -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) open.open(getItem(adapterPosition), adapterPosition);
        });
        holder.binding.getRoot().setOnLongClickListener(view -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) favorite.toggle(getItem(adapterPosition));
            return true;
        });
        holder.binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> {
            view.animate()
                    .scaleX(hasFocus ? 1.055f : 1f)
                    .scaleY(hasFocus ? 1.055f : 1f)
                    .translationZ(hasFocus ? dp(view, 12) : 0)
                    .setDuration(120)
                    .start();
            if (hasFocus) {
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition != RecyclerView.NO_POSITION) focused.focused(adapterPosition, getItem(adapterPosition));
            }
        });
        holder.binding.getRoot().setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && moveLeft.move(view)) return true;
            int adapterPosition = holder.getBindingAdapterPosition();
            return keyCode == KeyEvent.KEYCODE_DPAD_UP
                    && adapterPosition != RecyclerView.NO_POSITION
                    && moveUp.move(adapterPosition);
        });
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.binding.getRoot().animate().cancel();
        holder.binding.getRoot().setScaleX(1f);
        holder.binding.getRoot().setScaleY(1f);
        holder.binding.getRoot().setTranslationZ(0);
        Glide.with(holder.binding.poster).clear(holder.binding.poster);
        super.onViewRecycled(holder);
    }

    private static float dp(View view, int value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemMediaBinding binding;
        Holder(ItemMediaBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }

    private static final DiffUtil.ItemCallback<MediaRecord> DIFF = new DiffUtil.ItemCallback<MediaRecord>() {
        @Override public boolean areItemsTheSame(@NonNull MediaRecord oldItem, @NonNull MediaRecord newItem) {
            return oldItem.type.equals(newItem.type) && oldItem.id.equals(newItem.id);
        }

        @Override public boolean areContentsTheSame(@NonNull MediaRecord oldItem, @NonNull MediaRecord newItem) {
            return oldItem.name.equals(newItem.name)
                    && oldItem.image.equals(newItem.image)
                    && oldItem.year.equals(newItem.year)
                    && oldItem.rating.equals(newItem.rating)
                    && oldItem.extension.equals(newItem.extension);
        }
    };
}
