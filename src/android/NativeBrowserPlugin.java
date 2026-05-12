package com.titanLauncher.nativebrowser;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.widget.Toast;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.content.Context;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.webkit.DownloadListener;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NativeBrowserPlugin extends CordovaPlugin {

    // ── State ────────────────────────────────────────────────────────────────
    private FrameLayout            overlayContainer;
    private CallbackContext        eventCallback;
    private int                    tabCounter  = 0;
    private String                 activeTabId = null;
    private final Map<String, TabState> tabs   = new HashMap<>();
    private final List<String>     tabOrder    = new ArrayList<>();

    // ── Download tracking ────────────────────────────────────────────────────
    // Maps DownloadManager download ID → original URL
    private final ConcurrentHashMap<Long, String> activeDownloads = new ConcurrentHashMap<>();
    private android.app.DownloadManager downloadManager;
    private BroadcastReceiver downloadCompleteReceiver;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressPoller;

    // ── Tab state ────────────────────────────────────────────────────────────
    private static class TabState {
        String  id;
        String  url;
        WebView webView;
        boolean canGoBack;
        boolean canGoForward;
        boolean hadError;   // ← add this

        TabState(String id, String url, WebView wv) {
            this.id      = id;
            this.url     = url;
            this.webView = wv;
        }
    }

    // ── Plugin action dispatcher ─────────────────────────────────────────────
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
            throws JSONException {

        switch (action) {
            case "open":
                open(args, callbackContext);
                return true;
            case "navigate":
                navigate(args.getString(0), callbackContext);
                return true;
            case "goBack":
                goBack(callbackContext);
                return true;
            case "goForward":
                goForward(callbackContext);
                return true;
            case "reload":
                reload(callbackContext);
                return true;
            case "setRect":
                setRect(args, callbackContext);
                return true;
            case "show":
                setVisibility(View.VISIBLE, callbackContext);
                return true;
            case "hide":
                setVisibility(View.GONE, callbackContext);
                return true;
            case "close":
                close(callbackContext);
                return true;
            case "newTab":
                newTab(args.getString(0), callbackContext);
                return true;
            case "switchTab":
                switchTab(args.getString(0), callbackContext);
                return true;
            case "closeTab":
                closeTab(args.getString(0), callbackContext);
                return true;
        }
        return false;
    }

    // ── open ─────────────────────────────────────────────────────────────────
    private void open(JSONArray args, CallbackContext callbackContext) throws JSONException {
        String url    = args.getString(0);
        int    x      = args.getInt(1);
        int    y      = args.getInt(2);
        int    width  = args.getInt(3);
        int    height = args.getInt(4);

        eventCallback = callbackContext;
PluginResult pluginResult =
        new PluginResult(PluginResult.Status.NO_RESULT);

pluginResult.setKeepCallback(true);

callbackContext.sendPluginResult(pluginResult);
        cordova.getActivity().runOnUiThread(() -> {
            if (overlayContainer != null) {
                // Already open — just navigate & reposition
                positionOverlay(x, y, width, height);
                if (activeTabId != null) {
                    tabs.get(activeTabId).webView.loadUrl(url);
                }
                return;
            }

            // Create the transparent container that sits above CordovaWebView
            overlayContainer = new FrameLayout(cordova.getActivity());
            overlayContainer.setBackgroundColor(Color.WHITE);

            ViewGroup root = (ViewGroup) cordova.getActivity()
                    .getWindow().getDecorView().getRootView();
            root.addView(overlayContainer);

            positionOverlay(x, y, width, height);
            createTab(url, true);
        });
    }

    // ── navigate ─────────────────────────────────────────────────────────────
    private void navigate(String url, CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            TabState t = activeTab();
            if (t == null) { cb.error("No active tab"); return; }
            t.webView.loadUrl(normalizeUrl(url));
            cb.success();
        });
    }

    // ── goBack ───────────────────────────────────────────────────────────────
    private void goBack(CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            TabState t = activeTab();
            if (t != null && t.webView.canGoBack()) t.webView.goBack();
            cb.success();
        });
    }

    // ── goForward ────────────────────────────────────────────────────────────
    private void goForward(CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            TabState t = activeTab();
            if (t != null && t.webView.canGoForward()) t.webView.goForward();
            cb.success();
        });
    }

    // ── reload ───────────────────────────────────────────────────────────────
    private void reload(CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            TabState t = activeTab();
            if (t != null) t.webView.reload();
            cb.success();
        });
    }

    // ── setRect ──────────────────────────────────────────────────────────────
    private void setRect(JSONArray args, CallbackContext cb) throws JSONException {
        int x = args.getInt(0), y = args.getInt(1),
            w = args.getInt(2), h = args.getInt(3);
        cordova.getActivity().runOnUiThread(() -> {
            positionOverlay(x, y, w, h);
            cb.success();
        });
    }

    // ── show / hide ──────────────────────────────────────────────────────────
    private void setVisibility(int vis, CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            if (overlayContainer != null) overlayContainer.setVisibility(vis);
            cb.success();
        });
    }

    // ── close ────────────────────────────────────────────────────────────────
    private void close(CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            if (overlayContainer == null) { cb.success(); return; }

            for (TabState t : tabs.values()) {
                t.webView.stopLoading();
                t.webView.destroy();
            }
            tabs.clear();
            tabOrder.clear();
            activeTabId = null;

            stopDownloadTracking();
            activeDownloads.clear();

            ViewGroup root = (ViewGroup) cordova.getActivity()
                    .getWindow().getDecorView().getRootView();
            root.removeView(overlayContainer);
            overlayContainer = null;
            eventCallback    = null;
            cb.success();
        });
    }

    // ── newTab ───────────────────────────────────────────────────────────────
    private void newTab(String url, CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            if (overlayContainer == null) { cb.error("Browser not open"); return; }
            createTab(url, true);
            cb.success();
        });
    }

    // ── switchTab ────────────────────────────────────────────────────────────
    private void switchTab(String tabId, CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            activateTab(tabId);
            cb.success();
        });
    }

    // ── closeTab ─────────────────────────────────────────────────────────────
    private void closeTab(String tabId, CallbackContext cb) {
        cordova.getActivity().runOnUiThread(() -> {
            destroyTab(tabId);
            cb.success();
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TabState activeTab() {
        return activeTabId != null ? tabs.get(activeTabId) : null;
    }

    private void positionOverlay(int x, int y, int width, int height) {
        if (overlayContainer == null) return;
        float density = cordova.getActivity().getResources()
                .getDisplayMetrics().density;

        int px = dpToPx(x, density);
        int py = dpToPx(y, density);
        int pw = dpToPx(width,  density);
        int ph = dpToPx(height, density);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pw, ph);
        lp.leftMargin = px;
        lp.topMargin  = py;
        overlayContainer.setLayoutParams(lp);
    }

    private int dpToPx(int dp, float density) {
        return Math.round(dp * density);
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) return "https://www.google.com";
        if (!url.contains(".") && !url.startsWith("http")) {
            return "https://www.google.com/search?q=" + url.replace(" ", "+");
        }
        if (!url.startsWith("http")) return "https://" + url;
        return url;
    }

    // ── Create a tab & its WebView ────────────────────────────────────────────
    private void createTab(String url, boolean activate) {
        String tabId = "tab-" + (++tabCounter);
        String resolved = normalizeUrl(url);

        WebView wv = buildWebView(tabId);
        wv.loadUrl(resolved);

        TabState state = new TabState(tabId, resolved, wv);
        tabs.put(tabId, state);
        tabOrder.add(tabId);

        overlayContainer.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        wv.setVisibility(View.GONE);

        if (activate) activateTab(tabId);

        sendEvent("tabCreated", tabId, resolved, null, null);
    }

    // ── Activate a tab ───────────────────────────────────────────────────────
    private void activateTab(String tabId) {
        // Hide current
        if (activeTabId != null && tabs.containsKey(activeTabId)) {
            tabs.get(activeTabId).webView.setVisibility(View.GONE);
        }

        activeTabId = tabId;
        TabState t = tabs.get(tabId);
        if (t != null) {
            t.webView.setVisibility(View.VISIBLE);
            sendEvent("tabActivated", tabId, t.url, null, null);
        }
    }

    // ── Destroy a tab ────────────────────────────────────────────────────────
    private void destroyTab(String tabId) {
        TabState t = tabs.remove(tabId);
        tabOrder.remove(tabId);

        if (t != null) {
            overlayContainer.removeView(t.webView);
            t.webView.stopLoading();
            t.webView.destroy();
        }

        if (tabId.equals(activeTabId)) {
            activeTabId = null;
            if (!tabOrder.isEmpty()) {
                activateTab(tabOrder.get(tabOrder.size() - 1));
            } else {
                // No tabs left — open Google
                createTab("https://www.google.com", true);
            }
        }

        sendEvent("tabClosed", tabId, null, null, null);
    }

    // ── Build a configured WebView ────────────────────────────────────────────
    private WebView buildWebView(String tabId) {
        Context ctx = cordova.getActivity();
        WebView wv  = new WebView(ctx);

        wv.setLongClickable(true);
        wv.setHapticFeedbackEnabled(true);
        wv.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = wv.getHitTestResult();
            if (hit == null) return false;
            int    type  = hit.getType();
            String extra = hit.getExtra();
            if (extra == null) return false;

            cordova.getActivity().runOnUiThread(() -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(ctx);

                if (type == WebView.HitTestResult.IMAGE_TYPE
                        || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {

                    builder.setTitle("Image");
                    builder.setItems(new String[]{ "Open in new tab", "Save image", "Copy image URL" },
                        (dialog, which) -> {
                            switch (which) {
                               case 0:
    sendEvent("newTabRequested", tabId, extra, null, null);
    break;
                                case 1:
                                    String mime = guessMimeFromUrl(extra);
                                    String filename = guessImageFilename(extra, mime);
                                    android.app.DownloadManager.Request req =
                                            new android.app.DownloadManager.Request(Uri.parse(extra));
                                    req.setMimeType(mime);
                                    req.setNotificationVisibility(
                                            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                    req.setDestinationInExternalPublicDir(
                                            android.os.Environment.DIRECTORY_PICTURES, filename);
                                    String cookies = android.webkit.CookieManager.getInstance().getCookie(extra);
                                    if (cookies != null) req.addRequestHeader("Cookie", cookies);
                                    android.app.DownloadManager dm = (android.app.DownloadManager)
                                            ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                                    long dlId = dm.enqueue(req);
                                    activeDownloads.put(dlId, extra);
                                    boolean wasIdle = (downloadManager == null);
                                    downloadManager = dm;
                                    if (wasIdle) { registerDownloadReceiver(); startProgressPoller(); }
                                    sendDownloadEvent("downloadStarted", extra, mime, null, 0, 0, filename);
                                    break;
                                case 2:
                                    ClipboardManager cm = (ClipboardManager)
                                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(ClipData.newPlainText("Image URL", extra));
                                    Toast.makeText(ctx, "URL copied", Toast.LENGTH_SHORT).show();
                                    break;
                            }
                        });

                } else if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {

                    builder.setTitle(extra);
                    builder.setItems(new String[]{ "Open in new tab", "Copy link URL" },
                        (dialog, which) -> {
                            switch (which) {
                               case 0:
    sendEvent("newTabRequested", tabId, extra, null, null);
    break;
                                case 1:
                                    ClipboardManager cm = (ClipboardManager)
                                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(ClipData.newPlainText("Link URL", extra));
                                    Toast.makeText(ctx, "URL copied", Toast.LENGTH_SHORT).show();
                                    break;
                            }
                        });

                } else {
                    return;
                }

                builder.setNegativeButton("Cancel", null);
                builder.show();
            });
            return true;
        });

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
       s.setUserAgentString(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/124.0.0.0 Safari/537.36"
);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (tabId.equals(activeTabId)) {
                    sendEvent("pageStarted", tabId, url, null, null);
                }
            }

            @Override
          
