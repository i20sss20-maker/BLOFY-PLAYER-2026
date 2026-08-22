package tv.blofy.commercial.ui.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import tv.blofy.commercial.R;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.databinding.ActivitySettingsBinding;
import tv.blofy.commercial.ui.activation.ActivationActivity;
import tv.blofy.commercial.ui.sync.SyncActivity;

public final class SettingsActivity extends LicensedActivity {
    private ActivitySettingsBinding binding;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        prefs = getSharedPreferences("blofy_player_settings", MODE_PRIVATE);
        String[] playerLabels = {"تلقائي", "Media3", "LibVLC", "MX Player", "MX Player Pro", "VLC", "أي مشغل خارجي"};
        String[] playerValues = {"auto", "media3", "libvlc", "mx_free", "mx_pro", "vlc_external", "external"};
        ArrayAdapter<String> playerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, playerLabels);
        playerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.playerEngine.setAdapter(playerAdapter);
        String savedPlayer = prefs.getString("player_engine", "auto");
        int selectedPlayer = 0;
        for (int i = 0; i < playerValues.length; i++) if (playerValues[i].equals(savedPlayer)) selectedPlayer = i;
        binding.playerEngine.setSelection(selectedPlayer, false);
        renderPlayer(savedPlayer);
        binding.playerEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String value = playerValues[Math.max(0, Math.min(position, playerValues.length - 1))];
                prefs.edit().putString("player_engine", value).apply();
                renderPlayer(value);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        String quality = prefs.getString("quality", "auto");
        binding.quality.check("sd".equals(quality) ? R.id.sdQuality : "hd".equals(quality) ? R.id.hdQuality : R.id.autoQuality);
        binding.buffer.check("stable".equals(prefs.getString("buffer", "fast")) ? R.id.stableBuffer : R.id.fastBuffer);
        binding.subtitles.setChecked(prefs.getBoolean("subtitles", true)); binding.autoplay.setChecked(prefs.getBoolean("autoplay", true));
        renderQuality(quality);
        renderBuffer(prefs.getString("buffer", "fast"));
        binding.quality.addOnButtonCheckedListener((g,id,checked)->{
            if (!checked) return;
            String selected = id == R.id.sdQuality ? "sd" : id == R.id.hdQuality ? "hd" : "auto";
            prefs.edit().putString("quality", selected).apply();
            renderQuality(selected);
        });
        binding.buffer.addOnButtonCheckedListener((g,id,checked)->{
            if (!checked) return;
            String selected = id == R.id.stableBuffer ? "stable" : "fast";
            prefs.edit().putString("buffer", selected).apply();
            renderBuffer(selected);
        });
        binding.subtitles.setOnCheckedChangeListener((v,c)->prefs.edit().putBoolean("subtitles",c).apply());
        binding.autoplay.setOnCheckedChangeListener((v,c)->prefs.edit().putBoolean("autoplay",c).apply());
        binding.back.setOnClickListener(v -> finish());
        binding.sync.setOnClickListener(v->startActivity(new Intent(this, SyncActivity.class)));
        binding.account.setOnClickListener(v -> startActivity(new Intent(this, ActivationActivity.class)
                .putExtra("force_form", true)
                .putExtra("boot_error", "يمكنك تجديد التفعيل أو تحديث بيانات الباقة من هنا.")));
        installFocusFeedback(binding.back, binding.autoQuality, binding.hdQuality, binding.sdQuality,
                binding.playerEngine, binding.fastBuffer, binding.stableBuffer, binding.subtitles, binding.autoplay,
                binding.sync, binding.account);

        // A TV remote needs a deterministic first target. Material toggle groups
        // do not consistently choose the checked child on every Android TV OEM.
        binding.getRoot().post(() -> {
            int checked = binding.quality.getCheckedButtonId();
            View target = checked == View.NO_ID ? binding.autoQuality : binding.getRoot().findViewById(checked);
            if (target != null) target.requestFocus();
        });
    }

    private void renderPlayer(String value) {
        String text;
        switch (value) {
            case "media3": text = "Media3 داخلي • المحرك الأساسي السريع"; break;
            case "libvlc": text = "LibVLC داخلي • توافق واسع مع الصيغ"; break;
            case "mx_free": text = "MX Player خارجي • يعود إلى Media3 إذا لم يكن مثبتًا"; break;
            case "mx_pro": text = "MX Player Pro خارجي • يعود إلى Media3 إذا لم يكن مثبتًا"; break;
            case "vlc_external": text = "VLC الخارجي • يعود إلى Media3 إذا لم يكن مثبتًا"; break;
            case "external": text = "يفتح قائمة المشغلات المثبتة على الجهاز"; break;
            default: text = "تلقائي • Media3 أولًا ثم LibVLC عند مشاكل الصيغة"; break;
        }
        binding.playerEngineDescription.setText(text);
    }

    private void renderQuality(String quality) {
        if ("sd".equals(quality)) {
            binding.qualityDescription.setText("SD • حد أقصى 480p لتقليل استهلاك الشبكة");
        } else if ("hd".equals(quality)) {
            binding.qualityDescription.setText("HD • حد أقصى 1080p عند توفره من الباقة");
        } else {
            binding.qualityDescription.setText("تلقائي • يختار Media3 أفضل جودة مناسبة للشبكة");
        }
    }

    private void renderBuffer(String buffer) {
        binding.bufferDescription.setText("stable".equals(buffer)
                ? "أكثر ثباتًا • مخزن أكبر للشبكات المتذبذبة"
                : "بدء سريع • مخزن قصير لفتح القناة بأسرع وقت");
    }

    private static void installFocusFeedback(View... controls) {
        for (View control : controls) {
            control.setOnFocusChangeListener((view, focused) -> view.animate()
                    .scaleX(focused ? 1.025f : 1f)
                    .scaleY(focused ? 1.025f : 1f)
                    .translationZ(focused ? 10f : 0f)
                    .setDuration(110)
                    .start());
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
