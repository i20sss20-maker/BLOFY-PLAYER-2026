package tv.blofy.player;

import android.content.Context;
import android.util.Log;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.datasource.cronet.CronetUtil;

import org.chromium.net.CronetEngine;

import java.util.concurrent.Executor;

/** Builds Player1 (Default HTTP) and Player2 (Google Play Services Cronet). */
@UnstableApi
final class PlaybackTransportFactory {
    private static final String TAG = "BlofyTransport";
    private static volatile CronetEngine cronetEngine;
    private static volatile boolean cronetAttempted;

    private PlaybackTransportFactory() {}

    static DataSource.Factory create(Context context, boolean preferCronet, Executor executor) {
        if (preferCronet) {
            CronetEngine engine = getCronetEngine(context.getApplicationContext());
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
                .setAllowCrossProtocolRedirects(true);
        return new DefaultDataSource.Factory(context, http);
    }

    private static CronetEngine getCronetEngine(Context context) {
        if (cronetAttempted) return cronetEngine;
        synchronized (PlaybackTransportFactory.class) {
            if (!cronetAttempted) {
                try {
                    // 7 Max contains the GMS Cronet provider classes and no bundled
                    // libcronet.so in its ARM64 split. Prefer the same provider shape.
                    cronetEngine = CronetUtil.buildCronetEngine(context, null, true);
                } catch (Throwable error) {
                    Log.w(TAG, "cronet-init-failed", error);
                    cronetEngine = null;
                }
                cronetAttempted = true;
            }
        }
        return cronetEngine;
    }
}