public void onPageFinished(WebView view, String url) {
    TabState t = tabs.get(tabId);
    if (t != null) {
        t.url          = url;
        t.canGoBack    = view.canGoBack();
        t.canGoForward = view.canGoForward();

        if (t.hadError) {
            t.hadError = false;  // reset for next navigation
            return;              // don't tell JS — error overlay stays up
        }
    }
    if (tabId.equals(activeTabId)) {
        sendEvent("pageFinished", tabId, url, null, null);
        sendNavState(tabId);
    }
}
// Modern API (Android 6+) — preferred, gives full WebResourceError
@Override
public void onReceivedError(WebView view, WebResourceRequest request,
                            android.webkit.WebResourceError error) {
    // Only fire for the main frame, not sub-resources like ads/images
    if (!request.isForMainFrame()) return;

    String  failUrl = request.getUrl().toString();
    int     code    = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                      ? error.getErrorCode()
                      : -1;
    String  desc    = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                      ? (error.getDescription() != null ? error.getDescription().toString() : "")
                      : "";

    TabState t = tabs.get(tabId);
if (t != null) {
    t.url      = failUrl;
    t.hadError = true;   // ← add this line
}

    sendPageError(tabId, failUrl, code, desc);
}

// Legacy fallback (Android < 6) — still fires on all versions as secondary
@Override
public void onReceivedError(WebView view, int errorCode,
                            String description, String failingUrl) {
    // On API 23+ this fires for every sub-resource too, skip those
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) return;

    TabState t = tabs.get(tabId);
    if (t != null) t.url = failingUrl;

    sendPageError(tabId, failingUrl, errorCode, description);
}

