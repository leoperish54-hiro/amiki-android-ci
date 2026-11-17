package com.amiki.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.SafeBrowsingResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Limited live web browser. Runs independently from the chat Activity. */
public class BrowserActivity extends Activity {
    private WebView webView;
    private EditText address;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(5, 7, 18));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackgroundColor(Color.rgb(12, 15, 30));

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(22);
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());

        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("URL or search");
        address.setTextColor(Color.rgb(245, 245, 250));
        address.setHintTextColor(Color.rgb(165, 165, 185));
        address.setTextSize(14);
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setOnEditorActionListener((v, actionId, event) -> {
            loadTarget(address.getText().toString());
            return true;
        });

        Button go = new Button(this);
        go.setText("GO");
        go.setAllCaps(false);
        go.setOnClickListener(v -> loadTarget(address.getText().toString()));

        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1f));
        bar.addView(go, new LinearLayout.LayoutParams(dp(64), dp(48)));
        root.addView(bar);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isWebUrl(request != null ? request.getUrl().toString() : null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isWebUrl(url);
            }

            @Override
            public void onSafeBrowsingHit(
                    WebView view,
                    WebResourceRequest request,
                    int threatType,
                    SafeBrowsingResponse callback
            ) {
                if (callback != null) {
                    callback.backToSafety(true);
                }
                Toast.makeText(
                        BrowserActivity.this,
                        "Unsafe page blocked.",
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                if (request != null && request.isForMainFrame()) {
                    Toast.makeText(
                            BrowserActivity.this,
                            "Page could not be loaded.",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (address != null && isWebUrl(url)) {
                    address.setText(url);
                }
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        loadTarget("https://www.google.com");
    }

    private void loadTarget(String raw) {
        String target = raw == null ? "" : raw.trim();
        if (target.isEmpty()) return;

        String url = target;
        if (!target.startsWith("https://") && !target.startsWith("http://")) {
            if (target.contains(" ")) {
                try {
                    url = "https://www.google.com/search?q=" +
                            URLEncoder.encode(target, StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {
                    return;
                }
            } else {
                url = "https://" + target;
            }
        }

        if (url.startsWith("http://")) {
            url = "https://" + url.substring("http://".length());
        }

        if (!isWebUrl(url)) {
            return;
        }
        webView.loadUrl(url);
    }

    private boolean isWebUrl(String url) {
        if (url == null) return false;
        return url.startsWith("https://");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
