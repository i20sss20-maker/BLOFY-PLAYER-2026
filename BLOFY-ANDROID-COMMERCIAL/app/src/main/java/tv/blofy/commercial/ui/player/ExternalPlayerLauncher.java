package tv.blofy.commercial.ui.player;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

/** Launches optional external players without making them a hard dependency of BLOFY. */
public final class ExternalPlayerLauncher {
    public static final String MX_FREE = "com.mxtech.videoplayer.ad";
    public static final String MX_PRO = "com.mxtech.videoplayer.pro";
    public static final String VLC = "org.videolan.vlc";

    private ExternalPlayerLauncher() { }

    public static boolean launch(Activity activity, String packageName, String url,
                                 String mimeType, String title) {
        if (activity == null || url == null || url.trim().isEmpty()) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), mimeType == null || mimeType.isEmpty() ? "video/*" : mimeType);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra("title", title == null ? "BLOFY PLAYER" : title);
            if (packageName != null && !packageName.isEmpty()) intent.setPackage(packageName);
            if (intent.resolveActivity(activity.getPackageManager()) == null) return false;
            activity.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean launchChooser(Activity activity, String url, String mimeType, String title) {
        if (activity == null || url == null || url.trim().isEmpty()) return false;
        try {
            Intent base = new Intent(Intent.ACTION_VIEW);
            base.setDataAndType(Uri.parse(url), mimeType == null || mimeType.isEmpty() ? "video/*" : mimeType);
            base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            base.putExtra("title", title == null ? "BLOFY PLAYER" : title);
            if (base.resolveActivity(activity.getPackageManager()) == null) return false;
            activity.startActivity(Intent.createChooser(base, "اختر مشغل الفيديو"));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
