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

/** IBO/7up-style Movies/Series browser: category rail + poster wall + explicit search. */
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
        root.setPadding(dp(24), dp(18), dp(24), dp(22));
        root.setBackgroundResource(R.drawable.bg_blofy);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        TextView title = text("series".equals(type) ? "SERIES" : "MOVIES", 28, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        count = text("", 13, false);
        count.setTextColor(getColor(R.color.blofy_muted));
        top.addView(count, new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));

        search = new EditText(this);
        search.setHint("⌕  بحث");
        search.setSingleLine(true);
        search.setTextColor(getColor(R.color.blofy_text));
        search.setHintTextColor(getColor(R.color.blofy_muted));
        search.setBackgroundResource(R.drawable.bg_home_status);
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setShowSoftInputOnFocus(false);
        search.setFocusable(true);
        search.setFocusableInTouchMode(false);
        top.addView(search, new LinearLayout.LayoutParams(dp(280), dp(48)));
        search.setOnEditorActionListener((v, action, event) -> {
            query = v.getText() == null ? "" : v.getText().toString().trim();
            hideKeyboard();
            loadMedia();
            return true;
        });
        search.setOnClickListener(v -> showKeyboard());
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                showKeyboard(); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (posterAdapter != null && posterAdapter.getItemCount() > 0) focus(grid, 0);
                else focus(categories, 0);
                return true;
            }
            return false;
        });

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(10);
        root.addView(body, bodyLp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundResource(R.drawable.bg_panel);
        left.setPadding(dp(8), dp(8), dp(8), dp(8));
        body.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .23f));

        TextView categoryTitle = text("التصنيفات", 16, true);
        categoryTitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        left.addView(categoryTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        categories = list();
        categories.setLayoutManager(new LinearLayoutManager(this));
        left.addView(categories, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = list();
        grid.setLayoutManager(new GridLayoutManager(this, 5));
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .77f);
        gridLp.setMarginStart(dp(12));
        body.addView(grid, gridLp);

        categoryAdapter = new CategoryAdapter();
        posterAdapter = new PosterAdapter();
        categories.setAdapter(categoryAdapter);
        grid.setAdapter(posterAdapter);
        root.requestFocus();
        return root;
    }

    private RecyclerView list() {
        RecyclerView view = new RecyclerView(this);
        view.setHasFixedSize(true);
        view.setItemAnimator(null);
        view.setItemViewCacheSize(24);
        view.setFocusable(false);
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
                categories.postDelayed(() -> focus(categories, 0), 80);
            });
        });
    }

    private void loadMedia() {
        int request = generation.incrementAndGet();
        final String c = category;
        final String q = query;
        worker.execute(() -> {
            List<MediaRecord> rows = store.media(type, c, q, false, false, 900, 0);
            runOnUiThread(() -> {
                if (request != generation.get()) return;
                posterAdapter.submit(rows);
                count.setText(rows.size() + " عنصر");
            });
        });
    }

    private void open(MediaRecord item) {
        if (item == null || item.id == null || item.id.trim().isEmpty()) return;
        boolean directM3u = "m3u".equalsIgnoreCase(store.getMeta("kind"));
        Class<?> target = directM3u ? PlayerActivity.class : DetailsActivity.class;
        startActivity(new Intent(this, target)
                .putExtra("type", item.type)
                .putExtra("id", item.id)
                .putExtra("name", item.name)
                .putExtra("extension", item.extension)
                .putExtra("image", item.image)
                .putExtra("direct_source", item.directSource));
    }

    private TextView categoryRow(ViewGroup parent) {
        TextView view = new TextView(parent.getContext());
        view.setTextColor(getColor(R.color.blofy_text));
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPadding(dp(15), 0, dp(10), 0);
        view.setFocusable(true);
        view.setSingleLine(true);
        view.setBackgroundResource(R.drawable.bg_home_status);
        view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return view;
    }

    private View posterCard(ViewGroup parent) {
        LinearLayout card = new LinearLayout(parent.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(7));
        card.setFocusable(true);
        card.setBackgroundResource(R.drawable.bg_home_status);
        card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(258)));
        ImageView image = new ImageView(parent.getContext());
        image.setId(View.generateViewId());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView title = text("", 13, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        card.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        card.setTag(new Object[]{image, title});
        return card;
    }

    private void focus(RecyclerView view, int position) {
        if (view == null || position < 0) return;
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
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new Holder(categoryRow(parent)); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            CategoryRecord row = rows.get(position);
            holder.text.setText(row.name + (row.count > 0 ? "  " + row.count : ""));
            holder.text.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f).setDuration(65).start();
                if (focused && !row.id.equals(category)) { category = row.id; loadMedia(); }
            });
            holder.text.setOnClickListener(v -> { if (posterAdapter.getItemCount() > 0) focus(grid, 0); });
            holder.text.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && posterAdapter.getItemCount() > 0) { focus(grid, 0); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && holder.getBindingAdapterPosition() == 0) { search.requestFocus(); return true; }
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
                    .scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f).setDuration(75).start());
            holder.itemView.setOnKeyListener((v, keyCode, event) -> {
                int p = holder.getBindingAdapterPosition();
                if (event.getAction() != KeyEvent.ACTION_DOWN || p == RecyclerView.NO_POSITION) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && p % 5 == 0) { focus(categories, 0); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && p < 5) { search.requestFocus(); return true; }
                return false;
            });
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
