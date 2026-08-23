package tv.blofy.player;

import android.app.Application;

/** Prepares optional playback transports without delaying the first screen. */
public final class BlofyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PlaybackTransportFactory.warmUpCronet(this);
    }
}
