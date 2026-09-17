package com.tdiqsy.app;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateManager {
    private static final String API_CHECK_UPDATE = "https://ok1666.cn/adminqsy/check_update.php";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 更新结果缓存
    private static volatile boolean hasResult = false;
    private static volatile int latestVersionCode = 0;
    private static volatile String latestVersionName = "";
    private static volatile String changelog = "";
    private static volatile String downloadUrl = "";
    private static volatile boolean forceUpdate = false;

    // 每次冷启动只检测一次
    private static volatile boolean checked = false;
    // 主界面弹窗只弹一次（切换页面不重复弹）
    private static volatile boolean dialogShown = false;

    /** 发起更新检测（应在启动页调用，每次 App 启动仅检测一次） */
    public static void checkUpdate() {
        if (checked) return;
        checked = true;
        executor.execute(() -> {
            try {
                URL url = new URL(API_CHECK_UPDATE);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                conn.disconnect();

                org.json.JSONObject json = new org.json.JSONObject(response.toString());
                latestVersionCode = json.optInt("versionCode", 0);
                latestVersionName = json.optString("versionName", "");
                changelog = json.optString("changelog", "");
                String rawUrl = json.optString("downloadUrl", "");
                forceUpdate = json.optBoolean("forceUpdate", false);
                // 加固：仅接受 https 且主机为 ok1666.cn 的官方下载地址，其余一律丢弃
                downloadUrl = isTrustedDownloadUrl(rawUrl) ? rawUrl : "";
                if (downloadUrl.isEmpty()) {
                    hasResult = false; // 不明下载源不弹更新，避免投毒
                } else {
                    hasResult = true;
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** 主界面是否应弹出更新弹窗（同一进程内只弹一次） */
    public static boolean shouldShowDialog() {
        if (dialogShown) return false;
        return hasResult;
    }

    /** 标记更新弹窗已展示 */
    public static void markDialogShown() {
        dialogShown = true;
    }

    /** 是否需要在首页弹出更新（仅当服务端版本号高于本地版本号才提示） */
    public static boolean shouldShowUpdate(Context context) {
        if (!hasResult) return false;
        try {
            int current = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
            return latestVersionCode > current;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasResult() { return hasResult; }
    public static boolean isChecked() { return checked; }
    public static boolean isForceUpdate() { return forceUpdate; }
    public static int getLatestVersionCode() { return latestVersionCode; }
    public static String getLatestVersionName() { return latestVersionName; }
    public static String getChangelog() { return changelog; }
    public static String getDownloadUrl() { return downloadUrl; }

    /** 客户端复用：仅信任 https 且主机为 ok1666.cn 的下载地址 */
    private static boolean isTrustedDownloadUrl(String url) {
        if (url == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase().replaceAll("\\.$", "");
            return host.equals("ok1666.cn") || host.endsWith(".ok1666.cn");
        } catch (Exception e) {
            return false;
        }
    }
}