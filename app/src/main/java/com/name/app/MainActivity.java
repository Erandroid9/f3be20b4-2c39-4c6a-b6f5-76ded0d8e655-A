package com.example.ussdwebview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    private static final int REQUEST_PERMISSIONS = 101;

    private String pendingUSSDCode;

    private TelephonyManager activeTelephonyManager;
    private boolean ussdSessionActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        setupWebView();

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setupWebView() {

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new JSBridge(), "AndroidUSSD");
    }

    private class JSBridge {

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e("OPEN_EXTERNAL", "Invalid URL: " + url);
                }
            });
        }

        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(() -> restartApp());
        }

        @JavascriptInterface
        public void setSystemBarsColor(String colorString) {
            runOnUiThread(() -> changeSystemBarsColor(colorString));
        }

        @JavascriptInterface
        public void getAvailableSims() {
            runOnUiThread(() -> sendAvailableSimsToWeb());
        }

        @JavascriptInterface
        public void runUssd(String code, int simSlot) {
            runOnUiThread(() -> executeUSSD(code, simSlot));
        }

        @JavascriptInterface
        public void sendUssdInput(String input) {
            runOnUiThread(() -> sendNextUssd(input));
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

        } catch (Exception e) {
            Log.e("SYSTEM_BAR", "Invalid color: " + colorString);
        }
    }


    private void sendAvailableSimsToWeb() {

        if (!hasPermissions()) {
            requestPermissions();
            return;
        }

        SubscriptionManager subscriptionManager =
                (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);

        if (subscriptionManager == null) {
            sendResultToWeb("Subscription service unavailable");
            return;
        }

        List<SubscriptionInfo> subscriptionList =
                subscriptionManager.getActiveSubscriptionInfoList();

        if (subscriptionList == null || subscriptionList.isEmpty()) {
            sendResultToWeb("No active SIM cards found");
            return;
        }

        StringBuilder simData = new StringBuilder("[");

        for (SubscriptionInfo info : subscriptionList) {
            simData.append("{")
                    .append("\"slot\":").append(info.getSimSlotIndex()).append(",")
                    .append("\"name\":\"").append(info.getDisplayName()).append("\"")
                    .append("},");
        }

        simData.deleteCharAt(simData.length() - 1);
        simData.append("]");

        webView.post(() ->
                webView.evaluateJavascript(
                        "loadSimCards(" + simData + ")",
                        null
                )
        );
    }

    private void executeUSSD(String code, int simSlot) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sendResultToWeb("USSD supported on Android 8.0+ only");
            return;
        }

        if (!hasPermissions()) {
            pendingUSSDCode = code + "|" + simSlot;
            requestPermissions();
            return;
        }

        SubscriptionManager subscriptionManager =
                (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);

        if (subscriptionManager == null) {
            sendResultToWeb("Subscription service unavailable");
            return;
        }

        List<SubscriptionInfo> subscriptionList =
                subscriptionManager.getActiveSubscriptionInfoList();

        if (subscriptionList == null || subscriptionList.isEmpty()) {
            sendResultToWeb("No active SIM cards");
            return;
        }

        int subscriptionId = -1;

        for (SubscriptionInfo info : subscriptionList) {
            if (info.getSimSlotIndex() == simSlot) {
                subscriptionId = info.getSubscriptionId();
                break;
            }
        }

        if (subscriptionId == -1) {
            sendResultToWeb("Selected SIM not available");
            return;
        }

        TelephonyManager telephonyManager =
                ((TelephonyManager) getSystemService(TELEPHONY_SERVICE))
                        .createForSubscriptionId(subscriptionId);

        activeTelephonyManager = telephonyManager;
        ussdSessionActive = true;

        telephonyManager.sendUssdRequest(code,
                new TelephonyManager.UssdResponseCallback() {

                    @Override
                    public void onReceiveUssdResponse(
                            TelephonyManager telephonyManager,
                            String request,
                            CharSequence response) {

                        sendUssdMenuToWeb(response.toString());
                    }

                    @Override
                    public void onReceiveUssdResponseFailed(
                            TelephonyManager telephonyManager,
                            String request,
                            int failureCode) {

                        ussdSessionActive = false;
                        sendResultToWeb("USSD failed: " + failureCode);
                    }

                }, new Handler(Looper.getMainLooper()));
    }

    private void sendNextUssd(String input) {

        if (!ussdSessionActive || activeTelephonyManager == null) {
            sendResultToWeb("No active USSD session");
            return;
        }

        activeTelephonyManager.sendUssdRequest(input,
                new TelephonyManager.UssdResponseCallback() {

                    @Override
                    public void onReceiveUssdResponse(
                            TelephonyManager telephonyManager,
                            String request,
                            CharSequence response) {

                        sendUssdMenuToWeb(response.toString());
                    }

                    @Override
                    public void onReceiveUssdResponseFailed(
                            TelephonyManager telephonyManager,
                            String request,
                            int failureCode) {

                        ussdSessionActive = false;
                        sendResultToWeb("Session ended");
                    }

                }, new Handler(Looper.getMainLooper()));
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE
                },
                REQUEST_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {

            boolean allGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted && pendingUSSDCode != null) {

                String[] parts = pendingUSSDCode.split("\\|");
                String code = parts[0];
                int simSlot = Integer.parseInt(parts[1]);

                pendingUSSDCode = null;
                executeUSSD(code, simSlot);

            } else if (!allGranted) {
                sendResultToWeb("Permission denied");
            }
        }
    }

    private void sendUssdMenuToWeb(String message) {

        String safe = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                        "showUssdMenu('" + safe + "')",
                        null
                )
        );
    }

    private void sendResultToWeb(String message) {

        String safe = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                        "showResult('" + safe + "')",
                        null
                )
        );
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