package com.tdiqsy.app;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 协议/声明详情页：用户协议、隐私协议、免责声明
 * 内容从后台动态加载，后台可修改。
 * 使用 WebView 渲染正文，支持完整 HTML（超链接、图片、样式等）。
 */
public class ContentDetailActivity extends AppCompatActivity {

    private TextView tvTitle;
    private WebView wvContent;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final String API_GET_CONTENT = "https://ok1666.cn/adminqsy/api/get_content.php";
    private static final String BASE_URL = "https://ok1666.cn/adminqsy";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_content_detail);

        tvTitle = findViewById(R.id.tvContentTitle);
        wvContent = findViewById(R.id.wvContent);
        setupWebView();

        String type = getIntent().getStringExtra("type");
        if (type == null) type = "agreement";

        loadContent(type);
    }

    private void setupWebView() {
        WebSettings settings = wvContent.getSettings();
        // 加固：后台正文为纯静态排版文本，无需 JS。关闭 JS 消除存储型 XSS 执行链路。
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        settings.setLoadWithOverviewMode(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkLoads(false);
        // 加固：不再允许混合内容（http 资源一律不加载）
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setTextZoom((int) (settings.getTextZoom() * 1.05f)); // 略微放大，提升手机可读性
        wvContent.setBackgroundColor(Color.TRANSPARENT);
        wvContent.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 仅允许 http/https 外链跳转系统浏览器；其它协议一律拦截
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        // 无浏览器时忽略
                    }
                }
                return true;
            }
        });
    }

    /**
     * 把后台正文包成完整的 HTML 文档，注入通用样式。
     */
    private String wrapHtml(String content) {
        return "<html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<style>"
                + "body{color:#333;line-height:1.9;font-size:16px;padding:12px;word-break:break-word;}"
                + "a{color:#667eea;} img{max-width:100%;height:auto;border-radius:6px;}"
                + "h1,h2,h3,h4,h5{color:#111;line-height:1.5;margin:18px 0 10px;}"
                + "p{margin:10px 0;} ul,ol{padding-left:22px;}"
                + "blockquote{border-left:4px solid #ddd;margin:10px 0;padding:4px 14px;color:#666;background:#f8f8f8;}"
                + "table{border-collapse:collapse;width:100%;} td,th{border:1px solid #ddd;padding:6px 10px;}"
                + "</style></head><body>" + content + "</body></html>";
    }

    private void showContent(String title, String content) {
        if (title != null && !title.isEmpty()) tvTitle.setText(title);
        wvContent.loadDataWithBaseURL(
                BASE_URL + "/",
                wrapHtml(content),
                "text/html",
                "utf-8",
                null);
    }

    private void loadContent(String type) {
        wvContent.loadDataWithBaseURL(BASE_URL + "/", wrapHtml("正在加载…"), "text/html", "utf-8", null);
        executor.execute(() -> {
            try {
                String msgTitle;
                String msgContent;
                URL url = new URL(API_GET_CONTENT + "?type=" + type);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                org.json.JSONObject json = new org.json.JSONObject(sb.toString());
                msgTitle = json.optString("title", "");
                msgContent = json.optString("content", "");
                boolean ok = json.optInt("code", 500) == 200;

                mainHandler.post(() -> {
                    if (ok && !msgContent.isEmpty()) {
                        showContent(msgTitle, msgContent);
                    } else {
                        String fallback = getLocalContent(type);
                        showContent(msgTitle,
                                !fallback.isEmpty() ? fallback : "内容加载失败，请检查网络后重试");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    String fallback = getLocalContent(type);
                    showContent(null, !fallback.isEmpty() ? fallback : "内容加载失败，请检查网络后重试");
                    Toast.makeText(this, "网络加载失败，显示本地内容", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String getLocalContent(String type) {
        int res;
        switch (type) {
            case "privacy":    res = R.string.privacy_content;    break;
            case "disclaimer": res = R.string.disclaimer_content; break;
            default:           res = R.string.agreement_content;  break;
        }
        return getString(res);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (wvContent != null) {
            ((ViewGroup) wvContent.getParent()).removeView(wvContent);
            wvContent.destroy();
        }
    }
}