package tv.blofy.commercial.core;

import android.content.Context;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestOptions;

import java.util.Map;

import tv.blofy.commercial.R;

/** Resolves Railway-relative signed image paths and carries the native session. */
public final class BlofyImageLoader {
    private BlofyImageLoader() {}

    public static void poster(Context context, ImageView view, String raw) {
        load(context, view, raw, true);
    }

    public static void backdrop(Context context, ImageView view, String raw) {
        load(context, view, raw, false);
    }

    private static void load(Context context, ImageView view, String raw, boolean poster) {
        ApiClient api = new ApiClient(context);
        String target = api.absoluteUrl(raw);
        Glide.with(view).clear(view);
        if (target.isEmpty()) {
            view.setImageResource(poster ? R.drawable.placeholder_poster : R.drawable.placeholder_backdrop);
            return;
        }
        Object request = target;
        // Never forward the BLOFY cookie/device key to an IPTV image host.
        // Authenticated headers are only valid for our own Railway origin.
        if (target.startsWith(api.baseUrl() + "/")) {
            LazyHeaders.Builder headers = new LazyHeaders.Builder();
            for (Map.Entry<String, String> entry : api.authenticatedHeaders().entrySet()) {
                headers.addHeader(entry.getKey(), entry.getValue());
            }
            request = new GlideUrl(target, headers.build());
        }
        RequestOptions options = new RequestOptions()
                .placeholder(poster ? R.drawable.placeholder_poster : R.drawable.placeholder_backdrop)
                .error(poster ? R.drawable.placeholder_poster : R.drawable.placeholder_backdrop)
                .centerCrop();
        Glide.with(context).load(request).apply(options).into(view);
    }
}