// SSL errors — treated separately by Android; without this the page just hangs
@Override
public void onReceivedSslError(WebView view,
                               android.webkit.SslErrorHandler handler,
                               android.net.http.SslError error) {
    handler.cancel(); // don't proceed on bad cert
    String failUrl = error.getUrl() != null ? error.getUrl() : "";

    TabState t = tabs.get(tabId);
    if (t != null) t.url = failUrl;

    // errorCode -9 maps to the ssl branch in your JS
    sendPageError(tabId, failUrl, -9, "SSL_ERROR_" + error.getPrimaryError());
}
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Let the WebView handle it natively
                return false;
            }
        });

      wv.setWebChromeClient(new WebChromeClient() {

    private View customView;
    private CustomViewCallback customViewCallback;

    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {

        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }

        customView = view;
        customViewCallback = callback;

        ViewGroup root = (ViewGroup) cordova.getActivity()
                .getWindow()
                .getDecorView()
                .getRootView();

        root.addView(
                customView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        if (overlayContainer != null) {
            overlayContainer.setVisibility(View.GONE);
        }
    }

    @Override
    public void onHideCustomView() {

        if (customView == null) return;

        ViewGroup root = (ViewGroup) cordova.getActivity()
                .getWindow()
                .getDecorView()
                .getRootView();

        root.removeView(customView);

        customView = null;
        customViewCallback = null;

        if (overlayContainer != null) {
            overlayContainer.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onReceivedTitle(WebView view, String title) {

        TabState t = tabs.get(tabId);

        if (tabId.equals(activeTabId)) {
            sendEvent(
                    "titleChanged",
                    tabId,
                    t != null ? t.url : null,
                    title,
                    null
            );
        }
    }

    @Override
    public void onReceivedIcon(WebView view,
                               android.graphics.Bitmap icon) {
    }

    @Override
    public boolean onCreateWindow(WebView view,
                                  boolean isDialog,
                                  boolean isUserGesture,
                                  android.os.Message resultMsg) {

        WebView tempView = new WebView(cordova.getActivity());

        tempView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView v,
                    WebResourceRequest req) {

                String newUrl = req.getUrl().toString();

                cordova.getActivity().runOnUiThread(() ->
                        sendEvent(
                                "newTabRequested",
                                tabId,
                                newUrl,
                                null,
                                null
                        )
                );

                return true;
            }
        });

        WebView.WebViewTransport transport =
                (WebView.WebViewTransport) resultMsg.obj;

        transport.setWebView(tempView);

        resultMsg.sendToTarget();

        return true;
    }
});

