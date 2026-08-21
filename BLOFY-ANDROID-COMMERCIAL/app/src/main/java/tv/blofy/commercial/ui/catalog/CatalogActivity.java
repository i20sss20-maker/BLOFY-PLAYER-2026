package tv.blofy.commercial.ui.catalog;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.CategoryRecord;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.databinding.ActivityCatalogBinding;
import tv.blofy.commercial.ui.details.DetailsActivity;
import tv.blofy.commercial.ui.player.PlayerActivity;

/** Native TV catalogue with a physical left category rail and paged database reads. */
public final class CatalogActivity extends LicensedActivity {
    private static final int PAGE_SIZE = 180;
    private static final int LOAD_MORE_THRESHOLD = 24;
    private static final String STATE_CATEGORY_TYPE = "category_type";
    private static final String STATE_CATEGORY_ID = "category_id";
    private static final String STATE_QUERY = "query";
    private static final String STATE_FOCUS = "focus_position";
    private static final String STATE_GRID = "grid_state";

    private ActivityCatalogBinding binding;
    private CatalogStore store;
    private CategoryAdapter categoryAdapter;
    private MediaAdapter mediaAdapter;
    private GridLayoutManager gridLayout;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    private String requestedType;
    private String activeType;
    private String activeCategory = "";
    private String query = "";
    private boolean favorites;
    private boolean history;
    private boolean loading;
    private boolean reachedEnd;
    private int offset;
    private int generation;
    private int focusedPosition;
    private int total = -1;
    private Parcelable pendingGridState;
    private Runnable pendingSearch;
    private List<MediaRecord> loadedRows = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityCatalogBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        store = new CatalogStore(this);

        requestedType = safe(getIntent().getStringExtra("type"));
        activeType = requestedType;
        favorites = getIntent().getBooleanExtra("favorites", false);
        history = getIntent().getBooleanExtra("history", false);
        if (state != null) {
            activeType = state.getString(STATE_CATEGORY_TYPE, requestedType);
            activeCategory = state.getString(STATE_CATEGORY_ID, "");
            query = state.getString(STATE_QUERY, "");
            focusedPosition = Math.max(0, state.getInt(STATE_FOCUS, 0));
            pendingGridState = state.getParcelable(STATE_GRID);
        }

        int columns = isTv() ? ("live".equals(requestedType) ? 5 : 6) : ("live".equals(requestedType) ? 2 : 3);
        gridLayout = new GridLayoutManager(this, columns);
        binding.list.setLayoutManager(gridLayout);
        binding.list.setHasFixedSize(true);
        binding.list.setItemViewCacheSize(columns * 3);

        mediaAdapter = new MediaAdapter(
                "live".equals(requestedType),
                this::open,
                this::toggleFavorite,
                (position, item) -> focusedPosition = position,
                this::moveFromGridToCategories);
        binding.list.setAdapter(mediaAdapter);

        categoryAdapter = new CategoryAdapter(this::selectCategory, this::moveFromCategoriesToGrid);
        binding.categories.setLayoutManager(new LinearLayoutManager(this));
        binding.categories.setHasFixedSize(true);
        binding.categories.setItemViewCacheSize(18);
        binding.categories.setAdapter(categoryAdapter);

