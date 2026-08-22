package tv.blofy.commercial.ui.details;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import tv.blofy.commercial.core.BlofyImageLoader;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.databinding.ActivityDetailsBinding;
import tv.blofy.commercial.databinding.ItemEpisodeBinding;
import tv.blofy.commercial.databinding.ItemSeasonBinding;
import tv.blofy.commercial.provider.ProviderProfile;
import tv.blofy.commercial.provider.ProviderProfileStore;
import tv.blofy.commercial.provider.XtreamClient;
import tv.blofy.commercial.ui.player.PlayerActivity;

/** Local-first IBO-style details. Remote metadata must never crash the screen. */
public final class DetailsActivity extends LicensedActivity {
    private ActivityDetailsBinding binding;
    private CatalogStore store;
    private String type, id, name, extension, image, directSource;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final List<Season> seasons = new ArrayList<>();
    private SeasonAdapter seasonAdapter;
    private EpisodeAdapter episodeAdapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDetailsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        store = new CatalogStore(this);
        type = value("type", "movies");
        id = value("id", "");
        name = value("name", "BLOFY PLAYER");
        extension = value("extension", "mp4");
        image = value("image", "");
        directSource = value("direct_source", "");

        binding.title.setText(name);
        binding.description.setText("جاري جلب تفاصيل المحتوى…");
        safePoster(binding.poster, image);
        binding.back.setOnClickListener(v -> finish());
        binding.retry.setOnClickListener(v -> load());
        binding.favorite.setOnClickListener(v -> toggleFavoriteSafe());
        binding.play.setOnClickListener(v -> play(id, name, type, extension, directSource));
        if ("series".equals(type)) binding.play.setVisibility(View.GONE);
        else {
            binding.play.setVisibility(View.VISIBLE);
            binding.play.post(binding.play::requestFocus);
        }
        load();
    }

    private void toggleFavoriteSafe() {
        worker.execute(() -> {
            boolean saved = false;
            try { saved = store != null && store.toggleFavorite(type, id); }
            catch (Throwable ignored) { }
            final boolean value = saved;
            runOnUiThread(() -> { if (binding != null) binding.favorite.setText(value ? "♥  محفوظ" : "♡  المفضلة"); });
        });
    }

    private void load() {
        if (binding == null) return;
        final int request = generation.incrementAndGet();
        binding.errorPanel.setVisibility(View.GONE);
        binding.progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            try {
                ProviderProfile profile = ProviderProfileStore.load(this);
                if (profile == null || !profile.isXtream()) throw new Exception("بيانات Xtream غير متوفرة على الجهاز.");
                XtreamClient client = new XtreamClient(profile);
                JSONObject raw = "series".equals(type) ? client.seriesInfo(id) : client.movieInfo(id);
                if (raw == null) throw new Exception("المزوّد لم يرسل تفاصيل صالحة.");
                JSONObject data = "series".equals(type) ? normalizeSeries(raw) : normalizeMovie(raw);
                runOnUiThread(() -> {
                    if (request != generation.get() || isFinishing() || isDestroyed() || binding == null) return;
                    try { render(data); } catch (Throwable error) { showMetadataError(error); }
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (request != generation.get() || isFinishing() || isDestroyed() || binding == null) return;
                    showMetadataError(error);
                });
            }
        });
    }

    private void showMetadataError(Throwable error) {
        if (binding == null) return;
        binding.progress.setVisibility(View.GONE);
        if (!"series".equals(type)) {
            binding.description.setText("تعذر جلب الوصف من المزود، لكن الفيلم ما زال قابلًا للتشغيل.");
            binding.play.setVisibility(View.VISIBLE);
            binding.play.post(binding.play::requestFocus);
            return;
        }
        String message = error == null ? "" : error.getMessage();
        binding.error.setText(message == null || message.trim().isEmpty()
                ? "لم يرسل المزود مواسم أو حلقات صالحة لهذا المسلسل." : message);
        binding.errorPanel.setVisibility(View.VISIBLE);
        binding.retry.post(binding.retry::requestFocus);
    }

    private JSONObject normalizeMovie(JSONObject raw) throws Exception {
        JSONObject info = raw.optJSONObject("info"); if (info == null) info = new JSONObject();
        JSONObject movie = raw.optJSONObject("movie_data"); if (movie == null) movie = new JSONObject();
        JSONObject out = new JSONObject();
        out.put("name", movie.optString("name", info.optString("name", name)));
        out.put("extension", movie.optString("container_extension", extension));
        out.put("image", info.optString("movie_image", movie.optString("stream_icon", image)));
        out.put("backdrop", firstBackdrop(info));
        out.put("rating", info.optString("rating"));
        out.put("genre", info.optString("genre"));
        out.put("duration", info.optString("duration"));
        out.put("description", info.optString("plot", "لا يوجد وصف متاح."));
        out.put("year", year(info.optString("releasedate", info.optString("releaseDate", ""))));
        return out;
    }

    private JSONObject normalizeSeries(JSONObject raw) throws Exception {
        JSONObject info = raw.optJSONObject("info"); if (info == null) info = new JSONObject();
        JSONObject out = new JSONObject();
        out.put("name", info.optString("name", name));
        out.put("image", info.optString("cover", image));
        out.put("backdrop", firstBackdrop(info));
        out.put("rating", info.optString("rating"));
        out.put("genre", info.optString("genre"));
        out.put("description", info.optString("plot", "لا يوجد وصف متاح."));
        out.put("year", year(info.optString("releaseDate", info.optString("release_date", ""))));

        JSONArray seasonsOut = new JSONArray();
        Object episodesRaw = raw.opt("episodes");
        if (episodesRaw instanceof JSONObject) {
            JSONObject episodes = (JSONObject) episodesRaw;
            Iterator<String> keys = episodes.keys();
            while (keys.hasNext()) appendSeason(seasonsOut, keys.next(), episodes);
        } else if (episodesRaw instanceof JSONArray) {
            JSONArray flat = (JSONArray) episodesRaw;
            JSONObject grouped = new JSONObject();
            for (int i = 0; i < flat.length(); i++) {
                JSONObject row = flat.optJSONObject(i); if (row == null) continue;
                String season = row.optString("season", row.optString("season_num", "1"));
                JSONArray list = grouped.optJSONArray(season);
                if (list == null) { list = new JSONArray(); grouped.put(season, list); }
                list.put(row);
            }
            Iterator<String> keys = grouped.keys();
            while (keys.hasNext()) appendSeason(seasonsOut, keys.next(), grouped);
        }
        out.put("seasons", seasonsOut);
        return out;
    }

    private void appendSeason(JSONArray target, String seasonNumber, JSONObject sourceObject) throws Exception {
        JSONArray source = sourceObject.optJSONArray(seasonNumber); if (source == null) return;
        JSONObject season = new JSONObject(); season.put("season", seasonNumber);
        JSONArray normalized = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i); if (row == null) continue;
            JSONObject rowInfo = row.optJSONObject("info"); if (rowInfo == null) rowInfo = new JSONObject();
            JSONObject episode = new JSONObject();
            String sourceUrl = firstHttp(row.optString("direct_source"), row.optString("directSource"),
                    rowInfo.optString("direct_source"), rowInfo.optString("directSource"));
            String episodeExtension = row.optString("container_extension", "mp4");
            String sourceExtension = extensionFromUrl(sourceUrl);
            if (!sourceExtension.isEmpty()) episodeExtension = sourceExtension;
            episode.put("id", row.optString("id"));
            episode.put("number", row.optInt("episode_num", row.optInt("episode", i + 1)));
            episode.put("title", row.optString("title", "الحلقة " + (i + 1)));
            episode.put("extension", episodeExtension);
            episode.put("directSource", sourceUrl);
            episode.put("duration", rowInfo.optString("duration"));
            episode.put("image", rowInfo.optString("movie_image", rowInfo.optString("cover_big", "")));
            if (!episode.optString("id").isEmpty()) normalized.put(episode);
        }
        season.put("episodes", normalized);
        if (normalized.length() > 0) target.put(season);
    }

    private static String firstBackdrop(JSONObject info) {
        JSONArray values = info.optJSONArray("backdrop_path");
        if (values != null && values.length() > 0) return values.optString(0, "");
        Object raw = info.opt("backdrop_path"); return raw instanceof String ? (String) raw : "";
    }
    private static String year(String value) { if (value == null) return ""; String t=value.trim(); return t.length()>=4?t.substring(0,4):t; }

    private void render(JSONObject data) {
        if (binding == null || data == null) return;
        binding.progress.setVisibility(View.GONE);
        name = data.optString("name", name); extension = data.optString("extension", extension); image = data.optString("image", image);
        binding.title.setText(name);
        StringBuilder meta = new StringBuilder();
        append(meta, data.optString("year"));
        append(meta, data.optString("rating").isEmpty() ? "" : "★ " + data.optString("rating"));
        append(meta, data.optString("genre")); append(meta, data.optString("duration"));
        binding.meta.setText(meta); binding.description.setText(data.optString("description", "لا يوجد وصف متاح."));
        safePoster(binding.poster, image); safeBackdrop(binding.backdrop, data.optString("backdrop"));

        if (!"series".equals(type)) {
            binding.play.setVisibility(View.VISIBLE); binding.play.post(binding.play::requestFocus); return;
        }

        seasons.clear();
        JSONArray values = data.optJSONArray("seasons");
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject rawSeason = values.optJSONObject(i); if (rawSeason == null) continue;
            Season season = new Season(rawSeason.optString("season", String.valueOf(i + 1)));
            JSONArray rawEpisodes = rawSeason.optJSONArray("episodes");
            if (rawEpisodes != null) for (int e = 0; e < rawEpisodes.length(); e++) {
                JSONObject episode = rawEpisodes.optJSONObject(e); if (episode != null) season.episodes.add(new Episode(episode));
            }
            if (!season.episodes.isEmpty()) seasons.add(season);
        }
        if (seasons.isEmpty()) { showMetadataError(new Exception("لم يرسل مزود الباقة حلقات صالحة لهذا المسلسل.")); return; }

        binding.errorPanel.setVisibility(View.GONE); binding.seriesPanel.setVisibility(View.VISIBLE);
        binding.seasons.setLayoutManager(new LinearLayoutManager(this)); seasonAdapter = new SeasonAdapter(); binding.seasons.setAdapter(seasonAdapter);
        binding.episodes.setLayoutManager(new GridLayoutManager(this, 3, RecyclerView.HORIZONTAL, false)); episodeAdapter = new EpisodeAdapter(); binding.episodes.setAdapter(episodeAdapter);
        selectSeason(0);
        binding.seasons.post(() -> { if (binding == null) return; RecyclerView.ViewHolder h=binding.seasons.findViewHolderForAdapterPosition(0); if(h!=null) h.itemView.requestFocus(); });
    }

    private void safePoster(android.widget.ImageView view, String url) { try { BlofyImageLoader.poster(this, view, url == null ? "" : url); } catch (Throwable ignored) {} }
    private void safeBackdrop(android.widget.ImageView view, String url) { try { BlofyImageLoader.backdrop(this, view, url == null ? "" : url); } catch (Throwable ignored) {} }
    private static void append(StringBuilder target, String value) { if(value==null||value.trim().isEmpty())return; if(target.length()>0)target.append("  •  "); target.append(value.trim()); }

    private void selectSeason(int index) {
        if (seasonAdapter == null || episodeAdapter == null || index < 0 || index >= seasons.size()) return;
        seasonAdapter.selected=index; seasonAdapter.notifyDataSetChanged(); Season selected=seasons.get(index);
        binding.episodesTitle.setText("حلقات الموسم " + selected.number); episodeAdapter.rows.clear(); episodeAdapter.rows.addAll(selected.episodes); episodeAdapter.notifyDataSetChanged();
    }
    private boolean focusFirstEpisode() { if(binding==null||episodeAdapter==null||episodeAdapter.getItemCount()==0)return false; binding.episodes.scrollToPosition(0); binding.episodes.post(()->{if(binding==null)return; RecyclerView.ViewHolder h=binding.episodes.findViewHolderForAdapterPosition(0);if(h!=null)h.itemView.requestFocus();}); return true; }
    private boolean focusSelectedSeason() { if(binding==null||seasonAdapter==null||seasons.isEmpty())return false; int p=Math.max(0,Math.min(seasonAdapter.selected,seasons.size()-1)); binding.seasons.scrollToPosition(p); binding.seasons.post(()->{if(binding==null)return;RecyclerView.ViewHolder h=binding.seasons.findViewHolderForAdapterPosition(p);if(h!=null)h.itemView.requestFocus();}); return true; }

    private void play(String playId, String playName, String playType, String ext, String source) {
        if (playId == null || playId.trim().isEmpty()) return;
        startActivity(new Intent(this, PlayerActivity.class).putExtra("id",playId).putExtra("name",playName)
                .putExtra("type",playType).putExtra("extension",ext).putExtra("direct_source",source==null?"":source));
    }
    private String value(String key, String fallback) { String raw=getIntent().getStringExtra(key); return raw==null||raw.isEmpty()?fallback:raw; }

    private static String firstHttp(String... values) {
        if (values != null) for (String value : values) if (isHttp(value)) return value.trim();
        return "";
    }
    private static boolean isHttp(String value) { return value != null && (value.startsWith("http://") || value.startsWith("https://")); }
    private static String extensionFromUrl(String value) {
        if (!isHttp(value)) return "";
        String lower=value.toLowerCase(Locale.US); int q=lower.indexOf('?'); String path=q>=0?lower.substring(0,q):lower;
        if(path.endsWith(".m3u8"))return "m3u8"; if(path.endsWith(".mpd"))return "mpd";
        if(path.endsWith(".ts")||path.endsWith(".mts")||path.endsWith(".m2ts"))return "ts";
        if(path.endsWith(".mp4")||path.endsWith(".m4v"))return "mp4"; if(path.endsWith(".mkv"))return "mkv"; if(path.endsWith(".webm"))return "webm";
        if(lower.contains("output=m3u8")||lower.contains("format=m3u8"))return "m3u8"; if(lower.contains("output=ts")||lower.contains("format=ts"))return "ts";
        return "";
    }

    private static final class Season { final String number; final List<Episode> episodes=new ArrayList<>(); Season(String n){number=n==null||n.isEmpty()?"1":n;} }
    private static final class Episode {
        final String id,title,extension,duration,image,directSource;
        Episode(JSONObject row){id=row.optString("id");int number=row.optInt("number",0);title=row.optString("title",number>0?"الحلقة "+number:"حلقة");extension=row.optString("extension","mp4");duration=row.optString("duration");image=row.optString("image");directSource=row.optString("directSource",row.optString("direct_source"));}
    }

    private final class SeasonAdapter extends RecyclerView.Adapter<SeasonAdapter.Holder> {
        int selected;
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){return new Holder(ItemSeasonBinding.inflate(LayoutInflater.from(parent.getContext()),parent,false));}
        @Override public void onBindViewHolder(@NonNull Holder holder,int position){Season season=seasons.get(position);holder.binding.name.setText((position==selected?"●  ":"")+"الموسم "+season.number);holder.binding.getRoot().setSelected(position==selected);holder.binding.getRoot().setOnClickListener(v->{int p=holder.getBindingAdapterPosition();if(p!=RecyclerView.NO_POSITION)selectSeason(p);});holder.binding.getRoot().setOnFocusChangeListener((v,f)->{int p=holder.getBindingAdapterPosition();if(f&&p!=RecyclerView.NO_POSITION)selectSeason(p);});holder.binding.getRoot().setOnKeyListener((v,k,e)->e.getAction()==KeyEvent.ACTION_DOWN&&k==KeyEvent.KEYCODE_DPAD_RIGHT&&focusFirstEpisode());}
        @Override public int getItemCount(){return seasons.size();}
        final class Holder extends RecyclerView.ViewHolder{final ItemSeasonBinding binding;Holder(ItemSeasonBinding b){super(b.getRoot());binding=b;}}
    }

    private final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {
        final List<Episode> rows=new ArrayList<>();
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){return new Holder(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()),parent,false));}
        @Override public void onBindViewHolder(@NonNull Holder holder,int position){Episode ep=rows.get(position);holder.binding.title.setText(ep.title);holder.binding.meta.setText(ep.duration.isEmpty()?ep.extension.toUpperCase(Locale.US):ep.duration);safePoster(holder.binding.image,ep.image.isEmpty()?image:ep.image);holder.binding.getRoot().setOnClickListener(v->play(ep.id,name+" • "+ep.title,"episode",ep.extension,ep.directSource));holder.binding.getRoot().setOnFocusChangeListener((v,f)->v.animate().scaleX(f?1.045f:1f).scaleY(f?1.045f:1f).setDuration(100).start());holder.binding.getRoot().setOnKeyListener((v,k,e)->{int p=holder.getBindingAdapterPosition();return e.getAction()==KeyEvent.ACTION_DOWN&&k==KeyEvent.KEYCODE_DPAD_LEFT&&p!=RecyclerView.NO_POSITION&&p<3&&focusSelectedSeason();});}
        @Override public int getItemCount(){return rows.size();}
        final class Holder extends RecyclerView.ViewHolder{final ItemEpisodeBinding binding;Holder(ItemEpisodeBinding b){super(b.getRoot());binding=b;}}
    }

    @Override protected void onDestroy(){generation.incrementAndGet();worker.shutdownNow();if(store!=null)store.close();binding=null;super.onDestroy();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getAction()==KeyEvent.ACTION_DOWN&&(event.getKeyCode()==KeyEvent.KEYCODE_ESCAPE||event.getKeyCode()==KeyEvent.KEYCODE_BUTTON_B)){finish();return true;}return super.dispatchKeyEvent(event);}
}
