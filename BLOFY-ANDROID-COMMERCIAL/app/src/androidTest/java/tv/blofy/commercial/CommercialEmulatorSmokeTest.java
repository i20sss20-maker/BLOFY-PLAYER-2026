package tv.blofy.commercial;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import tv.blofy.commercial.provider.PlaylistProfile;
import tv.blofy.commercial.provider.PlaylistRepository;
import tv.blofy.commercial.provider.ProviderProfile;
import tv.blofy.commercial.ui.discovery.DiscoveryActivity;
import tv.blofy.commercial.ui.home.HomeActivity;

/**
 * Real-provider smoke test executed only by GitHub's Android emulator workflow.
 * Provider credentials arrive as masked instrumentation arguments and are never committed.
 */
@RunWith(AndroidJUnit4.class)
public final class CommercialEmulatorSmokeTest {
    private static final long SYNC_TIMEOUT_MS = 360_000L;
    private static final long SCREEN_TIMEOUT_MS = 25_000L;

    private Context context;
    private UiDevice device;
    private Bundle args;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        args = InstrumentationRegistry.getArguments();

        context.getSharedPreferences("blofy_emulator_test", Context.MODE_PRIVATE)
                .edit().putBoolean("skip_license", true).commit();

        List<PlaylistProfile> existing = new ArrayList<>(PlaylistRepository.all(context));
        for (PlaylistProfile row : existing) PlaylistRepository.remove(context, row.id);

        String kind = value("providerKind", "xtream").toLowerCase();
        String server = value("serverUrl", "");
        String username = value("username", "");
        String password = value("password", "");
        String playlistUrl = value("playlistUrl", "");

        ProviderProfile provider;
        if ("m3u".equals(kind)) {
            assertTrue("playlistUrl instrumentation argument is required for M3U", !playlistUrl.isEmpty());
            provider = new ProviderProfile("m3u", "BLOFY CI", "", "", "", playlistUrl);
        } else {
            assertTrue("serverUrl instrumentation argument is required", !server.isEmpty());
            assertTrue("username instrumentation argument is required", !username.isEmpty());
            assertTrue("password instrumentation argument is required", !password.isEmpty());
            provider = new ProviderProfile("xtream", "BLOFY CI", server, username, password, "");
        }
        assertTrue("Provider profile is invalid", provider.isValid());
        PlaylistProfile playlist = PlaylistProfile.create(provider);
        assertNotNull(PlaylistRepository.upsert(context, playlist, true));

        device.pressHome();
        Thread.sleep(800L);
    }

    @Test public void fullProviderFlowDoesNotCrash() throws Exception {
        Intent start = new Intent(context, DiscoveryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(start);

        boolean home = device.wait(Until.hasObject(By.textContains("LIVE TV")), SYNC_TIMEOUT_MS);
        screenshot("01-home");
        assertTrue("BLOFY did not reach Home after Discovery/Sync", home);
        assertInBlofyPackage("home");

        // LIVE: open screen and click the first visible channel row directly.
        clickText("LIVE TV");
        assertTrue("Live screen did not open", device.wait(Until.hasObject(By.textContains("LIVE TV")), SCREEN_TIMEOUT_MS));
        screenshot("02-live");
        device.click(720, 250);
        Thread.sleep(12_000L);
        screenshot("03-live-player");
        assertInBlofyPackage("live player");
        openHome();

        // MOVIES: click the first visible poster card directly.
        clickText("MOVIES");
        assertTrue("Movies library did not open", device.wait(Until.hasObject(By.textContains("MOVIES")), SCREEN_TIMEOUT_MS));
        screenshot("04-movies");
        device.click(610, 410);
        Thread.sleep(4_500L);
        screenshot("05-movie-details");
        assertInBlofyPackage("movie details");
        openHome();

        // SERIES: click the first visible poster card directly.
        clickText("SERIES");
        assertTrue("Series library did not open", device.wait(Until.hasObject(By.textContains("SERIES")), SCREEN_TIMEOUT_MS));
        screenshot("06-series");
        device.click(610, 410);
        Thread.sleep(5_000L);
        screenshot("07-series-details");
        assertInBlofyPackage("series details");
    }

    private void openHome() throws Exception {
        Intent home = new Intent(context, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(home);
        assertTrue("Could not open BLOFY Home", device.wait(Until.hasObject(By.textContains("LIVE TV")), SCREEN_TIMEOUT_MS));
        assertInBlofyPackage("home return");
        Thread.sleep(500L);
    }

    private void clickText(String text) throws Exception {
        androidx.test.uiautomator.UiObject2 object = device.wait(Until.findObject(By.textContains(text)), SCREEN_TIMEOUT_MS);
        assertNotNull("Could not find UI text: " + text, object);
        object.click();
        Thread.sleep(1_000L);
    }

    private void assertInBlofyPackage(String stage) {
        String current = device.getCurrentPackageName();
        assertTrue("App left BLOFY package during " + stage + ": " + current,
                current != null && (current.equals("tv.blofy.player") || current.startsWith("tv.blofy.player")));
    }

    private void screenshot(String name) {
        File directory = new File("/sdcard/Download/blofy-ci");
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        device.takeScreenshot(new File(directory, name + ".png"));
    }

    private String value(String key, String fallback) {
        String raw = args == null ? null : args.getString(key);
        return raw == null || raw.trim().isEmpty() ? fallback : raw.trim();
    }
}
