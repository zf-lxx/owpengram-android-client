package org.telegram.owpengram.localweb;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.Theme;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/**
 * Full-screen in-app window that loads the bundled admin page from
 * {@link WebViewAssetLoader} (no loopback server, no public HTTPS origin).
 * JavaScript can only talk to {@link LocalBotChannel} through the bridge.
 */
public class LocalBotWebViewSheet extends Dialog {

    private final int currentAccount;
    private final long dialogId;
    private final TLRPC.User bot;
    private final Theme.ResourcesProvider resourcesProvider;
    private final HashSet<String> pendingIds = new HashSet<>();

    private ActionBar actionBar;
    private WebView webView;
    private WebViewAssetLoader assetLoader;

    public LocalBotWebViewSheet(@NonNull Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, TLRPC.User bot, long dialogId) {
        super(context, R.style.TransparentDialog);
        this.resourcesProvider = resourcesProvider;
        this.currentAccount = currentAccount;
        this.bot = bot;
        this.dialogId = dialogId;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(false);
        buildContent(context);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void buildContent(Context context) {
        int bg = getThemedColor(Theme.key_windowBackgroundWhite);
        int actionBarColor = getThemedColor(Theme.key_actionBarDefault);
        int actionBarTitle = getThemedColor(Theme.key_actionBarDefaultTitle);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        int topInset = AndroidUtilities.statusBarHeight;
        root.setPadding(0, topInset, 0, 0);

        actionBar = new ActionBar(context, resourcesProvider);
        actionBar.setBackButtonDrawable(new BackDrawable(true));
        actionBar.setOccupyStatusBar(false);
        actionBar.setBackgroundColor(actionBarColor);
        actionBar.setTitleColor(actionBarTitle);
        actionBar.setItemsColor(actionBarTitle, false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_actionBarDefaultSelector), false);
        String title = LocaleController.getString(R.string.LocalBotWebViewTitle);
        if (TextUtils.isEmpty(title)) {
            title = "设置";
        }
        actionBar.setTitle(title);
        String subtitle = LocaleController.getString(R.string.LocalBotWebViewSubtitle);
        if (TextUtils.isEmpty(subtitle)) {
            subtitle = "本地打开，不走公网";
        }
        if (bot != null) {
            String name = UserObject.getPublicUsername(bot);
            if (TextUtils.isEmpty(name)) {
                name = UserObject.getUserName(bot);
            }
            if (!TextUtils.isEmpty(name)) {
                subtitle = name + " · " + subtitle;
            }
        }
        actionBar.setSubtitle(subtitle);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    dismiss();
                }
            }
        });
        root.addView(actionBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ActionBar.getCurrentActionBarHeight()));

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(LocalBotProtocol.VIRTUAL_HOST)
                .addPathHandler("/admin/", new WebViewAssetLoader.AssetsPathHandler(context, LocalBotProtocol.ASSETS_DIR))
                .build();

        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setDatabaseEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= 26) {
            settings.setSafeBrowsingEnabled(true);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url != null && assetLoader != null) {
                    WebResourceResponse response = assetLoader.shouldInterceptRequest(url);
                    if (response != null) {
                        return response;
                    }
                }
                return blockedResponse();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url != null && assetLoader != null) {
                    WebResourceResponse response = assetLoader.shouldInterceptRequest(Uri.parse(url));
                    if (response != null) {
                        return response;
                    }
                }
                return blockedResponse();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAllowedUrl(request != null ? request.getUrl() : null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isAllowedUrl(url == null ? null : Uri.parse(url));
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "OwpengramNative");
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setBackgroundColor(bg);
        wrapper.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP));
        setContentView(wrapper, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView.loadUrl(LocalBotProtocol.VIRTUAL_ADMIN_URL);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.TOP | Gravity.FILL;
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        if (Build.VERSION.SDK_INT >= 21) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(getThemedColor(Theme.key_actionBarDefault));
            window.setNavigationBarColor(getThemedColor(Theme.key_windowBackgroundWhite));
        }
        window.setAttributes(params);
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(params);
        }
    }

    @Override
    public void dismiss() {
        for (String id : pendingIds) {
            LocalBotChannel.get(currentAccount).cancel(id);
        }
        pendingIds.clear();
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("OwpengramNative");
                webView.setWebViewClient(new WebViewClient());
                webView.loadUrl("about:blank");
                webView.stopLoading();
                webView.destroy();
            } catch (Exception e) {
                FileLog.e(e);
            }
            webView = null;
        }
        super.dismiss();
    }

    private boolean isAllowedUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return "https".equalsIgnoreCase(scheme) && LocalBotProtocol.VIRTUAL_HOST.equalsIgnoreCase(host);
    }

    private WebResourceResponse blockedResponse() {
        byte[] body = "blocked".getBytes(StandardCharsets.UTF_8);
        WebResourceResponse response = new WebResourceResponse(
                "text/plain",
                "utf-8",
                new ByteArrayInputStream(body)
        );
        if (Build.VERSION.SDK_INT >= 21) {
            response.setStatusCodeAndReasonPhrase(403, "Forbidden");
        }
        return response;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private static boolean TextUtils.isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    private void resolveJs(String requestId, int status, String bodyJson) {
        if (webView == null) {
            return;
        }
        String js = "window.__owpengramLocal&&window.__owpengramLocal.resolve("
                + JSONObject.quote(requestId) + "," + status + "," + JSONObject.quote(bodyJson == null ? "" : bodyJson) + ");";
        webView.evaluateJavascript(js, null);
    }

    private void rejectJs(String requestId, String message) {
        if (webView == null) {
            return;
        }
        String js = "window.__owpengramLocal&&window.__owpengramLocal.reject("
                + JSONObject.quote(requestId) + "," + JSONObject.quote(message == null ? "请求失败" : message) + ");";
        webView.evaluateJavascript(js, null);
    }

    public final class NativeBridge {
        @JavascriptInterface
        public void request(String requestId, String method, String path, String body) {
            if (TextUtils.isEmpty(requestId) || TextUtils.isEmpty(path)) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> pendingIds.add(requestId));
            LocalBotChannel.get(currentAccount).sendRequest(dialogId, requestId, method, path, body, new LocalBotChannel.RequestListener() {
                @Override
                public void onResult(String id, int status, String bodyJson) {
                    AndroidUtilities.runOnUIThread(() -> {
                        pendingIds.remove(id);
                        resolveJs(id, status, bodyJson);
                    });
                }

                @Override
                public void onError(String id, String message) {
                    AndroidUtilities.runOnUIThread(() -> {
                        pendingIds.remove(id);
                        rejectJs(id, message);
                    });
                }
            });
        }

        @JavascriptInterface
        public String theme() {
            return Theme.isCurrentThemeDark() ? "dark" : "light";
        }

        @JavascriptInterface
        public void close() {
            AndroidUtilities.runOnUIThread(LocalBotWebViewSheet.this::dismiss);
        }

        @JavascriptInterface
        public void haptic(String type) {
            AndroidUtilities.runOnUIThread(() -> {
                if (webView != null) {
                    webView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                }
            });
        }
    }
}
