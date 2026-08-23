package tv.blofy.player;

import android.content.Context;
import android.widget.Toast;

final class ToastBridge {
    private ToastBridge() {}
    static void show(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
