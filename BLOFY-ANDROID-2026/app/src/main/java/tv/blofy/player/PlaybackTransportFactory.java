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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Builds BLOFY platform HTTP and Google Play Services Cronet transports. */
@UnstableApi
final class PlaybackTransportFactory {
    private static final String TAG = "BlofyTransport";
    private static final String USER_AGENT = "BLOFY-PLAYER/2026 AndroidTV";
    private static final String EXOPLAYER_USER_AGENT = "ExoPlayerLib/1.11.0 (BLOFY AndroidTV)";
    private static final String VLC_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";
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
                            cronetInstallStarted = false;
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
                            cronetInstallStarted = false;
                        }
                    });
        } catch (Throwable error) {
            Log.w(TAG, "cronet-provider-install-start-failed", error);
            cronetInstallStarted = false;
        }
    }

    static boolean isCronetReady() {
        return cronetEngine != null;
    }

    static DataSource.Factory create(Context context, boolean preferCronet, Executor executor) {
        return create(context, preferCronet, executor, 3_500, 10_000);
    }

    static DataSource.Factory create(Context context, boolean preferCronet, Executor executor,
                                     int connectTimeoutMs, int readTimeoutMs) {
        return create(context, preferCronet, executor, connectTimeoutMs, readTimeoutMs, 0, "");
    }

    static DataSource.Factory create(Context context, boolean preferCronet, Executor executor,
                                     int connectTimeoutMs, int readTimeoutMs,
                                     int compatibilityProfile, String referer) {
        String userAgent = userAgent(compatibilityProfile);
        if (preferCronet) {
            CronetEngine engine = cronetEngine;
            if (engine != null) {
                Log.i(TAG, "transport=cronet-gms profile=" + compatibilityProfile);
                return new DefaultDataSource.Factory(
                        context,
                        new CronetDataSource.Factory(engine, executor).setUserAgent(userAgent));
            }
            Log.w(TAG, "transport=cronet-unavailable fallback=default-http");
        }

        Log.i(TAG, "transport=default-http connect=" + connectTimeoutMs + " read=" + readTimeoutMs);
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "identity");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", "1");
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
            try {
                android.net.Uri uri = android.net.Uri.parse(referer);
                if (uri.getScheme() != null && uri.getHost() != null) {
                    String origin = uri.getScheme() + "://" + uri.getHost()
                            + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
                    headers.put("Origin", origin);
                }
            } catch (Exception ignored) {}
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(connectTimeoutMs)
                .setReadTimeoutMs(readTimeoutMs)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);
        return new DefaultDataSource.Factory(context, http);
    }

    static String userAgent(int compatibilityProfile) {
        if (compatibilityProfile >= 2) return VLC_USER_AGENT;
        if (compatibilityProfile == 1) return EXOPLAYER_USER_AGENT;
        return USER_AGENT;
    }
}
