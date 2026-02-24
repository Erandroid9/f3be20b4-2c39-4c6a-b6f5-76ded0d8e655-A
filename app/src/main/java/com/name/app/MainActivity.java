package com.example.ussdwebview;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int REQUEST_CALL_PERMISSION = 1;

    private static final String PREF_NAME = "APP_PREF";
    private static final String KEY_SYSTEM_COLOR = "SYSTEM_COLOR";

    private String pendingUSSDCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        applySavedColorBeforeLaunch(); 

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        webView.addJavascriptInterface(new JSBridge(), "AndroidUSSD");

        webView.loadUrl("file:///android_asset/index.html");
    }


    private void applySavedColorBeforeLaunch() {

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedColor = prefs.getString(KEY_SYSTEM_COLOR, null);

        if (savedColor == null) return;

        try {
            int color = Color.parseColor(savedColor);

            Window window = getWindow();
            window.setBackgroundDrawable(new ColorDrawable(color));
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(color);
            window.setNavigationBarColor(color);

            boolean isLight = isColorLight(color);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                WindowInsetsControllerCompat controller =
                        new WindowInsetsControllerCompat(window, window.getDecorView());

                controller.setAppearanceLightStatusBars(isLight);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    controller.setAppearanceLightNavigationBars(isLight);
                }
            }

        } catch (Exception ignored) {}
    }

    private void applySystemBarColor(String colorString) {
        try {
            int color = Color.parseColor(colorString);

            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(color);
            window.setNavigationBarColor(color);

            boolean isLight = isColorLight(color);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                WindowInsetsControllerCompat controller =
                        new WindowInsetsControllerCompat(window, window.getDecorView());

                controller.setAppearanceLightStatusBars(isLight);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    controller.setAppearanceLightNavigationBars(isLight);
                }
            }

            SharedPreferences.Editor editor =
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
            editor.putString(KEY_SYSTEM_COLOR, colorString);
            editor.apply();

        } catch (Exception ignored) {}
    }

    private boolean isColorLight(int color) {
        double darkness = 1 - (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255;
        return darkness < 0.5;
    }

    private class JSBridge {

        @JavascriptInterface
        public void setSystemBarsColor(String color) {
            runOnUiThread(() -> applySystemBarColor(color));
        }

        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(() -> restartApp());
        }

        @JavascriptInterface
        public void runUssd(String code) {
            runOnUiThread(() -> executeUSSD(code));
        }
    }

    private void restartApp() {
        Intent intent = getPackageManager()
            .getLaunchIntentForPackage(getPackageName());

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }

        finish();
    }

    private void executeUSSD(String code) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {

            pendingUSSDCode = code;

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_PHONE_STATE
                    },
                    REQUEST_CALL_PERMISSION
            );
            return;
        }

        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return;

        tm.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {

            @Override
            public void onReceiveUssdResponse(
                    TelephonyManager telephonyManager,
                    String request,
                    CharSequence response) {

                webView.evaluateJavascript(
                        "showResult('" + response.toString().replace("'", "\\'") + "')",
                        null
                );
            }

        }, new Handler(Looper.getMainLooper()));
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}