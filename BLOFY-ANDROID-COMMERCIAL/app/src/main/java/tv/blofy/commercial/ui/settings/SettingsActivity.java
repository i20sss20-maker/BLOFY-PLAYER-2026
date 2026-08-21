package tv.blofy.commercial.ui.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import tv.blofy.commercial.R;
import tv.blofy.commercial.databinding.ActivitySettingsBinding;
import tv.blofy.commercial.ui.sync.SyncActivity;

public final class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding binding;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        prefs = getSharedPreferences("blofy_player_settings", MODE_PRIVATE);
        String quality = prefs.getString("quality", "auto");
        binding.quality.check("sd".equals(quality) ? R.id.sdQuality : "hd".equals(quality) ? R.id.hdQuality : R.id.autoQuality);
        binding.buffer.check("stable".equals(prefs.getString("buffer", "fast")) ? R.id.stableBuffer : R.id.fastBuffer);
        binding.subtitles.setChecked(prefs.getBoolean("subtitles", true)); binding.autoplay.setChecked(prefs.getBoolean("autoplay", true));
        binding.quality.addOnButtonCheckedListener((g,id,checked)->{if(checked)prefs.edit().putString("quality",id==R.id.sdQuality?"sd":id==R.id.hdQuality?"hd":"auto").apply();});
        binding.buffer.addOnButtonCheckedListener((g,id,checked)->{if(checked)prefs.edit().putString("buffer",id==R.id.stableBuffer?"stable":"fast").apply();});
        binding.subtitles.setOnCheckedChangeListener((v,c)->prefs.edit().putBoolean("subtitles",c).apply());
        binding.autoplay.setOnCheckedChangeListener((v,c)->prefs.edit().putBoolean("autoplay",c).apply());
        binding.sync.setOnClickListener(v->startActivity(new Intent(this, SyncActivity.class)));
    }
}
