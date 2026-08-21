package tv.blofy.commercial.ui.home;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.databinding.ActivityHomeBinding;
import tv.blofy.commercial.ui.catalog.CatalogActivity;
import tv.blofy.commercial.ui.sync.SyncActivity;
import tv.blofy.commercial.ui.settings.SettingsActivity;

public final class HomeActivity extends AppCompatActivity {
    private ActivityHomeBinding binding;
    private CatalogStore store;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        store = new CatalogStore(this);
        binding.welcome.setText("باقتك جاهزة • " + store.count("live") + " قناة • " + store.count("movies") + " فيلم • " + store.count("series") + " مسلسل");
        binding.live.setOnClickListener(v -> catalog("live", false, false));
        binding.movies.setOnClickListener(v -> catalog("movies", false, false));
        binding.series.setOnClickListener(v -> catalog("series", false, false));
        binding.favorites.setOnClickListener(v -> catalog("", true, false));
        binding.history.setOnClickListener(v -> catalog("", false, true));
        binding.settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        binding.live.requestFocus();
        binding.status.setOnClickListener(v -> startActivity(new Intent(this, SyncActivity.class)));
    }

    private void catalog(String type, boolean favorites, boolean history) {
        Intent intent = new Intent(this, CatalogActivity.class);
        intent.putExtra("type", type).putExtra("favorites", favorites).putExtra("history", history);
        startActivity(intent);
    }

    @Override protected void onDestroy() { if (store != null) store.close(); super.onDestroy(); }
}
