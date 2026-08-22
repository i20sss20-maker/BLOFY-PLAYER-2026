package tv.blofy.commercial.ui.library;

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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import tv.blofy.commercial.ui.details.DetailsActivity;
import tv.blofy.commercial.ui.player.PlayerActivity;

/** Two-pane Movies/Series browser: category rail + poster grid. */
public final class LibraryActivity extends LicensedActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private CatalogStore store;
    private RecyclerView categories, grid;
    private CategoryAdapter categoryAdapter;
    private PosterAdapter posterAdapter;
    private TextView count;
    private EditText search;
    private String type, category = "", query = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        type = getIntent().getStringExtra("type");
        if (!"series".equals(type)) type = "movies";
        store = new CatalogStore(this);
        setContentView(buildUi());
        loadCategories();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(22), dp(28), dp(24));
        root.setBackgroundResource(R.drawable.bg_blofy);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        TextView title = text("series".equals(type) ? "المسلسلات" : "الأفلام", 27, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        count = text("", 13, false);
        count.setTextColor(getColor(R.color.blofy_muted));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT);
        top.addView(count, countLp);

        search = new EditText(this);
        search.setHint("بحث");
        search.setSingleLine(true);
        search.setTextColor(getColor(R.color.blofy_text));
        search.setHintTextColor(getColor(R.color.blofy_muted));
        search.setBackgroundResource(R.drawable.bg_home_status);
        search.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(dp(260), dp(48));
        top.addView(search, searchLp);
        search.setOnEditorActionListener((v, action, event) -> {
            query = v.getText() == null ? "" : v.getText().toString().trim();
            hideKeyboard();
            loadMedia();
            return true;
        });

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(12);
        root.addView(body, bodyLp);

        categories = list();
        categories.setLayoutManager(new LinearLayoutManager(this));
        body.addView(categories, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.23f));

        grid = list();
        grid.setLayoutManager(new GridLayoutManager(this, 5));
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.77f);
        gridLp.setMarginStart(dp(14));
        body.addView(grid, gridLp);

        categoryAdapter = new CategoryAdapter();
        posterAdapter = new PosterAdapter();
        categories.setAdapter(categoryAdapter);
        grid.setAdapter(posterAdapter);
        return root;
    }

    private RecyclerView list() {
        RecyclerView view = new RecyclerView(this);
        view.setHasFixedSize(true);
        view.setItemAnimator(null);
        view.setItemViewCacheSize(20);
        return view;
    }

    private void loadCategories() {
        worker.execute(() -> {
            List<CategoryRecord> rows = new ArrayList<>();
            rows.add(new CategoryRecord("", "الكل", store.count(type)));
            rows.addAll(store.categories(type));
            runOnUiThread(() -> {
                categoryAdapter.submit(rows);
                loadMedia();
                categories.post(() -> focus(categories, 0));
            });
        });
    }

    private void loadMedia() {
        int request = generation.incrementAndGet();
        final String c = category;
        final String q = query;
        worker.execute(() -> {
            List<MediaRecord> rows = store.media(type, c, q, false, false, 800, 0);
            runOnUiThread(() -> {
                if (request != generation.get()) return;
                posterAdapter.submit(rows);
                count.setText(rows.size() + " عنصر");
            });
        });
    }

    private void open(MediaRecord item) {
        boolean directM3u = "m3u".equalsIgnoreCase(store.getMeta("kind"));
        Class<?> target = directM3u ? PlayerActivity.class : DetailsActivity.class;
        startActivity(new Intent(this, target)
                .putExtra("type", item.type)
                .putExtra("id", item.id)
                .putExtra("name", item.name)
                .putExtra("extension", item.extension)
                .putExtra("image", item.image));
    }

    private TextView categoryRow(ViewGroup parent) {
        TextView view = new TextView(parent.getContext());
        view.setTextColor(getColor(R.color.blofy_text));
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPadding(dp(17), 0, dp(12), 0);
        view.setFocusable(true);
        view.setSingleLine(true);
        view.setBackgroundResource(R.drawable.bg_home_status);
        view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return view;
    }

    private View posterCard(ViewGroup parent) {
        LinearLayout card = new LinearLayout(parent.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(8));
        card.setFocusable(true);
        card.setBackgroundResource(R.drawable.bg_home_status);
        card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));
        ImageView image = new ImageView(parent.getContext());
        image.setId(View.generateViewId());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView title = text("", 13, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        card.addView(title, titleLp);
        card.setTag(new Object[]{image, title});
        return card;
    }

    private void focus(RecyclerView view, int position) {
        view.scrollToPosition(position);
        view.post(() -> {
            RecyclerView.ViewHolder holder = view.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextColor(getColor(R.color.blofy_text)); view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.Holder> {
        final List<CategoryRecord> rows = new ArrayList<>();
        void submit(List<CategoryRecord> values) { rows.clear(); rows.addAll(values); notifyDataSetChanged(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new Holder(categoryRow(parent)); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            CategoryRecord row = rows.get(position);
            holder.text.setText(row.name + (row.count > 0 ? "  " + row.count : ""));
            holder.text.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.02f : 1f).scaleY(focused ? 1.02f : 1f).setDuration(60).start();
                if (focused && !row.id.equals(category)) { category = row.id; loadMedia(); }
            });
            holder.text.setOnClickListener(v -> { if (posterAdapter.getItemCount() > 0) focus(grid, 0); });
            holder.text.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && posterAdapter.getItemCount() > 0) {
                    focus(grid, 0); return true;
                }
                return false;
            });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder { final TextView text; Holder(TextView v) { super(v); text = v; } }
    }

    private final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
        final List<MediaRecord> rows = new ArrayList<>();
        void submit(List<MediaRecord> values) { rows.clear(); rows.addAll(values); notifyDataSetChanged(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new Holder(posterCard(parent)); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            MediaRecord row = rows.get(position);
            holder.title.setText(row.name);
            BlofyImageLoader.poster(LibraryActivity.this, holder.image, row.image);
            holder.itemView.setOnClickListener(v -> open(row));
            holder.itemView.setOnFocusChangeListener((v, focused) -> v.animate()
                    .scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(65).start());
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image; final TextView title;
            Holder(View item) {
                super(item);
                Object[] parts = (Object[]) item.getTag();
                image = (ImageView) parts[0]; title = (TextView) parts[1];
            }
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            finish(); return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDestroy() {
        generation.incrementAndGet(); worker.shutdownNow(); if (store != null) store.close(); super.onDestroy();
    }
}
