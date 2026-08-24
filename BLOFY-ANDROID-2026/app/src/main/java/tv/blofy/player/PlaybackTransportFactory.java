package tv.blofy.player;

import android.content.Context;
import android.util.Log;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.datasource.cronet.CronetUtil;

import com.google.android.gms.net.CronetProviderInstaller;

import org.chromium.net.CronetEngine;

import java.util.concurrent.Executor;

/** Builds BLOFY Player1 (platform HTTP) and Player2 (Google Play Services Cronet). */
@UnstableApi
final class PlaybackTransportFactory {
    private static final String TAG = "BlofyTransport";
    private static final String USER_AGENT = "BLOFY-PLAYER/2026 AndroidTV";
    private static volatile CronetEngine cronetEngine;
    private static volatile boolean cronetInstallStarted;

    private PlaybackTransportFactory() {}

    static void warmUpCronet(Context context) {
        if (cronetEngine != null || cronetInstallStarted) return;
        synchronized (PlaybackTransportFactory.class) {
            if (cronetEngine != null || cronetInstallStarted) return;
            cronetInstallStarted = true;
        }

        Context appContext = context.getApplicationContext();
        try {
            CronetProviderInstaller.installProvider(appContext)
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful()) {
                            Log.w(TAG, "cronet-provider-install-failed", task.getException());
                            return;
                        }
                        try {
                            cronetEngine = CronetUtil.buildCronetEngine(appContext, null, true);
                            Log.i(TAG, cronetEngine != null
                                    ? "cronet-provider-ready"
                                    : "cronet-provider-ready-but-engine-unavailable");
                        } catch (Throwable error) {
                            Log.w(TAG, "cronet-engine-init-failed", error);
                            cronetEngine = null;
                        }
                    });
        } catch (Throwable error) {
            Log.w(TAG, "cronet-provider-install-start-failed", error);
        }
    }

    static DataSource.Factory create(Context context, boolean preferCronet, Executor executor) {
        if (preferCronet) {
            CronetEngine engine = cronetEngine;
            if (engine != null) {
                Log.i(TAG, "transport=cronet-gms");
                return new DefaultDataSource.Factory(
                        context,
                        new CronetDataSource.Factory(engine, executor));
            }
            Log.w(TAG, "transport=cronet-unavailable fallback=default-http");
        }

        Log.i(TAG, "transport=default-http");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(3_500)
                .setReadTimeoutMs(10_000)
                .setAllowCrossProtocolRedirects(true);
        return new DefaultDataSource.Factory(context, http);
    }
}
