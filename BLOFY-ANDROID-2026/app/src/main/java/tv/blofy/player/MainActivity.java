package tv.blofy.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.net.http.SslError;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS = "blofy_settings";
    private static final String KEY_BASE_URL = "portal_base_url";

    private FrameLayout root;
    private WebView webView;
    private View setupView;
    private String activeBaseUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(6, 6, 10));
        getWindow().setNavigationBarColor(Color.rgb(6, 6, 10));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(6, 6, 10));
        setContentView(root);

        String configured = normalizedBaseUrl(getPreferences().getString(KEY_BASE_URL, BuildConfig.BLOFY_BASE_URL));
        if (configured == null || configured.contains("YOUR-RAILWAY-DOMAIN")) showSetup("");
        else loadPortal(configured);
    }

    private android.content.SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static String normalizedBaseUrl(String value) {
        try {
            String clean = String.valueOf(value).trim();
            while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
            Uri uri = Uri.parse(clean);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
            return clean;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(6), dp(8), dp(6));
        return view;
    }

    private void showSetup(String error) {
        if (webView != null) webView.setVisibility(View.GONE);
        if (setupView != null) root.removeView(setupView);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(28), dp(24), dp(28), dp(24));
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.blofy_logo);
        logo.setAdjustViewBounds(true);
        panel.addView(logo, new LinearLayout.LayoutParams(dp(190), dp(190)));

        TextView title = text("ربط BLOFY PLAYER بخادم Railway", 25, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView caption = text("ألصق رابط Railway العام الذي يبدأ بـ https. يُحفظ الرابط على هذا الجهاز ويمكن تغييره لاحقًا من الإعدادات.", 14, Color.rgb(166, 163, 181));
        caption.setMaxWidth(dp(650));
        panel.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(110, 108, 124));
        input.setHint("https://project.up.railway.app");
        input.setText(activeBaseUrl != null ? activeBaseUrl : "");
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        input.setBackgroundColor(Color.rgb(18, 20, 34));
        input.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(Math.min(dp(650), getResources().getDisplayMetrics().widthPixels - dp(56)), dp(58));
        inputParams.topMargin = dp(24);
        panel.addView(input, inputParams);

        Button connect = new Button(this);
        connect.setText("ربط وتشغيل");
        connect.setTextColor(Color.WHITE);
        connect.setTextSize(16);
        connect.setAllCaps(false);
        connect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(124, 50, 255)));
        connect.setOnClickListener(view -> {
            String baseUrl = normalizedBaseUrl(input.getText().toString());
            if (baseUrl == null) {
                input.setError("أدخل رابط HTTPS صحيحًا من Railway");
                input.requestFocus();
                return;
            }
            getPreferences().edit().putString(KEY_BASE_URL, baseUrl).apply();
            loadPortal(baseUrl);
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(Math.min(dp(650), getResources().getDisplayMetrics().widthPixels - dp(56)), dp(56));
        buttonParams.topMargin = dp(14);
        panel.addView(connect, buttonParams);

        if (!error.isEmpty()) {
            TextView errorView = text(error, 13, Color.rgb(255, 128, 151));
            LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            errorParams.topMargin = dp(12);
            panel.addView(errorView, errorParams);
        }

        setupView = panel;
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        connect.requestFocus();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void loadPortal(String baseUrl) {
        activeBaseUrl = baseUrl;
        if (setupView != null) {
            root.removeView(setupView);
            setupView = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.removeJavascriptInterface("BlofyAndroid");
            webView.destroy();
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(6, 6, 10));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " BLOFY-ANDROID/2026 Media3/1.11");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);
        webView.addJavascriptInterface(new AndroidBridge(), "BlofyAndroid");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                Uri allowed = Uri.parse(activeBaseUrl);
                if ("https".equalsIgnoreCase(target.getScheme()) && allowed.getHost() != null && allowed.getHost().equalsIgnoreCase(target.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, target)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.requestFocus();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showSetup("تعذر الاتصال بالخادم. تأكد من نشر Railway ومن صحة الرابط ثم أعد المحاولة.");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showSetup("شهادة HTTPS غير صالحة. استخدم رابط Railway الرسمي الآمن.");
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadUrl(baseUrl + "/");
        webView.requestFocus();
    }

    private String deviceId() {
        try {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("tv.blofy.player:" + androidId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 8; index++) hex.append(String.format(Locale.US, "%02X", hash[index]));
            String value = hex.toString();
            return "BLOFY-" + value.substring(0, 4) + "-" + value.substring(4, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16);
        } catch (Exception ignored) {
            return "BLOFY-ANDROID-DEVICE";
        }
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public String getDeviceId() {
            return deviceId();
        }

        @JavascriptInterface
        public void play(String url, String title, String kind, String extension) {
            runOnUiThread(() -> {
                try {
                    Uri target = Uri.parse(url);
                    Uri allowed = Uri.parse(activeBaseUrl);
                    if (!"https".equalsIgnoreCase(target.getScheme()) || allowed.getHost() == null || !allowed.getHost().equalsIgnoreCase(target.getHost()) || !target.getPath().startsWith("/api/play/")) {
                        throw new IllegalArgumentException("رابط تشغيل غير مسموح");
                    }
                    String cookie = CookieManager.getInstance().getCookie(url);
                    Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                    intent.putExtra(PlayerActivity.EXTRA_URL, url);
                    intent.putExtra(PlayerActivity.EXTRA_TITLE, title);
                    intent.putExtra(PlayerActivity.EXTRA_KIND, kind);
                    intent.putExtra(PlayerActivity.EXTRA_EXTENSION, extension);
                    intent.putExtra(PlayerActivity.EXTRA_COOKIE, cookie == null ? "" : cookie);
                    intent.putExtra(PlayerActivity.EXTRA_DEVICE_ID, deviceId());
                    startActivity(intent);
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void openServerSettings() {
            runOnUiThread(() -> showSetup(""));
        }
    }

    private void sendRemoteKey(String key) {
        if (webView == null || webView.getVisibility() != View.VISIBLE) return;
        webView.evaluateJavascript("window.BlofyRemote && window.BlofyRemote.key(" + JSONObject.quote(key) + ")", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && setupView == null) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: sendRemoteKey("ArrowUp"); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: sendRemoteKey("ArrowDown"); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: sendRemoteKey("ArrowLeft"); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: sendRemoteKey("ArrowRight"); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: sendRemoteKey("Enter"); return true;
                case KeyEvent.KEYCODE_BACK: sendRemoteKey("Back"); return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (setupView != null) {
            String saved = normalizedBaseUrl(getPreferences().getString(KEY_BASE_URL, ""));
            if (saved != null) loadPortal(saved); else finish();
        } else sendRemoteKey("Back");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.evaluateJavascript("window.dispatchEvent(new Event('blofyresume'))", null);
        }
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("BlofyAndroid");
            webView.destroy();
        }
        super.onDestroy();
    }
}
