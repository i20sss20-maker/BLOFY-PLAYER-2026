package tv.blofy.player;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Full-screen Live channel overlay shown above the running video. */
final class LiveChannelOverlay {
    interface Listener {
        void onChannelSelected(BlofyModels.Media media);
    }

    private static final int PAGE = 120;

    private final Activity activity;
    private final CatalogDatabase database;
    private final Listener listener;
    private final FrameLayout container;
    private final LinearLayout panel;
    private final RecyclerView list;
    private final LinearLayoutManager layoutManager;
    private final Adapter adapter;
    private final String categoryId;
    private String currentId = "";
    private int animationGeneration;

    LiveChannelOverlay(Activity activity, String categoryId, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.categoryId = categoryId == null ? "" : categoryId;
        database = new CatalogDatabase(activity);

        container = new FrameLayout(activity);
        container.setVisibility(View.GONE);
        container.setBackgroundColor(Color.argb(150, 3, 1, 8));
        // The reference keeps the channel drawer on the physical left, even when
        // the channel labels themselves are Arabic.
        container.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        container.setFocusable(false);
        container.setClickable(true);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(18));
        GradientDrawable panelBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.rgb(13, 8, 23),
                        Color.rgb(25, 13, 42),
                        Color.rgb(15, 9, 27)
                });
        panelBackground.setCornerRadius(dp(14));
        panelBackground.setStroke(dp(1), Color.rgb(118, 74, 169));
        panel.setBackground(panelBackground);
        panel.setElevation(dp(18));

        FrameLayout heading = new FrameLayout(activity);
        TextView title = BlofyUi.title(activity, "قنوات البث", 22);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        title.setPadding(dp(145), 0, dp(6), 0);
        heading.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView liveBadge = BlofyUi.text(activity, "BLOFY  •  LIVE", 10, BlofyUi.PURPLE_LIGHT);
        liveBadge.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        liveBadge.setTextDirection(View.TEXT_DIRECTION_LTR);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(145),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT);
        heading.addView(liveBadge, badgeParams);
        panel.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        View accent = new View(activity);
        GradientDrawable accentBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, BlofyUi.PURPLE, BlofyUi.PURPLE_LIGHT, Color.TRANSPARENT});
        accent.setBackground(accentBackground);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        accentParams.setMargins(0, 0, 0, dp(8));
        panel.addView(accent, accentParams);

        TextView hint = BlofyUi.text(activity, "↑↓ تنقل   •   OK تشغيل   •   رجوع إغلاق", 12, BlofyUi.MUTED);
        hint.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        hint.setTextDirection(View.TEXT_DIRECTION_RTL);
        panel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        list = new RecyclerView(activity);
        layoutManager = new LinearLayoutManager(activity);
        list.setLayoutManager(layoutManager);
        list.setItemAnimator(null);
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(20);
        list.setClipToPadding(false);
        list.setPadding(0, dp(6), 0, dp(8));
        list.setOnKeyListener((view, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT));
        adapter = new Adapter();
        adapter.setHasStableIds(true);
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                dp(450), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT);
        panelParams.setMargins(dp(22), dp(20), 0, dp(20));
        container.addView(panel, panelParams);
    }

    View view() { return container; }

    boolean isVisible() { return container.getVisibility() == View.VISIBLE; }

    void show(String currentId) {
        this.currentId = currentId == null ? "" : currentId;
        adapter.reload();
        animationGeneration++;
        panel.animate().cancel();
        container.setVisibility(View.VISIBLE);
        panel.setAlpha(0.88f);
        panel.setTranslationX(-dp(34));
        panel.animate().alpha(1f).translationX(0f).setDuration(150L).start();
        list.post(() -> {
            int selected = adapter.indexOf(this.currentId);
            if (selected >= 0) {
                layoutManager.scrollToPositionWithOffset(selected, dp(90));
                list.postOnAnimation(() -> focusPosition(selected));
            } else {
                focusPosition(0);
            }
        });
    }

    void hide() {
        if (!isVisible()) return;
        int token = ++animationGeneration;
        panel.animate().cancel();
        panel.animate().alpha(0.88f).translationX(-dp(28)).setDuration(120L)
                .withEndAction(() -> {
                    if (token != animationGeneration) return;
                    container.setVisibility(View.GONE);
                    panel.setAlpha(1f);
                    panel.setTranslationX(0f);
                }).start();
    }

    void close() {
        database.close();
    }

    void selectRelative(String currentId, int direction) {
        adapter.ensureLoaded();
        if (adapter.rows.isEmpty()) return;
        int current = adapter.indexOf(currentId);
        if (current < 0) current = 0;
        if (direction > 0 && current >= adapter.rows.size() - 1 && !adapter.exhausted) {
            adapter.loadMore();
        }
        int next = Math.max(0, Math.min(adapter.rows.size() - 1, current + direction));
        BlofyModels.Media media = adapter.rows.get(next);
        int previous = adapter.indexOf(this.currentId);
        this.currentId = media.id;
        if (previous >= 0) adapter.notifyItemChanged(previous);
        adapter.notifyItemChanged(next);
        if (isVisible()) {
            layoutManager.scrollToPositionWithOffset(next, dp(90));
            list.postOnAnimation(() -> focusPosition(next));
        }
        if (listener != null) listener.onChannelSelected(media);
    }

    private void focusPosition(int position) {
        if (adapter.getItemCount() == 0) {
            list.requestFocus();
            return;
        }
        int safe = Math.max(0, Math.min(adapter.getItemCount() - 1, position));
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(safe);
        if (holder != null) {
            holder.itemView.requestFocus();
            return;
        }
        layoutManager.scrollToPositionWithOffset(safe, dp(90));
        list.post(() -> {
            RecyclerView.ViewHolder retry = list.findViewHolderForAdapterPosition(safe);
            if (retry != null) retry.itemView.requestFocus();
            else list.requestFocus();
        });
    }

    private int dp(int value) { return BlofyUi.dp(activity, value); }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        final List<BlofyModels.Media> rows = new ArrayList<>();
        boolean exhausted;
        boolean loading;
        boolean loadPosted;

        void reload() {
            rows.clear();
            exhausted = false;
            loading = false;
            loadPosted = false;
            loadMore();
        }

        void loadMore() {
            if (exhausted || loading) return;
            loading = true;
            int offset = rows.size();
            List<BlofyModels.Media> next = database.media("live", categoryId, "", false, false, PAGE, offset);
            if (next.size() < PAGE) exhausted = true;
            if (next.isEmpty()) {
                loading = false;
                if (offset == 0) notifyDataSetChanged();
                return;
            }
            rows.addAll(next);
            loading = false;
            if (offset == 0) notifyDataSetChanged();
            else notifyItemRangeInserted(offset, next.size());
        }

        int indexOf(String id) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) return i;
            return -1;
        }

        void ensureLoaded() {
            if (rows.isEmpty()) loadMore();
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            Button button = BlofyUi.button(parent.getContext(), "", false);
            button.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            button.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            button.setTextSize(14);
            button.setSingleLine(true);
            button.setPadding(dp(16), 0, dp(16), 0);
            button.setOnKeyListener((view, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
            params.setMargins(0, dp(3), 0, dp(3));
            button.setLayoutParams(params);
            return new Holder(button);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 20 && !exhausted && !loadPosted) {
                loadPosted = true;
                list.post(() -> {
                    loadPosted = false;
                    loadMore();
                });
            }
            BlofyModels.Media media = rows.get(position);
            boolean current = media.id.equals(currentId);
            holder.button.setText((current ? "●   " : "") + (position + 1) + "   " + media.name);
            holder.button.setTextColor(current ? BlofyUi.PURPLE_LIGHT : BlofyUi.TEXT);
            holder.button.setAlpha(1f);
            holder.button.setBackground(BlofyUi.focusDrawable(activity,
                    current ? Color.rgb(60, 33, 91) : Color.argb(150, 18, 12, 29),
                    current ? Color.rgb(104, 62, 151) : Color.rgb(72, 42, 105),
                    BlofyUi.PURPLE_LIGHT));
            holder.button.setContentDescription((current ? "القناة الحالية، " : "") + media.name);
            holder.button.setOnClickListener(v -> {
                currentId = media.id;
                if (listener != null) listener.onChannelSelected(media);
                hide();
            });
        }

        @Override public int getItemCount() { return rows.size(); }

        @Override public long getItemId(int position) {
            return rows.get(position).id.hashCode() & 0xffffffffL;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final Button button;
            Holder(Button button) { super(button); this.button = button; }
        }
    }
}
