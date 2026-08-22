package tv.blofy.commercial.ui.playlists;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.List;

import tv.blofy.commercial.R;
import tv.blofy.commercial.core.ApiClient;
import tv.blofy.commercial.core.DeviceIdentity;
import tv.blofy.commercial.provider.CompatibilityProfileStore;
import tv.blofy.commercial.provider.PlaylistProfile;
import tv.blofy.commercial.provider.PlaylistRepository;
import tv.blofy.commercial.provider.PlaylistStateStore;
import tv.blofy.commercial.ui.activation.ActivationActivity;
import tv.blofy.commercial.ui.discovery.DiscoveryActivity;
import tv.blofy.commercial.ui.home.HomeActivity;
import tv.blofy.commercial.ui.sync.SyncActivity;

/** Fast TV-first playlist manager with per-playlist readiness. */
public final class PlaylistsActivity extends AppCompatActivity {
    private LinearLayout playlistColumn;
    private TextView empty;
    private ApiClient api;
    private PlaylistProfile active;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new ApiClient(this);
        setContentView(buildUi());
        renderPlaylists();
    }

    @Override protected void onResume() {
        super.onResume();
        if (playlistColumn != null) renderPlaylists();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(34), dp(28), dp(34), dp(28));
        root.setBackgroundResource(R.drawable.bg_blofy);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(24), dp(20), dp(24), dp(20));
        left.setBackgroundResource(R.drawable.bg_panel);
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.67f));

        TextView title = text("قوائم التشغيل", 30, true);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        left.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        TextView subtitle = text("اختر قائمتك أو أضف قائمة جديدة", 14, false);
        subtitle.setTextColor(getColor(R.color.blofy_muted));
        left.addView(subtitle, wrapTop(4));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        playlistColumn = new LinearLayout(this);
        playlistColumn.setOrientation(LinearLayout.VERTICAL);
        playlistColumn.setPadding(0, dp(16), 0, dp(16));
        scroll.addView(playlistColumn, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        left.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = text("لا توجد قوائم تشغيل", 16, false);
        empty.setTextColor(getColor(R.color.blofy_muted));
        empty.setGravity(Gravity.CENTER);

        MaterialButton add = actionButton("＋  أضف قائمة تشغيل");
        add.setOnClickListener(v -> startActivity(new Intent(this, ActivationActivity.class)
                .putExtra("force_form", true)));
        left.addView(add, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        right.setPadding(dp(28), dp(24), dp(28), dp(24));
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.33f);
        rightLp.setMarginStart(dp(20));
        root.addView(right, rightLp);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.blofy_brand);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        right.addView(logo, new LinearLayout.LayoutParams(dp(124), dp(124)));

        ImageView qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(8), dp(8), dp(8), dp(8));
        Bitmap qrBitmap = qr(pairingUrl(), 320);
        if (qrBitmap != null) qr.setImageBitmap(qrBitmap);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(218), dp(218));
        qrLp.topMargin = dp(12);
        right.addView(qr, qrLp);

        TextView renew = text("إضافة / إدارة / تجديد", 17, true);
        renew.setGravity(Gravity.CENTER);
        right.addView(renew, wrapTop(14));

        TextView deviceLabel = text("رقم الجهاز", 13, false);
        deviceLabel.setTextColor(getColor(R.color.blofy_muted));
        deviceLabel.setGravity(Gravity.CENTER);
        right.addView(deviceLabel, wrapTop(20));

        TextView device = text(api.deviceId(), 18, true);
        device.setGravity(Gravity.CENTER);
        device.setTextIsSelectable(true);
        right.addView(device, wrapTop(4));

        TextView hint = text("امسح QR من الجوال لإضافة قائمة جديدة أو تحديث بيانات جهازك.", 13, false);
        hint.setTextColor(getColor(R.color.blofy_muted));
        hint.setGravity(Gravity.CENTER);
        hint.setMaxLines(3);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(22);
        right.addView(hint, hintLp);
        return root;
    }

    private void renderPlaylists() {
        playlistColumn.removeAllViews();
        List<PlaylistProfile> rows = PlaylistRepository.all(this);
        active = PlaylistRepository.active(this);
        if (rows.isEmpty()) {
            playlistColumn.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));
            return;
        }

        MaterialButton focusTarget = null;
        for (PlaylistProfile playlist : rows) {
            MaterialButton card = actionButton(label(playlist));
            card.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            card.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            card.setSelected(active != null && active.id.equals(playlist.id));
            card.setOnClickListener(v -> activate(playlist));
            card.setOnLongClickListener(v -> {
                PlaylistStateStore.clear(this, playlist.id);
                CompatibilityProfileStore.remove(this, playlist.id);
                PlaylistRepository.remove(this, playlist.id);
                renderPlaylists();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(82));
            lp.bottomMargin = dp(10);
            playlistColumn.addView(card, lp);
            if (focusTarget == null || card.isSelected()) focusTarget = card;
        }
        if (focusTarget != null) focusTarget.post(focusTarget::requestFocus);
    }

    private String label(PlaylistProfile playlist) {
        String name = playlist.provider.name;
        if (name == null || name.trim().isEmpty()) {
            name = playlist.provider.isXtream() ? playlist.provider.serverUrl : "M3U Playlist";
        }
        boolean selected = active != null && active.id.equals(playlist.id);
        boolean ready = PlaylistStateStore.isReady(this, playlist.id);
        boolean discovered = CompatibilityProfileStore.load(this, playlist.id) != null;
        String type = playlist.provider.isXtream() ? "Xtream" : "M3U";
        String state = ready ? "جاهزة" : discovered ? "تحتاج تحديث" : "جديدة";
        return (selected ? "●  " : "") + name + "\n" + type + "  •  " + state;
    }

    private void activate(PlaylistProfile playlist) {
        PlaylistRepository.setActive(this, playlist.id);
        Class<?> target;
        if (PlaylistStateStore.isReady(this, playlist.id)) target = HomeActivity.class;
        else if (CompatibilityProfileStore.load(this, playlist.id) != null) target = SyncActivity.class;
        else target = DiscoveryActivity.class;
        startActivity(new Intent(this, target).putExtra("playlist_id", playlist.id));
    }

    private String pairingUrl() {
        String base = api.baseUrl() + "/activate?device_id=" + ApiClient.encode(api.deviceId());
        String token = DeviceIdentity.pairToken(this);
        return token == null || token.isEmpty() ? base
                : base + "&pair_token=" + ApiClient.encode(token);
    }

    private MaterialButton actionButton(String value) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setTextColor(getColor(R.color.blofy_text));
        button.setTextSize(16);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setBackgroundResource(R.drawable.bg_home_status);
        button.setPadding(dp(22), dp(10), dp(22), dp(10));
        button.setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? 1.015f : 1f)
                .scaleY(focused ? 1.015f : 1f)
                .setDuration(70).start());
        return button;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(getColor(R.color.blofy_text));
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        return view;
    }

    private LinearLayout.LayoutParams wrapTop(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static Bitmap qr(String value, int size) {
        try {
            BitMatrix bits = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) pixels[y * size + x] = bits.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            return bitmap;
        } catch (Exception ignored) { return null; }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            if (PlaylistRepository.active(this) != null) startActivity(new Intent(this, HomeActivity.class));
            finish(); return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
