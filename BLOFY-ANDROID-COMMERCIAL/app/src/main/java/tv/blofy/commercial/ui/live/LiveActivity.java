package tv.blofy.commercial.ui.live;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import tv.blofy.commercial.R;
import tv.blofy.commercial.core.BlofyImageLoader;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.CategoryRecord;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.provider.ProviderProfile;
import tv.blofy.commercial.provider.ProviderProfileStore;
import tv.blofy.commercial.provider.XtreamClient;
import tv.blofy.commercial.ui.player.PlayerActivity;

/** Three-pane live browser: categories, channels, and lightweight preview/EPG. */
public final class LiveActivity extends LicensedActivity {
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final AtomicInteger generation = new AtomicInteger();
    private CatalogStore store;
    private RecyclerView categories, channels;
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;
    private ImageView previewLogo;
    private TextView previewName, previewEpg, count;
    private String activeCategory = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new CatalogStore(this);
        setContentView(buildUi());
        loadCategories();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_blofy);
        root.setPadding(dp(28), dp(22), dp(28), dp(24));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        TextView title = text("البث المباشر", 27, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        count = text("", 13, false);
        count.setTextColor(getColor(R.color.blofy_muted));
        top.addView(count);

        LinearLayout panes = new LinearLayout(this);
        panes.setOrientation(LinearLayout.HORIZONTAL);
        panes.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams panesLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panesLp.topMargin = dp(12);
        root.addView(panes, panesLp);

        categories = list();
        categories.setLayoutManager(new LinearLayoutManager(this));
        LinearLayout.LayoutParams catLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.24f);
        panes.addView(categories, catLp);

        channels = list();
        channels.setLayoutManager(new LinearLayoutManager(this));
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.34f);
        chLp.setMarginStart(dp(12));
        panes.addView(channels, chLp);

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setGravity(Gravity.CENTER_HORIZONTAL);
        preview.setPadding(dp(24), dp(22), dp(24), dp(22));
        preview.setBackgroundResource(R.drawable.bg_panel);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f);
        prevLp.setMarginStart(dp(14));
        panes.addView(preview, prevLp);

        previewLogo = new ImageView(this);
        previewLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        preview.addView(previewLogo, new LinearLayout.LayoutParams(dp(190), dp(190)));

        previewName = text("اختر قناة", 23, true);
        previewName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(18);
        preview.addView(previewName, nameLp);

        previewEpg = text("", 15, false);
        previewEpg.setTextColor(getColor(R.color.blofy_muted));
        previewEpg.setGravity(Gravity.CENTER);
        previewEpg.setMaxLines(4);
        LinearLayout.LayoutParams epgLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        epgLp.topMargin = dp(14);
        preview.addView(previewEpg, epgLp);

        TextView hint = text("OK للتشغيل  •  CH+ / CH- داخل المشغل", 12, false);
        hint.setTextColor(getColor(R.color.blofy_muted));
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(24);
        preview.addView(hint, hintLp);

        categoryAdapter = new CategoryAdapter();
        channelAdapter = new ChannelAdapter();
        categories.setAdapter(categoryAdapter);
        channels.setAdapter(channelAdapter);
        return root;
    }

    private RecyclerView list() {
        RecyclerView view = new RecyclerView(this);
        view.setHasFixedSize(true);
        view.setItemAnimator(null);
        view.setItemViewCacheSize(16);
        return view;
    }

    private void loadCategories() {
        worker.execute(() -> {
            List<CategoryRecord> rows = new ArrayList<>();
            rows.add(new CategoryRecord("", "الكل", store.count("live")));
            rows.addAll(store.categories("live"));
            runOnUiThread(() -> {
                categoryAdapter.submit(rows);
                if (!rows.isEmpty()) {
                    activeCategory = rows.get(0).id;
                    loadChannels(activeCategory);
                    categories.post(() -> focus(categories, 0));
                }
            });
        });
    }

    private void loadChannels(String categoryId) {
        activeCategory = categoryId == null ? "" : categoryId;
        int request = generation.incrementAndGet();
        worker.execute(() -> {
            List<MediaRecord> rows = store.media("live", activeCategory, "", false, false, 500, 0);
            runOnUiThread(() -> {
                if (request != generation.get()) return;
                channelAdapter.submit(rows);
                count.setText(rows.size() + " قناة");
                if (!rows.isEmpty()) showPreview(rows.get(0));
            });
        });
    }

    private void showPreview(MediaRecord item) {
        if (item == null) return;
        previewName.setText(item.name);
        previewEpg.setText("جلب دليل البرنامج…");
        BlofyImageLoader.poster(this, previewLogo, item.image);
        loadEpg(item);
    }

    private void loadEpg(MediaRecord item) {
        final String requestedId = item.id;
        worker.execute(() -> {
            String text = "البث المباشر";
            try {
                ProviderProfile profile = ProviderProfileStore.load(this);
                if (profile != null && profile.isXtream()) {
                    JSONObject data = new XtreamClient(profile).epg(requestedId, 2);
                    JSONArray rows = data.optJSONArray("epg_listings");
                    if (rows != null && rows.length() > 0) {
                        JSONObject current = rows.optJSONObject(0);
                        if (current != null) text = current.optString("title", "البث المباشر");
                    }
                }
            } catch (Exception ignored) { }
            final String value = text;
            runOnUiThread(() -> {
                if (channelAdapter.focusedId.equals(requestedId)) previewEpg.setText(value);
            });
        });
    }

    private void play(MediaRecord item) {
        if (item == null) return;
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra("type", "live")
                .putExtra("id", item.id)
                .putExtra("name", item.name)
                .putExtra("extension", item.extension));
    }

    private TextView rowText(ViewGroup parent) {
        TextView view = new TextView(parent.getContext());
        view.setTextColor(getColor(R.color.blofy_text));
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPadding(dp(18), 0, dp(14), 0);
        view.setFocusable(true);
        view.setBackgroundResource(R.drawable.bg_home_status);
        view.setSingleLine(true);
        return view;
    }

    private void focus(RecyclerView list, int position) {
        list.scrollToPosition(position);
        list.post(() -> {
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextColor(getColor(R.color.blofy_text)); view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.Holder> {
        final List<CategoryRecord> rows = new ArrayList<>();
        void submit(List<CategoryRecord> values) { rows.clear(); rows.addAll(values); notifyDataSetChanged(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            TextView view = rowText(parent);
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
            return new Holder(view);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            CategoryRecord row = rows.get(position);
            holder.text.setText(row.name + (row.count > 0 ? "  " + row.count : ""));
            holder.text.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.02f : 1f).scaleY(focused ? 1.02f : 1f).setDuration(60).start();
                if (focused && !row.id.equals(activeCategory)) loadChannels(row.id);
            });
            holder.text.setOnClickListener(v -> focus(channels, 0));
            holder.text.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focusChannels());
        }
        private boolean focusChannels() { if (channelAdapter.getItemCount() == 0) return false; focus(channels, 0); return true; }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder { final TextView text; Holder(TextView v) { super(v); text = v; } }
    }

    private final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.Holder> {
        final List<MediaRecord> rows = new ArrayList<>();
        String focusedId = "";
        void submit(List<MediaRecord> values) { rows.clear(); rows.addAll(values); focusedId = ""; notifyDataSetChanged(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            TextView view = rowText(parent);
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
            return new Holder(view);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            MediaRecord row = rows.get(position);
            holder.text.setText((position + 1) + "   " + row.name);
            holder.text.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.018f : 1f).scaleY(focused ? 1.018f : 1f).setDuration(60).start();
                if (focused) { focusedId = row.id; showPreview(row); }
            });
            holder.text.setOnClickListener(v -> play(row));
            holder.text.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { categories.requestFocus(); focus(categories, 0); return true; }
                return false;
            });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder { final TextView text; Holder(TextView v) { super(v); text = v; } }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            finish(); return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDestroy() {
        generation.incrementAndGet();
        worker.shutdownNow();
        if (store != null) store.close();
        super.onDestroy();
    }
}
