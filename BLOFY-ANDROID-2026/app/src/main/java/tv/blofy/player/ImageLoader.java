package tv.blofy.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ImageLoader {
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
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
                byte[] bytes = isDirectHttp(path) ? directImage(path) : api.image(path);
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

    private static boolean isDirectHttp(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    /** Provider artwork is fetched directly without BLOFY cookies or device credentials. */
    private static byte[] directImage(String value) throws Exception {
        URL url = new URL(value);
        String scheme = url.getProtocol();
        if (url.getHost() == null || url.getHost().isEmpty()
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Invalid image URL");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(true);
        connection.setRequestProperty("Accept", "image/*,*/*;q=0.5");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Image HTTP " + status);
        }

        int declaredLength = connection.getContentLength();
        if (declaredLength > MAX_IMAGE_BYTES) {
            connection.disconnect();
            throw new IllegalStateException("Image too large");
        }

        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     declaredLength > 0 ? Math.min(declaredLength, MAX_IMAGE_BYTES) : 32 * 1024)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (output.size() > MAX_IMAGE_BYTES) throw new IllegalStateException("Image too large");
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }
}
