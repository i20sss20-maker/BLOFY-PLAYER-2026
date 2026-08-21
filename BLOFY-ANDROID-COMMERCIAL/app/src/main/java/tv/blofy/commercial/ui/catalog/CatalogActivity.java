package tv.blofy.commercial.ui.catalog;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.databinding.ActivityCatalogBinding;
import tv.blofy.commercial.databinding.ItemMediaBinding;
import tv.blofy.commercial.ui.details.DetailsActivity;
import tv.blofy.commercial.ui.player.PlayerActivity;

public final class CatalogActivity extends AppCompatActivity {
    private ActivityCatalogBinding binding;
    private CatalogStore store;
    private String type;
    private boolean favorites, history;
    private MediaAdapter adapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCatalogBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        store = new CatalogStore(this);
        type = getIntent().getStringExtra("type"); if (type == null) type = "";
        favorites = getIntent().getBooleanExtra("favorites", false); history = getIntent().getBooleanExtra("history", false);
        binding.title.setText(favorites ? "المفضلة" : history ? "سجل المشاهدة" : "live".equals(type) ? "البث المباشر" : "movies".equals(type) ? "الأفلام" : "المسلسلات");
        int columns = isTv() ? ("live".equals(type) ? 4 : 6) : ("live".equals(type) ? 1 : 2);
        binding.list.setLayoutManager(new GridLayoutManager(this, columns));
        adapter = new MediaAdapter(); binding.list.setAdapter(adapter); reload("");
        binding.search.setOnEditorActionListener((view, actionId, event) -> { if (actionId == EditorInfo.IME_ACTION_SEARCH) { reload(view.getText().toString()); return true; } return false; });
    }

    private boolean isTv() { return getPackageManager().hasSystemFeature("android.software.leanback"); }
    private void reload(String query) {
        List<MediaRecord> rows = store.media(type, "", query, favorites, history, 10_000);
        adapter.rows = rows; adapter.notifyDataSetChanged(); binding.empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (!rows.isEmpty()) binding.list.post(() -> binding.list.getChildAt(0).requestFocus());
    }

    private void open(MediaRecord item) {
        store.addHistory(item.type, item.id);
        Class<?> target = "live".equals(item.type) ? PlayerActivity.class : DetailsActivity.class;
        startActivity(new Intent(this, target).putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name).putExtra("extension", item.extension).putExtra("image", item.image));
    }

    private final class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.Holder> {
        List<MediaRecord> rows = new ArrayList<>();
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new Holder(ItemMediaBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)); }
        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            MediaRecord item = rows.get(position); h.binding.name.setText(item.name);
            h.binding.meta.setText((item.year.isEmpty() ? "" : item.year + " • ") + (item.rating.isEmpty() ? item.extension.toUpperCase(Locale.US) : "★ " + item.rating));
            Glide.with(CatalogActivity.this).load(item.image).centerCrop().into(h.binding.poster);
            h.binding.getRoot().setOnClickListener(v -> open(item));
            h.binding.getRoot().setOnLongClickListener(v -> { boolean saved = store.toggleFavorite(item.type, item.id); Toast.makeText(CatalogActivity.this, saved ? "تمت الإضافة للمفضلة" : "تمت الإزالة من المفضلة", Toast.LENGTH_SHORT).show(); if (favorites && !saved) reload(binding.search.getText().toString()); return true; });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder { final ItemMediaBinding binding; Holder(ItemMediaBinding binding) { super(binding.getRoot()); this.binding = binding; } }
    }

    @Override protected void onDestroy() { if (store != null) store.close(); super.onDestroy(); }
}
