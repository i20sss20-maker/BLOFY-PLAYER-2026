package tv.blofy.commercial.ui.live;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
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

/** IBO/7up-style live TV: category rail -> channel list -> preview/EPG -> fullscreen player. */
public final class LiveActivity extends LicensedActivity {
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final AtomicInteger generation = new AtomicInteger();
    private CatalogStore store;
    private RecyclerView categories, channels;
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;
    private ImageView previewLogo;
    private TextView previewName, previewEpg, previewMeta, count;
    private EditText search;
    private String activeCategory = "", query = "";

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
        root.setPadding(dp(24), dp(18), dp(24), dp(22));
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        TextView title = text("LIVE TV", 28, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        count = text("", 13, false);
        count.setTextColor(getColor(R.color.blofy_muted));
        top.addView(count, new LinearLayout.LayoutParams(dp(125), ViewGroup.LayoutParams.WRAP_CONTENT));

        search = new EditText(this);
        search.setHint("⌕  بحث في القنوات");
        search.setSingleLine(true);
        search.setTextColor(getColor(R.color.blofy_text));
        search.setHintTextColor(getColor(R.color.blofy_muted));
        search.setBackgroundResource(R.drawable.bg_home_status);
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setShowSoftInputOnFocus(false);
        search.setFocusable(true);
        search.setFocusableInTouchMode(false);
        top.addView(search, new LinearLayout.LayoutParams(dp(280), dp(48)));
        search.setOnEditorActionListener((v, actionId, event) -> {
            query = v.getText() == null ? "" : v.getText().toString().trim();
            hideKeyboard();
            loadChannels(activeCategory);
            return true;
        });
        search.setOnClickListener(v -> showKeyboard());
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                showKeyboard(); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (channelAdapter != null && channelAdapter.getItemCount() > 0) focus(channels, 0);
                else focus(categories, 0);
                return true;
            }
            return false;
        });

        LinearLayout panes = new LinearLayout(this);
        panes.setOrientation(LinearLayout.HORIZONTAL);
        panes.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams panesLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panesLp.topMargin = dp(10);
        root.addView(panes, panesLp);

        categories = list();
        categories.setLayoutManager(new LinearLayoutManager(this));
        panes.addView(categories, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .22f));

        channels = list();
        channels.setLayoutManager(new LinearLayoutManager(this));
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .34f);
        chLp.setMarginStart(dp(10));
        panes.addView(channels, chLp);

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setGravity(Gravity.CENTER_HORIZONTAL);
        preview.setPadding(dp(24), dp(24), dp(24), dp(20));
        preview.setBackgroundResource(R.drawable.bg_panel);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .44f);
        prevLp.setMarginStart(dp(12));
        panes.addView(preview, prevLp);

        TextView now = text("NOW PLAYING", 12, true);
        now.setTextColor(getColor(R.color.blofy_muted));
        now.setGravity(Gravity.CENTER);
        preview.addView(now, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        previewLogo = new ImageView(this);
        previewLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        preview.addView(previewLogo, new LinearLayout.LayoutParams(dp(185), dp(170)));

        previewName = text("اختر قناة", 23, true);
        previewName.setGravity(Gravity.CENTER);
        previewName.setMaxLines(2);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(14);
        preview.addView(previewName, nameLp);

        previewMeta = text("", 13, false);
        previewMeta.setTextColor(getColor(R.color.blofy_muted));
        previewMeta.setGravity(Gravity.CENTER);
        preview.addView(previewMeta, wrapTop(8));

        previewEpg = text("", 15, false);
        previewEpg.setTextColor(getColor(R.color.blofy_text));
        previewEpg.setGravity(Gravity.CENTER);
        previewEpg.setMaxLines(4);
        preview.addView(previewEpg, wrapTop(14));

        TextView hint = text("OK  تشغيل ملء الشاشة   •   ← رجوع للتصنيفات", 12, false);
        hint.setTextColor(getColor(R.color.blofy_muted));
        hint.setGravity(Gravity.CENTER);
        preview.addView(hint, wrapTop(24));

        categoryAdapter = new CategoryAdapter();
        channelAdapter = new ChannelAdapter();
        categories.setAdapter(categoryAdapter);
        channels.setAdapter(channelAdapter);
        root.requestFocus();
        return root;
    }

    private RecyclerView list() {
        RecyclerView view = new RecyclerView(this);
        view.setHasFixedSize(true);
        view.setItemAnimator(null);
        view.setItemViewCacheSize(20);
        view.setFocusable(false);
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
                    categories.postDelayed(() -> focus(categories, 0), 80);
                }
            });
        });
    }

    private void loadChannels(String categoryId) {
        activeCategory = categoryId == null ? "" : categoryId;
        int request = generation.incrementAndGet();
        final String c = activeCategory;
        final String q = query;
        worker.execute(() -> {
            List<MediaRecord> rows = store.media("live", c, q, false, false, 700, 0);
            runOnUiThread(() -> {
                if (request != generation.get()) return;
                channelAdapter.submit(rows);
                count.setText(rows.size() + " قناة");
                if (!rows.isEmpty()) showPreview(rows.get(0));
                else {
                    previewName.setText("لا توجد نتائج");
                    previewMeta.setText("");
                    previewEpg.setText("");
                }
            });
        });
    }

    private void showPreview(MediaRecord item) {
        if (item == null) return;
        previewName.setText(item.name);
        previewMeta.setText((item.extension == null || item.extension.isEmpty() ? "LIVE" : item.extension.toUpperCase())
                + (item.directSource == null || item.directSource.isEmpty() ? "" : "  •  DIRECT"));
        previewEpg.setText("جلب دليل البرنامج…");
        BlofyImageLoader.poster(this, previewLogo, item.image);
        loadEpg(item);
    }

    private void loadEpg(MediaRecord item) {
        final String requestedId = item.id;
        worker.execute(() -> {
            String value = "البث المباشر";
            try {
                ProviderProfile profile = ProviderProfileStore.load(this);
                if (profile != null && profile.isXtream()) {
                    JSONObject data = new XtreamClient(profile).epg(requestedId, 3);
                    JSONArray rows = data.optJSONArray("epg_listings");
                    if (rows != null && rows.length() > 0) {
                        JSONObject current = rows.optJSONObject(0);
                        if (current != null) value = current.optString("title", "البث المباشر");
                    }
                }
            } catch (Exception ignored) { }
            final String text = value;
            runOnUiThread(() -> {
                if (channelAdapter != null && requestedId.equals(channelAdapter.focusedId)) previewEpg.setText(text);
            });
        });
    }

    private void play(MediaRecord item) {
        if (item == null) return;
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra("type", "live")
                .putExtra("id", item.id)
                .putExtra("name", item.name)
                .putExtra("extension", item.extension)
                .putExtra("direct_source", item.directSource));
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
        if (list == null || position < 0) return;
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

    private LinearLayout.LayoutParams wrapTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top); return lp;
    }

    private void showKeyboard() {
        search.setShowSoftInputOnFocus(true);
        search.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setShowSoftInputOnFocus(false);
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
                v.animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f).setDuration(65).start();
                if (focused && !row.id.equals(activeCategory)) loadChannels(row.id);
            });
            holder.text.setOnClickListener(v -> focus(channels, 0));
            holder.text.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && channelAdapter.getItemCount() > 0) { focus(channels, 0); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && holder.getBindingAdapterPosition() == 0) { search.requestFocus(); return true; }
                return false;
            });
        }
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
            holder.text.setText(String.format(java.util.Locale.US, "%03d   %s", position + 1, row.name));
            holder.text.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.02f : 1f).scaleY(focused ? 1.02f : 1f).setDuration(65).start();
                if (focused) { focusedId = row.id; showPreview(row); }
            });
            holder.text.setOnClickListener(v -> play(row));
            holder.text.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { focus(categories, 0); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && holder.getBindingAdapterPosition() == 0) { search.requestFocus(); return true; }
                return false;
            });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder { final TextView text; Holder(TextView v) { super(v); text = v; } }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            hideKeyboard(); finish(); return true;
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
