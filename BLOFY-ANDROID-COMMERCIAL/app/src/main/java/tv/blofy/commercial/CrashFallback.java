package tv.blofy.commercial;

import android.content.Context;
import android.content.SharedPreferences;

final class CrashFallback implements Thread.UncaughtExceptionHandler {
    private final Context context;
    private final Thread.UncaughtExceptionHandler parent;

    CrashFallback(Context context, Thread.UncaughtExceptionHandler parent) {
        this.context = context;
        this.parent = parent;
    }

    @Override public void uncaughtException(Thread thread, Throwable error) {
        SharedPreferences preferences = context.getSharedPreferences("blofy_diagnostics", Context.MODE_PRIVATE);
        preferences.edit().putLong("last_crash_at", System.currentTimeMillis())
                .putString("last_crash", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())).apply();
        if (parent != null) parent.uncaughtException(thread, error);
    }
}
