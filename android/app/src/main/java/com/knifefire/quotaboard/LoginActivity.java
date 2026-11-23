package com.knifefire.quotaboard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/**
 * 内嵌登录页：加载智谱/DeepSeek 登录地址，自动从请求头与 localStorage 抓取凭证。
 * 抓到后通过 setResult 回传给插件。
 */
public class LoginActivity extends Activity {
    public static final String EXTRA_PROVIDER = "provider";
    public static final String RESULT_TOKEN = "token";
    public static final String RESULT_AUTHMODE = "authMode";
    public static final String RESULT_ORG = "org";
    public static final String RESULT_PROJECT = "project";
    public static final String RESULT_PLANTYPE = "planType";

    private static final String TAG = "LoginActivity";
    private WebView web;
    private ProgressBar progress;
    private String provider;
    private volatile boolean finished = false;

    private static final String URL_GLM_CN = "https://bigmodel.cn/coding-plan";
    private static final String URL_GLM_GLOBAL = "https://z.ai/manage-apikey/coding-plan/personal/usage";
    // DeepSeek 查余额必须用 sk- API Key，网页登录态无效；直接引导到创建页，key 只显示一次
    private static final String URL_DEEPSEEK = "https://platform.deepseek.com/api_keys";
    private final Handler scanHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        provider = getIntent().getStringExtra(EXTRA_PROVIDER);
        if (provider == null) provider = "glm-cn";

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.WHITE);

        TextView hint = new TextView(this);
        boolean isDS = "deepseek".equals(provider);
        if (isDS) {
            hint.setText("登录后点「创建 API key」，App 会自动复制生成的 key 并回填");
        } else {
            hint.setText("登录 " + (provider.startsWith("glm-global") ? "智谱 GLM 国际站" : "智谱 GLM")
                    + " · 成功后自动读取凭证并返回");
        }
        hint.setTextColor(Color.rgb(60, 66, 87));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(12, 6, 12, 6);
        // 关键：固定高度(48dp)，不用 WRAP_CONTENT——真机上 WRAP_CONTENT 会被异常测量成
        // 占半个屏幕；同时必须用 LinearLayout.LayoutParams 以正确参与 LinearLayout 布局。
        int hintHeight = Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics()));
        root.addView(hint, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, hintHeight));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 6));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " QuotaBoard/1.0");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                inspectHeaders(request.getUrl().toString(), request.getRequestHeaders());
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setProgress(100);
                view.postDelayed(() -> scanLocalStorage(view), 1500);
                view.postDelayed(() -> scanLocalStorage(view), 4000);
                if ("deepseek".equals(provider)) startScanDeepSeekKey(view);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.w(TAG, "page error " + errorCode + " " + description);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(newProgress);
                } else {
                    progress.setVisibility(View.GONE);
                }
            }
        });

        android.widget.LinearLayout.LayoutParams webLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(web, webLp);
        setContentView(root);

        String url = URL_GLM_CN;
        if ("glm-global".equals(provider)) url = URL_GLM_GLOBAL;
        else if ("deepseek".equals(provider)) url = URL_DEEPSEEK;
        web.loadUrl(url);

        Toast.makeText(this, "登录成功后会自动返回；若未自动返回请按返回键", Toast.LENGTH_LONG).show();
    }

    /** 从网络请求头抓取凭证 */
    private void inspectHeaders(String url, Map<String, String> headers) {
        if (headers == null || finished) return;
        String auth = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("authorization".equalsIgnoreCase(e.getKey())) auth = e.getValue();
        }
        if (TextUtils.isEmpty(auth)) return;

        boolean isQuota = url.contains("/api/monitor/usage/quota/limit");
        boolean isBalance = url.contains("/user/balance");
        boolean isBiz = url.contains("/api/biz/");
        if (!(isQuota || isBalance || isBiz)) return;

        String token = auth.replaceFirst("(?i)^Bearer\\s+", "").trim();
        String mode = auth.toLowerCase().startsWith("bearer") ? "bearer" : "raw";
        String org = getHeader(headers, "bigmodel-organization");
        String project = getHeader(headers, "bigmodel-project");
        String planType = "";
        int q = url.indexOf("type=");
        if (q >= 0) {
            String v = url.substring(q + 5);
            if (v.contains("&")) v = v.substring(0, v.indexOf("&"));
            planType = v;
        }
        deliver(token, mode, org, project, planType);
    }

    private String getHeader(Map<String, String> headers, String key) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return "";
    }

    /** 扫描 localStorage 找 token（兜底） */
    private void scanLocalStorage(WebView view) {
        if (finished) return;
        String js = "(function(){var out={};try{for(var i=0;i<localStorage.length;i++){"
                + "var k=localStorage.key(i);out[k]=localStorage.getItem(k);}}catch(e){}"
                + "return JSON.stringify(out);})()";
        view.evaluateJavascript(js, value -> {
            if (TextUtils.isEmpty(value) || "null".equals(value)) return;
            try {
                String json = value;
                if (json.startsWith("\"")) {
                    json = new JSONObject("{\"v\":" + json + "}").getString("v");
                }
                JSONObject obj = new JSONObject(json);
                String token = findToken(obj);
                if (!TextUtils.isEmpty(token)) {
                    String mode = token.startsWith("eyJ") ? "bearer" : "raw";
                    deliver(token, mode,
                            findByKeyHint(obj, "organization", "orgid", "org_id"),
                            findByKeyHint(obj, "projectid", "project_id", "project"),
                            "");
                }
            } catch (Exception e) {
                Log.w(TAG, "parse localStorage err " + e.getMessage());
            }
        });
    }

    /**
     * DeepSeek：轮询扫描页面，抓创建 key 时一次性显示的 sk- key。
     * key 可能出现在 input.value、页面文本或剪贴板提示里，每 1 秒扫一次直到抓到或页面关闭。
     */
    private void startScanDeepSeekKey(WebView view) {
        final WebView w = view;
        Runnable task = new Runnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (finished || isFinishing() || isDestroyed()) return;
                ticks++;
                scanDeepSeekPage(w);
                // 最多扫 5 分钟，够用户完成登录+创建
                if (ticks < 300) scanHandler.postDelayed(this, 1000);
            }
        };
        scanHandler.post(task);
    }

    private void scanDeepSeekPage(WebView view) {
        // 扫所有 input/textarea 的 value + body 文本中形如 sk-xxxxxxxx 的串
        String js = "(function(){"
                + "var pat=/sk-[A-Za-z0-9]{20,}/g, found=[];"
                + "function scan(t){if(!t)return;var m;while((m=pat.exec(t))!==null){found.push(m[0]);}}"
                + "try{var els=document.querySelectorAll('input,textarea');"
                + "for(var i=0;i<els.length;i++){scan(els[i].value);}}catch(e){}"
                + "try{scan(document.body?document.body.innerText:'');}catch(e){}"
                + "return JSON.stringify(found);})()";
        view.evaluateJavascript(js, value -> {
            if (TextUtils.isEmpty(value) || "null".equals(value) || "[]".equals(value)) return;
            try {
                String json = value;
                if (json.startsWith("\"")) {
                    json = new JSONObject("{\"v\":" + json + "}").getString("v");
                }
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    String key = arr.getString(i);
                    if (!TextUtils.isEmpty(key) && key.length() > 20) {
                        Log.i(TAG, "deepseek api key captured, len=" + key.length());
                        // DeepSeek 余额接口要求 Authorization: Bearer sk-...
                        deliver(key, "bearer", "", "", "");
                        return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "scanDeepSeekPage err " + e.getMessage());
            }
        });
    }

    private String findToken(JSONObject obj) {
        String[] keys = {"Authorization", "authorization", "token", "access_token", "accessToken", "ZAI_API_KEY", "ZHIPUAI_API_KEY"};
        for (String k : keys) {
            if (obj.has(k)) {
                String v = obj.optString(k, "");
                if (!TextUtils.isEmpty(v)) return cleanToken(v);
            }
        }
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String v = obj.optString(it.next(), "");
            if (v.startsWith("eyJ") || v.startsWith("sk-")
                    || (v.length() > 20 && v.matches("^[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+$"))) {
                return cleanToken(v);
            }
        }
        return "";
    }

    private String cleanToken(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(v);
                if (o.has("token")) return o.optString("token");
            } catch (Exception ignored) {}
        }
        return v.replaceFirst("(?i)^Bearer\\s+", "").trim();
    }

    private String findByKeyHint(JSONObject obj, String... hints) {
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            String lower = k.toLowerCase();
            for (String h : hints) {
                if (lower.contains(h)) return obj.optString(k, "");
            }
        }
        return "";
    }

    private void deliver(String token, String mode, String org, String project, String planType) {
        if (TextUtils.isEmpty(token)) return;
        // 拦截请求头发生在 WebView 后台线程，UI 操作必须切回主线程，否则崩溃并丢失凭证
        synchronized (this) {
            if (finished) return;
            finished = true;
        }
        final String fToken = token, fMode = mode;
        final String fOrg = org == null ? "" : org;
        final String fProject = project == null ? "" : project;
        final String fPlan = planType == null ? "" : planType;
        Log.i(TAG, "credential captured, len=" + token.length() + " mode=" + mode);
        runOnUiThread(() -> {
            try {
                Intent result = new Intent();
                result.putExtra(RESULT_TOKEN, fToken);
                result.putExtra(RESULT_AUTHMODE, fMode);
                result.putExtra(RESULT_ORG, fOrg);
                result.putExtra(RESULT_PROJECT, fProject);
                result.putExtra(RESULT_PLANTYPE, fPlan);
                setResult(RESULT_OK, result);
                Toast.makeText(this, "已读取凭证", Toast.LENGTH_SHORT).show();
                finish();
            } catch (Exception e) {
                Log.e(TAG, "deliver ui error " + e.getMessage());
            }
        });
    }

    @Override
    public void onBackPressed() {
        Intent result = new Intent();
        result.putExtra(RESULT_TOKEN, "");
        setResult(RESULT_CANCELED, result);
        finish();
    }
}
