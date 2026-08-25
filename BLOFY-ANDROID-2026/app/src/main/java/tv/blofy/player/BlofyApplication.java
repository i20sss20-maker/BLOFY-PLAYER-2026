package tv.blofy.player;

import android.app.Application;

/**
 * Process bootstrap.
 *
 * The launcher intentionally stays on MainActivity's playlist hub. A saved or
 * synchronized playlist is never connected merely because the app was opened;
 * the viewer always confirms the source with the visible "اتصال" action.
 */
public final class BlofyApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        PlaybackTransportFactory.warmUpCronet(this);
    }
}
