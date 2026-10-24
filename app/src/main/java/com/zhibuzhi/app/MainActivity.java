package com.zhibuzhi.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 整个 app 就是一个全屏 WebView，界面在 assets/index.html。
 * 这里负责：保存账本、保存 API key、调用 AI 接口（Claude / GPT / 其他兼容 OpenAI 的服务）、选文件、导出备份、付款通知。
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_EXPORT = 2;
    private static final int REQ_NOTIFY = 3;
    // 三种服务商：claude（Anthropic）、openai（GPT）、custom（其他兼容 OpenAI 格式的服务）
    private static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
    private static final String CLAUDE_VERSION = "2023-06-01";
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DATA_FILE = "data.json";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String exportPayload;
    private String pendingPayId;
    private boolean pageReady;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        migrateKey();

        web = new WebView(this);
        web.setBackgroundColor(isNight() ? 0xFF0B0D10 : 0xFFF2F3F5);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverPayment();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "选择文件"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });

        handleIntent(getIntent());
        web.loadUrl("file:///android_asset/index.html");
    }

    // ---------- 系统栏颜色跟随深浅色 ----------

    private boolean isNight() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void applySystemBars() {
        boolean night = isNight();
        getWindow().setStatusBarColor(night ? 0xFF0B0D10 : 0xFFF2F3F5);
        getWindow().setNavigationBarColor(night ? 0xFF16191E : 0xFFFFFFFF);
        int flags = 0;
        if (!night) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("payId")) {
            pendingPayId = intent.getStringExtra("payId");
            intent.removeExtra("payId");
            deliverPayment();
        }
    }

    /** 用户点了“刚付了 ¥xx”的通知：让页面打开对应的记账弹层 */
    private void deliverPayment() {
        if (!pageReady || pendingPayId == null) return;
        String id = pendingPayId;
        pendingPayId = null;
        js("window.__onPayment&&window.__onPayment(" + JSONObject.quote(id) + ")");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) js("window.__onResume&&window.__onResume()");
    }

    @Override
    protected void onPause() {
        if (pageReady) js("window.__flush&&window.__flush()");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        pool.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (!pageReady) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__onBack&&window.__onBack())?'1':'0'", value -> {
            if (!"\"1\"".equals(value)) finish();
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        } else if (requestCode == REQ_EXPORT) {
            final String payload = exportPayload;
            exportPayload = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || payload == null) return;
            final Uri uri = data.getData();
            pool.execute(() -> {
                boolean ok = false;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(payload.getBytes(StandardCharsets.UTF_8));
                        ok = true;
                    }
                } catch (Exception ignored) {
                }
                final String msg = ok ? "备份已保存" : "保存失败，再试一次";
                runOnUiThread(() -> js("window.__toast&&window.__toast(" + JSONObject.quote(msg) + ")"));
            });
        }
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("app", MODE_PRIVATE);
    }

    private static String normProvider(String p) {
        return "openai".equals(p) || "custom".equals(p) ? p : "claude";
    }

    private static String keyName(String provider) {
        return "key_" + normProvider(provider);
    }

    /** 旧版本只存了一个 Claude 的 key，挪到新位置 */
    private void migrateKey() {
        SharedPreferences p = prefs();
        String old = p.getString("key", "");
        if (!old.isEmpty() && p.getString("key_claude", "").isEmpty()) {
            p.edit().putString("key_claude", old).remove("key").apply();
        }
    }

    private String endpoint(String provider) {
        if ("openai".equals(provider)) return OPENAI_URL;
        if ("custom".equals(provider)) {
            String base = prefs().getString("customUrl", "").trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (base.isEmpty()) return "";
            return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        }
        return CLAUDE_URL;
    }

    // ---------- 文件读写 ----------

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private String readData() {
        File f = new File(getFilesDir(), DATA_FILE);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            return "";
        }
    }

    private synchronized boolean writeData(String json) {
        File dir = getFilesDir();
        File tmp = new File(dir, DATA_FILE + ".tmp");
        File dst = new File(dir, DATA_FILE);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            return false;
        }
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return tmp.renameTo(dst);
        }
        return true;
    }

    // ---------- 调用 AI 接口 ----------

    private void callApi(String id, String providerIn, String body) {
        String provider = normProvider(providerIn);
        String key = prefs().getString(keyName(provider), "");
        boolean ok = false;
        String payload;
        HttpURLConnection conn = null;
        try {
            if (key.isEmpty()) throw new IllegalStateException("nokey");
            String url = endpoint(provider);
            if (url.isEmpty()) throw new IllegalStateException("nourl");
            if (!url.startsWith("https://")) throw new IllegalStateException("nohttps");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json");
            if ("claude".equals(provider)) {
                conn.setRequestProperty("x-api-key", key);
                conn.setRequestProperty("anthropic-version", CLAUDE_VERSION);
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }
            int code = conn.getResponseCode();
            boolean success = code >= 200 && code < 300;
            InputStream is = success ? conn.getInputStream() : conn.getErrorStream();
            String resp = "";
            if (is != null) {
                try (InputStream in = is) {
                    resp = readAll(in);
                }
            }
            if (success) {
                ok = true;
                payload = resp;
            } else {
                JSONObject err = new JSONObject();
                err.put("status", code);
                err.put("body", resp);
                payload = err.toString();
            }
        } catch (Exception e) {
            ok = false;
            JSONObject err = new JSONObject();
            try {
                err.put("status", 0);
                err.put("message", String.valueOf(e.getMessage()));
            } catch (JSONException ignored) {
            }
            payload = err.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
        final String code = "window.__onAI&&window.__onAI(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(payload) + ")";
        runOnUiThread(() -> js(code));
    }

    // ---------- 给页面用的接口：window.Android ----------

    public class Bridge {

        @JavascriptInterface
        public String load() {
            return readData();
        }

        @JavascriptInterface
        public boolean save(String json) {
            return json != null && writeData(json);
        }

        @JavascriptInterface
        public boolean hasKey(String provider) {
            return !prefs().getString(keyName(provider), "").isEmpty();
        }

        @JavascriptInterface
        public String keyHint(String provider) {
            String k = prefs().getString(keyName(provider), "");
            if (k.length() <= 12) return k.isEmpty() ? "" : "已保存";
            return k.substring(0, 6) + "…" + k.substring(k.length() - 4);
        }

        @JavascriptInterface
        public void setKey(String provider, String key) {
            prefs().edit().putString(keyName(provider), key == null ? "" : key.trim()).apply();
        }

        @JavascriptInterface
        public String getCustomUrl() {
            return prefs().getString("customUrl", "");
        }

        @JavascriptInterface
        public void setCustomUrl(String url) {
            prefs().edit().putString("customUrl", url == null ? "" : url.trim()).apply();
        }

        @JavascriptInterface
        public void ask(final String id, final String provider, final String body) {
            pool.execute(() -> callApi(id, provider, body));
        }

        @JavascriptInterface
        public String getPending() {
            return getSharedPreferences(PayListener.PREFS, MODE_PRIVATE).getString("pending", "[]");
        }

        @JavascriptInterface
        public void removePending(String id) {
            synchronized (PayListener.LOCK) {
                SharedPreferences sp = getSharedPreferences(PayListener.PREFS, MODE_PRIVATE);
                try {
                    JSONArray in = new JSONArray(sp.getString("pending", "[]"));
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < in.length(); i++) {
                        JSONObject o = in.getJSONObject(i);
                        if (!o.optString("id").equals(id)) out.put(o);
                    }
                    sp.edit().putString("pending", out.toString()).apply();
                } catch (JSONException ignored) {
                }
            }
        }

        @JavascriptInterface
        public boolean listenerOn() {
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (flat == null) return false;
            ComponentName cn = new ComponentName(MainActivity.this, PayListener.class);
            return flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString());
        }

        @JavascriptInterface
        public void openListenerSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }

        /** Android 13 起，发通知要先征得同意；只问一次 */
        @JavascriptInterface
        public void askNotify() {
            if (Build.VERSION.SDK_INT < 33) return;
            if (prefs().getBoolean("askedNotify", false)) return;
            runOnUiThread(() -> {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    prefs().edit().putBoolean("askedNotify", true).apply();
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
                }
            });
        }

        @JavascriptInterface
        public void exportData(final String fileName, final String json) {
            exportPayload = json;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                try {
                    startActivityForResult(i, REQ_EXPORT);
                } catch (ActivityNotFoundException e) {
                    exportPayload = null;
                    js("window.__toast&&window.__toast('这台手机不支持导出文件')");
                }
            });
        }
    }
}
