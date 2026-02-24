package com.example.ussdwebview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.Log;
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

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int REQUEST_CALL_PERMISSION = 1;
    private String pendingUSSDCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

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

    private class JSBridge {

        @JavascriptInterface
        public void runUssd(String code) {
            runOnUiThread(() -> executeUSSD(code));
        }

        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(() -> restartApp());
        }

        @JavascriptInterface
        public void setSystemBarsColor(String colorString) {
            runOnUiThread(() -> changeSystemBarsColor(colorString));
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

    private void changeSystemBarsColor(String colorString) {
        try {
            int color = Color.parseColor(colorString);
            Window window = getWindow();

            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.setStatusBarColor(color);
                window.setNavigationBarColor(color);
            }

        } catch (IllegalArgumentException e) {
            Log.e("SYSTEM_BAR", "Invalid color: " + colorString);
        }
    }

    private void executeUSSD(String code) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sendResultToWeb("USSD supported on Android 8.0+ only");
            return;
        }

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
        if (tm == null) {
            sendResultToWeb("Telephony service unavailable");
            return;
        }

        tm.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {

            @Override
            public void onReceiveUssdResponse(
                    TelephonyManager telephonyManager,
                    String request,
                    CharSequence response) {

                Log.d("USSD", "Success: " + response);
                sendResultToWeb(response.toString());
            }

            @Override
            public void onReceiveUssdResponseFailed(
                    TelephonyManager telephonyManager,
                    String request,
                    int failureCode) {

                Log.e("USSD", "Failed: " + failureCode);
                sendResultToWeb("USSD failed: " + failureCode);
            }

        }, new Handler(Looper.getMainLooper()));
    }

    private void sendResultToWeb(String message) {
        String safeMessage = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                    "showResult('" + safeMessage + "')",
                    null
                )
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CALL_PERMISSION &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            if (pendingUSSDCode != null) {
                executeUSSD(pendingUSSDCode);
                pendingUSSDCode = null;
            }

        } else {
            sendResultToWeb("Permission denied");
        }
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