        binding.title.setText(screenTitle());
        binding.search.setText(query);
        binding.search.setOnEditorActionListener((view, actionId, event) -> {
            hideKeyboard();
            applySearch(safe(view.getText() == null ? "" : view.getText().toString()));
            return true;
        });
        binding.search.addTextChangedListener(new SimpleTextWatcher(text -> scheduleSearch(safe(text))));
        binding.search.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return focusCurrentMedia();
            }
            return false;
        });

        binding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                int last = gridLayout.findLastVisibleItemPosition();
                if (last >= mediaAdapter.getItemCount() - LOAD_MORE_THRESHOLD) requestPage();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!query.isEmpty()) {
                    binding.search.setText("");
                    applySearch("");
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        loadCategories();
    }

    private void loadCategories() {
        showLoading(true);
        worker.execute(() -> {
            List<CategoryRecord> rows = new ArrayList<>();
            if (favorites || history || requestedType.isEmpty()) {
                rows.add(new CategoryRecord("", "الكل", 0));
                rows.add(new CategoryRecord("@type:live", "البث المباشر", 0));
                rows.add(new CategoryRecord("@type:movies", "الأفلام", 0));
                rows.add(new CategoryRecord("@type:series", "المسلسلات", 0));
            } else {
                rows.add(new CategoryRecord("", "الكل", store.count(requestedType, "")));
                List<CategoryRecord> saved = store.categories(requestedType);
                if (saved != null) rows.addAll(saved);
            }
            runOnUiThread(() -> {
                if (destroyed.get()) return;
                categoryAdapter.submit(rows, selectionId());
                if (!categoryAdapter.hasSelection()) {
                    CategoryRecord first = rows.get(0);
                    applyCategoryRecord(first);
                    categoryAdapter.select(selectionId());
                }
                loadFirstPage();
            });
        });
    }

    private void selectCategory(CategoryRecord category) {
        if (category.id.equals(selectionId())) {
            moveFromCategoriesToGrid();
            return;
        }
        applyCategoryRecord(category);
        categoryAdapter.select(selectionId());
        focusedPosition = 0;
        pendingGridState = null;
        loadFirstPage();
    }

    private void loadFirstPage() {
        generation++;
        offset = 0;
        total = -1;
        reachedEnd = false;
        loading = false;
        loadedRows = new ArrayList<>();
        mediaAdapter.submit(Collections.emptyList(), null);
        binding.empty.setVisibility(View.GONE);
        showLoading(true);
        requestPage();
    }

    private void requestPage() {
        if (loading || reachedEnd || destroyed.get()) return;
        loading = true;
        final int requestGeneration = generation;
        final int requestOffset = offset;
        final String requestType = activeType;
        final String requestCategory = activeCategory;
        final String requestQuery = query;
        worker.execute(() -> {
            List<MediaRecord> page;
            int count = -1;
            try {
                page = store.media(requestType, requestCategory, requestQuery, favorites, history, PAGE_SIZE, requestOffset);
                if (requestOffset == 0 && !favorites && !history && requestQuery.isEmpty()) {
                    count = store.count(requestType, requestCategory);
                }
            } catch (Exception error) {
                page = Collections.emptyList();
            }
            final List<MediaRecord> result = page == null ? Collections.emptyList() : page;
            final int resultCount = count;
            runOnUiThread(() -> applyPage(requestGeneration, requestOffset, result, resultCount));
        });
    }

    private void applyPage(int requestGeneration, int requestOffset, List<MediaRecord> page, int resultCount) {
        if (destroyed.get() || requestGeneration != generation) return;
        loading = false;
        showLoading(false);
        if (resultCount >= 0) total = resultCount;
        reachedEnd = page.size() < PAGE_SIZE;
        offset = requestOffset + page.size();

        List<MediaRecord> merged = new ArrayList<>(loadedRows);
        merged.addAll(page);
        loadedRows = merged;
        mediaAdapter.submit(merged, () -> {
            updateCount();
            binding.empty.setVisibility(merged.isEmpty() ? View.VISIBLE : View.GONE);
            if (requestOffset == 0) restoreGridFocus();
            else if (focusedPosition >= merged.size() && !reachedEnd) requestPage();
        });
    }

    private void restoreGridFocus() {
        if (mediaAdapter.getItemCount() == 0) {
            categoryAdapter.focusSelected(binding.categories);
            return;
        }
        if (pendingGridState != null) {
            gridLayout.onRestoreInstanceState(pendingGridState);
            pendingGridState = null;
        } else {
            gridLayout.scrollToPosition(Math.min(focusedPosition, mediaAdapter.getItemCount() - 1));
        }
        binding.list.post(this::focusCurrentMedia);
    }

    private boolean focusCurrentMedia() {
        if (mediaAdapter.getItemCount() == 0) return false;
        int position = Math.min(Math.max(0, focusedPosition), mediaAdapter.getItemCount() - 1);
        RecyclerView.ViewHolder holder = binding.list.findViewHolderForAdapterPosition(position);
        if (holder == null) {
            binding.list.scrollToPosition(position);
            binding.list.post(() -> {
                RecyclerView.ViewHolder next = binding.list.findViewHolderForAdapterPosition(position);
                if (next != null) next.itemView.requestFocus();
            });
        } else holder.itemView.requestFocus();
        return true;
    }

    private boolean moveFromGridToCategories(View card) {
        int threshold = binding.list.getPaddingLeft() + dp(28);
        if (card.getLeft() > threshold) return false;
        return categoryAdapter.focusSelected(binding.categories);
    }

    private boolean moveFromCategoriesToGrid() { return focusCurrentMedia(); }

    private void scheduleSearch(String next) {
        if (pendingSearch != null) binding.search.removeCallbacks(pendingSearch);
        pendingSearch = () -> applySearch(next);
        binding.search.postDelayed(pendingSearch, 320);
    }

    private void applySearch(String next) {
        if (next.equals(query)) return;
        query = next;
        focusedPosition = 0;
        pendingGridState = null;
        loadFirstPage();
    }

    private void open(MediaRecord item, int position) {
        focusedPosition = position;
        boolean directM3u = "m3u".equalsIgnoreCase(store.getMeta("kind")) && !"live".equals(item.type);
        Class<?> target = "live".equals(item.type) || directM3u ? PlayerActivity.class : DetailsActivity.class;
        startActivity(new Intent(this, target)
                .putExtra("type", item.type)
                .putExtra("id", item.id)
                .putExtra("name", item.name)
                .putExtra("extension", item.extension)
                .putExtra("image", item.image));
    }

    private void toggleFavorite(MediaRecord item) {
        worker.execute(() -> {
            boolean saved = store.toggleFavorite(item.type, item.id);
            runOnUiThread(() -> {
                if (destroyed.get()) return;
                Toast.makeText(this, saved ? "تمت الإضافة للمفضلة" : "تمت الإزالة من المفضلة", Toast.LENGTH_SHORT).show();
                if (favorites && !saved) loadFirstPage();
            });
        });
    }

    private void updateCount() {
        int loaded = mediaAdapter.getItemCount();
        if (total >= 0) binding.count.setText(total + " عنصر");
        else if (reachedEnd) binding.count.setText(loaded + " عنصر");
        else binding.count.setText(loaded + "+ عنصر");
    }

    private void showLoading(boolean visible) {
        boolean empty = mediaAdapter == null || mediaAdapter.getItemCount() == 0;
        binding.progress.setVisibility(visible && empty ? View.VISIBLE : View.GONE);
    }

    private String screenTitle() {
        if (favorites) return "المفضلة";
        if (history) return "سجل المشاهدة";
        if ("live".equals(requestedType)) return "البث المباشر";
        if ("movies".equals(requestedType)) return "الأفلام";
        if ("series".equals(requestedType)) return "المسلسلات";
        return "المحتوى";
    }

    private boolean isTv() { return getPackageManager().hasSystemFeature("android.software.leanback"); }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(binding.search.getWindowToken(), 0);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void applyCategoryRecord(CategoryRecord category) {
        if (category.id.startsWith("@type:")) {
            activeType = category.id.substring("@type:".length());
            activeCategory = "";
        } else {
            activeType = requestedType;
            activeCategory = category.id;
        }
    }

    private String selectionId() {
        if (favorites || history || requestedType.isEmpty()) {
            return activeType.isEmpty() ? "" : "@type:" + activeType;
        }
        return activeCategory;
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    @Override protected void onResume() {
        super.onResume();
        if (binding != null && mediaAdapter != null && mediaAdapter.getItemCount() > 0) {
            binding.list.postDelayed(this::focusCurrentMedia, 80);
        }
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString(STATE_CATEGORY_TYPE, activeType);
        outState.putString(STATE_CATEGORY_ID, activeCategory);
        outState.putString(STATE_QUERY, query);
        outState.putInt(STATE_FOCUS, focusedPosition);
        outState.putParcelable(STATE_GRID, gridLayout.onSaveInstanceState());
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        destroyed.set(true);
        if (pendingSearch != null && binding != null) binding.search.removeCallbacks(pendingSearch);
        worker.shutdownNow();
        if (store != null) store.close();
        super.onDestroy();
    }

    private static final class SimpleTextWatcher implements android.text.TextWatcher {
        interface Listener { void changed(String value); }
        private final Listener listener;
        SimpleTextWatcher(Listener listener) { this.listener = listener; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { listener.changed(s == null ? "" : s.toString()); }
        @Override public void afterTextChanged(android.text.Editable s) {}
    }
}
