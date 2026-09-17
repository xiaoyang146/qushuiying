package com.tdiqsy.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 心跳上报 —— 在线人数统计
 * 应用启动时向后台发送一次心跳，携带设备唯一标识；
 * 后台按「IP + 设备ID」每日去重统计。
 */
public class HeartbeatManager {

    private static final String API_HEARTBEAT = "https://ok1666.cn/adminqsy/heartbeat.php";
    private static final String PREFS_NAME = "heartbeat_prefs";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 每次冷启动只上报一次
    private static volatile boolean sent = false;

    /** 应用启动时上报心跳（后台线程执行，静默失败，不阻塞 UI） */
    public static void ping(Context context) {
        if (sent) return;
        sent = true;
        final String deviceId = getOrCreateDeviceId(context);
        executor.execute(() -> {
            try {
                URL url = new URL(API_HEARTBEAT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("User-Agent", "WatermarkRemover");

                org.json.JSONObject body = new org.json.JSONObject();
                body.put("device_id", deviceId);
                DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                out.writeBytes(body.toString());
                out.flush();
                out.close();

                int responseCode = conn.getResponseCode();
                // 读取响应体（即使不解析，也要消费流以正确关闭连接）
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        (responseCode >= 200 && responseCode < 300)
                                ? conn.getInputStream() : conn.getErrorStream()));
                while (reader.readLine() != null) { /* ignore */ }
                reader.close();
                conn.disconnect();
            } catch (Exception ignored) {
                // 心跳失败不影响主流程
            }
        });
    }

    /** 获取或创建持久化设备ID（优先 ANDROID_ID，异常/缺失时回退为 UUID） */
    private static String getOrCreateDeviceId(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String id = prefs.getString(KEY_DEVICE_ID, null);
            if (id != null && !id.isEmpty()) {
                return id;
            }

            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            // 已知的 bug 值需视同无效
            if (androidId == null || androidId.isEmpty() || "9774d56d682e549c".equalsIgnoreCase(androidId)) {
                androidId = UUID.randomUUID().toString();
            }
            prefs.edit().putString(KEY_DEVICE_ID, androidId).apply();
            return androidId;
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}