wv.setDownloadListener(new DownloadListener() {

    @Override
    public void onDownloadStart(String url,
                                String userAgent,
                                String contentDisposition,
                                String mimeType,
                                long contentLength) {

        try {

            android.app.DownloadManager.Request request =
                    new android.app.DownloadManager.Request(
                            Uri.parse(url)
                    );

            request.setMimeType(mimeType);

            String cookies =
                    android.webkit.CookieManager
                            .getInstance()
                            .getCookie(url);

            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies);
            }

            request.addRequestHeader("User-Agent", userAgent);

            request.setDescription("Downloading file...");

            String filename =
                    android.webkit.URLUtil.guessFileName(
                            url,
                            contentDisposition,
                            mimeType
                    );

            filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

            request.setTitle(filename);

            request.allowScanningByMediaScanner();

            request.setNotificationVisibility(
                    android.app.DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );

            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    filename
            );

            android.app.DownloadManager dm =
                    (android.app.DownloadManager)
                            cordova.getActivity()
                                    .getSystemService(
                                            Context.DOWNLOAD_SERVICE
                                    );

            long downloadId = dm.enqueue(request);

            activeDownloads.put(downloadId, url);

            boolean wasIdle = (downloadManager == null);

            downloadManager = dm;

            if (wasIdle) {
                registerDownloadReceiver();
                startProgressPoller();
            }

            sendDownloadEvent(
                    "downloadStarted",
                    url,
                    mimeType,
                    contentDisposition,
                    contentLength,
                    0,
                    filename
            );

        } catch (Exception e) {

            sendDownloadEvent(
                    "downloadError",
                    url,
                    mimeType,
                    null,
                    0,
                    0,
                    e.toString()
            );
        }
    }
});

