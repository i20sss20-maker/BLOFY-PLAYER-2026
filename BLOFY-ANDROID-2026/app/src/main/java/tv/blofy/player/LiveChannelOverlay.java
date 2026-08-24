package tv.blofy.player;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
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

    private static final int PAGE = 220;

    private final Activity activity;
    private final CatalogDatabase database;
    private final Listener listener;
    private final FrameLayout container;
    private final RecyclerView list;
    private final Adapter adapter;
    private String currentId = "";

    LiveChannelOverlay(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        database = new CatalogDatabase(activity);

        container = new FrameLayout(activity);
        container.setVisibility(View.GONE);
        container.setBackgroundColor(Color.argb(55, 0, 0, 0));
        container.setFocusable(false);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackground(BlofyUi.panel(activity, Color.argb(232, 10, 14, 23), 8, Color.rgb(79, 55, 116)));

        TextView title = BlofyUi.title(activity, "القنوات", 18);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView hint = BlofyUi.text(activity, "↑↓ تنقل  •  OK تغيير القناة  •  رجوع إغلاق", 11, BlofyUi.MUTED);
        hint.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        panel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setItemAnimator(null);
        list.setItemViewCacheSize(20);
        adapter = new Adapter();
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(dp(430), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT);
        panelParams.setMargins(dp(18), dp(18), 0, dp(18));
        container.addView(panel, panelParams);
    }

    View view() { return container; }

    boolean isVisible() { return container.getVisibility() == View.VISIBLE; }

    void show(String currentId) {
        this.currentId = currentId == null ? "" : currentId;
        adapter.reload();
        container.setVisibility(View.VISIBLE);
        list.post(() -> {
            int selected = adapter.indexOf(this.currentId);
            if (selected >= 0) {
                list.scrollToPosition(selected);
                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(selected);
                if (holder != null) holder.itemView.requestFocus();
                else list.requestFocus();
            } else {
                list.requestFocus();
            }
        });
    }

    void hide() {
        container.setVisibility(View.GONE);
    }

    void close() {
        database.close();
    }

    private int dp(int value) { return BlofyUi.dp(activity, value); }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        final List<BlofyModels.Media> rows = new ArrayList<>();
        boolean exhausted;

        void reload() {
            rows.clear();
            exhausted = false;
            loadMore();
        }

        void loadMore() {
            if (exhausted) return;
            int offset = rows.size();
            List<BlofyModels.Media> next = database.media("live", "", "", false, false, PAGE, offset);
            if (next.size() < PAGE) exhausted = true;
            if (next.isEmpty()) {
                if (offset == 0) notifyDataSetChanged();
                return;
            }
            rows.addAll(next);
            if (offset == 0) notifyDataSetChanged();
            else notifyItemRangeInserted(offset, next.size());
        }

        int indexOf(String id) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) return i;
            return -1;
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            Button button = BlofyUi.button(parent.getContext(), "", false);
            button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            button.setTextSize(13);
            button.setSingleLine(true);
            button.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            return new Holder(button);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 25) loadMore();
            BlofyModels.Media media = rows.get(position);
            holder.button.setText((position + 1) + "   " + media.name);
            holder.button.setAlpha(media.id.equals(currentId) ? 1f : 0.9f);
            holder.button.setOnClickListener(v -> {
                currentId = media.id;
                if (listener != null) listener.onChannelSelected(media);
                hide();
            });
        }

        @Override public int getItemCount() { return rows.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final Button button;
            Holder(Button button) { super(button); this.button = button; }
        }
    }
}
