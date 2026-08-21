package tv.blofy.commercial.ui.details;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.blofy.commercial.R;
import tv.blofy.commercial.core.ApiClient;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.databinding.ActivityDetailsBinding;
import tv.blofy.commercial.ui.player.PlayerActivity;

public final class DetailsActivity extends AppCompatActivity {
    private ActivityDetailsBinding binding;
    private ApiClient api;
    private CatalogStore store;
    private String type, id, name, extension;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Episode> episodes = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDetailsBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        api = new ApiClient(this); store = new CatalogStore(this);
        type = value("type", "movies"); id = value("id", ""); name = value("name", "BLOFY PLAYER"); extension = value("extension", "mp4");
        binding.title.setText(name); Glide.with(this).load(value("image", "")).into(binding.poster);
        binding.play.setOnClickListener(v -> play(id, name, type, extension));
        binding.favorite.setOnClickListener(v -> { boolean saved = store.toggleFavorite(type, id); binding.favorite.setText(saved ? "محفوظ بالمفضلة" : "إضافة للمفضلة"); });
        worker.execute(this::load);
    }

    private void load() {
        try {
            JSONObject data = api.get(("series".equals(type) ? "/api/series/" : "/api/movie/") + ApiClient.encode(id));
            runOnUiThread(() -> render(data));
        } catch (Exception error) {
            runOnUiThread(() -> { binding.progress.setVisibility(View.GONE); Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); });
        }
    }

    private void render(JSONObject data) {
        binding.progress.setVisibility(View.GONE);
        name = data.optString("name", name); extension = data.optString("extension", extension);
        binding.title.setText(name);
        binding.meta.setText((data.optString("year").isEmpty() ? "" : data.optString("year") + "  •  ") + (data.optString("rating").isEmpty() ? "" : "★ " + data.optString("rating") + "  •  ") + data.optString("genre"));
        binding.description.setText(data.optString("description", "لا يوجد وصف متاح."));
        Glide.with(this).load(data.optString("image", value("image", ""))).into(binding.poster);
        Glide.with(this).load(data.optString("backdrop")).centerCrop().into(binding.backdrop);
        if (!"series".equals(type)) { binding.play.requestFocus(); return; }
        JSONArray seasons = data.optJSONArray("seasons");
        if (seasons != null) for (int i = 0; i < seasons.length(); i++) {
            JSONObject season = seasons.optJSONObject(i); if (season == null) continue;
            String seasonNumber = season.optString("season", String.valueOf(i + 1));
            JSONArray values = season.optJSONArray("episodes");
            if (values == null) continue;
            for (int e = 0; e < values.length(); e++) {
                JSONObject episode = values.optJSONObject(e); if (episode != null) episodes.add(new Episode(seasonNumber, episode));
            }
        }
        binding.play.setVisibility(View.GONE);
        binding.episodesTitle.setVisibility(View.VISIBLE); binding.episodes.setVisibility(View.VISIBLE);
        binding.episodes.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)); binding.episodes.setAdapter(new EpisodeAdapter());
        binding.episodes.post(() -> { if (binding.episodes.getChildCount() > 0) binding.episodes.getChildAt(0).requestFocus(); });
    }

    private void play(String playId, String playName, String playType, String ext) {
        store.addHistory(type, id);
        startActivity(new Intent(this, PlayerActivity.class).putExtra("id", playId).putExtra("name", playName).putExtra("type", playType).putExtra("extension", ext));
    }

    private String value(String key, String fallback) { String value = getIntent().getStringExtra(key); return value == null || value.isEmpty() ? fallback : value; }

    private static final class Episode {
        final String season, id, title, extension;
        Episode(String season, JSONObject row) { this.season = season; id = row.optString("id"); title = row.optString("title", "الحلقة " + row.optInt("number")); extension = row.optString("extension", "mp4"); }
    }

    private final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) { Button button = new Button(parent.getContext()); button.setTextColor(getColor(R.color.blofy_text)); button.setTextSize(14); button.setAllCaps(false); button.setFocusable(true); button.setBackgroundResource(R.drawable.bg_home_card); button.setLayoutParams(new RecyclerView.LayoutParams(240, 105)); return new Holder(button); }
        @Override public void onBindViewHolder(Holder holder, int position) { Episode episode = episodes.get(position); holder.button.setText("الموسم " + episode.season + "\n" + episode.title); holder.button.setOnClickListener(v -> play(episode.id, name + " • " + episode.title, "episode", episode.extension)); }
        @Override public int getItemCount() { return episodes.size(); }
        final class Holder extends RecyclerView.ViewHolder { final Button button; Holder(Button button) { super(button); this.button = button; } }
    }

    @Override protected void onDestroy() { worker.shutdownNow(); if (store != null) store.close(); super.onDestroy(); }
}
