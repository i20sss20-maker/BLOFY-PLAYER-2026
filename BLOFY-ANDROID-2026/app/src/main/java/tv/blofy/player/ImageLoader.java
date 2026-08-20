package tv.blofy.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final BlofyApi api;

    ImageLoader(BlofyApi api) { this.api = api; }

    void load(ImageView view, String path) {
        if (path == null || path.isEmpty()) {
            view.setImageResource(R.drawable.blofy_logo);
            return;
        }
        view.setTag(path);
        Bitmap cached = CACHE.get(path);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(R.drawable.blofy_logo);
        WeakReference<ImageView> reference = new WeakReference<>(view);
        POOL.execute(() -> {
            try {
                byte[] bytes = api.image(path);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) return;
                CACHE.put(path, bitmap);
                MAIN.post(() -> {
                    ImageView target = reference.get();
                    if (target != null && path.equals(target.getTag())) target.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {}
        });
    }
}
