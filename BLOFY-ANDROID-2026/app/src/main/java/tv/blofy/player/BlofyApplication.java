package tv.blofy.player;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BLOFY process bootstrap.
 * Warms the optional transport and routes an authenticated, synchronized
 * MainActivity into the new television shell without exposing stale catalog data.
 */
public final class BlofyApplication extends Application {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService routerWorker = Executors.newSingleThreadExecutor();
    private Activity resumedMain;
    private boolean authChecked;
    private boolean authUsable;
    private boolean routing;

    private final Runnable routePoll = new Runnable() {
        @Override public void run() {
            Activity activity = resumedMain;
            if (!(activity instanceof MainActivity) || activity.isFinishing() || routing) return;

            CatalogDatabase database = new CatalogDatabase(activity);
            boolean ready;
            try {
                ready = "complete".equals(database.metadata("sync_state", ""))
                        && database.count("live") + database.count("movies") + database.count("series") > 0;
            } finally {
                database.close();
            }

            if (ready && authUsable) {
                routing = true;
                Intent intent = new Intent(activity, SevenMaxActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                activity.startActivity(intent);
                activity.finish();
                resumedMain = null;
                return;
            }
            main.postDelayed(this, 450L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        PlaybackTransportFactory.warmUpCronet(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (!(activity instanceof MainActivity)) return;
                resumedMain = activity;
                routing = false;
                main.removeCallbacks(routePoll);
                verifyAuthThenPoll(activity);
            }

            @Override public void onActivityPaused(Activity activity) {
                if (activity == resumedMain) {
                    resumedMain = null;
                    main.removeCallbacks(routePoll);
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private void verifyAuthThenPoll(Activity activity) {
        if (authChecked && authUsable) {
            main.post(routePoll);
            return;
        }
        routerWorker.execute(() -> {
            boolean usable = false;
            try {
                BlofyApi api = new BlofyApi(activity);
                BlofyModels.License license = new BlofyModels.License(
                        api.get("/api/license?device_id=" + BlofyApi.encode(api.deviceId())));
                BlofyModels.Session session = new BlofyModels.Session(api.get("/api/session"));
                usable = license.usable() && session.present;
            } catch (Exception ignored) {
                // MainActivity owns the visible error/retry state.
            }
            final boolean result = usable;
            main.post(() -> {
                if (activity != resumedMain) return;
                authChecked = true;
                authUsable = result;
                if (result) main.post(routePoll);
            });
        });
    }
}