wv.getSettings().setSupportMultipleWindows(true);
        return wv;
    }

    // ── Register BroadcastReceiver for download completion ───────────────────
    private void registerDownloadReceiver() {
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(
                        android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (!activeDownloads.containsKey(id)) return;

                String url = activeDownloads.remove(id);

                android.app.DownloadManager.Query query =
                        new android.app.DownloadManager.Query();
                query.setFilterById(id);

                try (android.database.Cursor c = downloadManager.query(query)) {
                    if (c != null && c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndexOrThrow(
                                android.app.DownloadManager.COLUMN_STATUS));
                        String localUri = c.getString(c.getColumnIndexOrThrow(
                                android.app.DownloadManager.COLUMN_LOCAL_URI));
                        String mimeType = c.getString(c.getColumnIndexOrThrow(
                                android.app.DownloadManager.COLUMN_MEDIA_TYPE));
                        String reason   = c.getString(c.getColumnIndexOrThrow(
                                android.app.DownloadManager.COLUMN_REASON));

                        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                            sendDownloadEvent("downloadFinished", url,
                                    mimeType, null, 0, 100, localUri);
                        } else {
                            sendDownloadEvent("downloadError", url,
                                    mimeType, null, 0, 0, reason);
                        }
                    }
                } catch (Exception e) {
                    sendDownloadEvent("downloadError", url, null, null, 0, 0, e.toString());
                }

                if (activeDownloads.isEmpty()) stopDownloadTracking();
            }
        };

        IntentFilter filter = new IntentFilter(
                android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);

        // Android 8+ (API 26+) requires RECEIVER_EXPORTED for system broadcasts
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            cordova.getActivity().registerReceiver(
                    downloadCompleteReceiver, filter,
                    Context.RECEIVER_EXPORTED);
        } else {
            cordova.getActivity().registerReceiver(
                    downloadCompleteReceiver, filter);
        }
    }

    // ── Poll DownloadManager every second for progress ───────────────────────
    private void startProgressPoller() {
        progressPoller = new Runnable() {
            @Override
            public void run() {
                if (activeDownloads.isEmpty()) {
                    // nothing to poll right now, but keep scheduling
                    // in case a new download starts before stopDownloadTracking
                    progressHandler.postDelayed(this, 1000);
                    return;
                }

                android.app.DownloadManager.Query query =
                        new android.app.DownloadManager.Query();
                // Filter only our tracked IDs
                long[] ids = new long[activeDownloads.size()];
                int i = 0;
                for (Long id : activeDownloads.keySet()) ids[i++] = id;
                query.setFilterById(ids);

                try (android.database.Cursor c =
                             downloadManager != null ? downloadManager.query(query) : null) {
                    if (c != null) {
                        while (c.moveToNext()) {
                            int status = c.getInt(c.getColumnIndexOrThrow(
                                    android.app.DownloadManager.COLUMN_STATUS));
                            if (status != android.app.DownloadManager.STATUS_RUNNING) continue;

                            long dlId = c.getLong(c.getColumnIndexOrThrow(
                                    android.app.DownloadManager.COLUMN_ID));
                            String url = activeDownloads.get(dlId);
                            if (url == null) continue;

                            long downloaded = c.getLong(c.getColumnIndexOrThrow(
                                    android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            long total = c.getLong(c.getColumnIndexOrThrow(
                                    android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                            int progress = (total > 0)
                                    ? (int) (downloaded * 100L / total)
                                    : -1;

                            sendDownloadEvent("downloadProgress", url,
                                    null, null, total, progress, null);
                        }
                    }
                } catch (Exception e) {
                    // silently ignore poll errors
                }

                progressHandler.postDelayed(this, 1000);
            }
        };
        progressHandler.postDelayed(progressPoller, 1000);
    }

    // ── Clean up receiver + poller when no more downloads ────────────────────
    private void stopDownloadTracking() {
        progressHandler.removeCallbacks(progressPoller);
        if (downloadCompleteReceiver != null) {
            try {
                cordova.getActivity().unregisterReceiver(downloadCompleteReceiver);
            } catch (IllegalArgumentException ignored) {}
            downloadCompleteReceiver = null;
        }
        downloadManager = null;
    }

    // ── Guess mime type from URL extension ───────────────────────────────────
    private String guessMimeFromUrl(String url) {
        String lower = url.toLowerCase().split("\\?")[0]; // strip query params
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        if (lower.endsWith(".bmp"))  return "image/bmp";
        return "image/jpeg"; // safe default for images
    }

    // ── Build a sane filename for a saved image ───────────────────────────────
    private String guessImageFilename(String url, String mime) {
        // try to get a name from the URL path
        String path = url.split("\\?")[0];
        String name = path.substring(path.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\\\:*?\"<>|]", "_");

        // determine correct extension from mime
        String ext;
        switch (mime) {
            case "image/jpeg":    ext = ".jpg";  break;
            case "image/png":     ext = ".png";  break;
            case "image/gif":     ext = ".gif";  break;
            case "image/webp":    ext = ".webp"; break;
            case "image/svg+xml": ext = ".svg";  break;
            case "image/bmp":     ext = ".bmp";  break;
            default:              ext = ".jpg";  break;
        }

        // if name already has the right extension, keep it; otherwise fix it
        if (name.isEmpty() || name.equals("_")) {
            name = "image_" + System.currentTimeMillis();
        }
        // strip any wrong extension (.bin, .php, etc.) and apply correct one
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String existingExt = name.substring(dot).toLowerCase();
            boolean knownImage = existingExt.equals(".jpg") || existingExt.equals(".jpeg")
                    || existingExt.equals(".png") || existingExt.equals(".gif")
                    || existingExt.equals(".webp") || existingExt.equals(".svg")
                    || existingExt.equals(".bmp");
            if (!knownImage) name = name.substring(0, dot) + ext;
        } else {
            name = name + ext;
        }
        return name;
    }

    // ── Send event to JS ──────────────────────────────────────────────────────
    private void sendEvent(String type, String tabId, String url,
                           String title, String favicon) {
        if (eventCallback == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",    type);
            obj.put("tabId",   tabId  != null ? tabId   : JSONObject.NULL);
            obj.put("url",     url    != null ? url     : JSONObject.NULL);
            obj.put("title",   title  != null ? title   : JSONObject.NULL);
            obj.put("favicon", favicon != null ? favicon : JSONObject.NULL);

            PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
            result.setKeepCallback(true);
            eventCallback.sendPluginResult(result);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
private void sendDownloadEvent(String type,
                               String url,
                               String mimetype,
                               String disposition,
                               long contentLength,
                               int progress,
                               String path) {

    if (eventCallback == null) return;

    try {

        JSONObject obj = new JSONObject();

        obj.put("type", type);
        obj.put("url", url != null ? url : JSONObject.NULL);
        obj.put("mimetype", mimetype != null ? mimetype : JSONObject.NULL);
        obj.put("disposition", disposition != null ? disposition : JSONObject.NULL);
        obj.put("contentLength", contentLength);
        obj.put("progress", progress);
        obj.put("path", path != null ? path : JSONObject.NULL);

        PluginResult result =
                new PluginResult(PluginResult.Status.OK, obj);

        result.setKeepCallback(true);

        eventCallback.sendPluginResult(result);

    } catch (Exception e) {
        e.printStackTrace();
    }
}
    private void sendNavState(String tabId) {
        TabState t = tabs.get(tabId);
        if (t == null || eventCallback == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",       "navState");
            obj.put("tabId",      tabId);
            obj.put("canGoBack",  t.canGoBack);
            obj.put("canGoForward", t.canGoForward);

            PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
            result.setKeepCallback(true);
            eventCallback.sendPluginResult(result);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
private void sendPageError(String tabId, String url, int errorCode, String description) {
    if (eventCallback == null) return;
    try {
        JSONObject obj = new JSONObject();
        obj.put("type",        "pageError");
        obj.put("tabId",       tabId       != null ? tabId       : JSONObject.NULL);
        obj.put("url",         url         != null ? url         : JSONObject.NULL);
        obj.put("errorCode",   errorCode);
        obj.put("description", description != null ? description : JSONObject.NULL);

        PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
        result.setKeepCallback(true);
        eventCallback.sendPluginResult(result);
    } catch (JSONException e) {
        e.printStackTrace();
    }
}
    // ── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onDestroy() {
        cordova.getActivity().runOnUiThread(() -> {
            if (overlayContainer != null) {
                for (TabState t : tabs.values()) {
                    t.webView.stopLoading();
                    t.webView.destroy();
                }
                tabs.clear();
                tabOrder.clear();

                stopDownloadTracking();
                activeDownloads.clear();

                ViewGroup root = (ViewGroup) cordova.getActivity()
                        .getWindow().getDecorView().getRootView();
                root.removeView(overlayContainer);
                overlayContainer = null;
            }
        });
    }

    @Override
    public void onPause(boolean multitasking) {
        cordova.getActivity().runOnUiThread(() -> {
            for (TabState t : tabs.values()) t.webView.onPause();
        });
    }

    @Override
    public void onResume(boolean multitasking) {
        cordova.getActivity().runOnUiThread(() -> {
            for (TabState t : tabs.values()) t.webView.onResume();
        });
    }
}