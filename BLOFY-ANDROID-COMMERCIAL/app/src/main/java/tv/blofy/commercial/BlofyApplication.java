package tv.blofy.commercial;

import android.app.Application;

public final class BlofyApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashFallback(getApplicationContext(), Thread.getDefaultUncaughtExceptionHandler()));
    }
}
