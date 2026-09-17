package com.tdiqsy.app;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;

import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MineActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final String API_CHECK_UPDATE = "https://ok1666.cn/adminqsy/check_update.php";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mine);

        // 动态获取应用图标和名称
        ImageView ivAppIcon = findViewById(R.id.ivAppIcon);
        TextView tvAppName = findViewById(R.id.tvAppName);

        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(getPackageName(), 0);
            ivAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo));
            tvAppName.setText(pm.getApplicationLabel(appInfo));
        } catch (Exception e) {
            tvAppName.setText("去水印");
        }

        HistoryDbHelper dbHelper = HistoryDbHelper.getInstance(this);

        // 历史记录
        findViewById(R.id.itemHistory).setOnClickListener(v -> {
            startActivity(new Intent(this, HistoryActivity.class));
            overridePendingTransition(0, 0);
        });

        // 关于
        findViewById(R.id.itemAbout).setOnClickListener(v -> {
            startActivity(new Intent(this, AboutActivity.class));
            overridePendingTransition(0, 0);
        });

        // 意见反馈
        findViewById(R.id.itemFeedback).setOnClickListener(v -> {
            startActivity(new Intent(this, FeedbackActivity.class));
            overridePendingTransition(0, 0);
        });

        // 检查更新
        findViewById(R.id.itemUpdate).setOnClickListener(v -> checkUpdate());

        // 底部导航：返回已有的首页实例（不要新建 MainActivity，否则首页解析结果会丢失）
        findViewById(R.id.navHome).setOnClickListener(v -> {
            finish();
            // 与进入“我的”页动画一致（均无转场动画）
            overridePendingTransition(0, 0);
        });

        findViewById(R.id.navMine).setOnClickListener(v -> {
            // 已在当前页
        });
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        // 返回首页也使用与进入一致的无动画转场
        overridePendingTransition(0, 0);
    }

    private void checkUpdate() {
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("正在检查更新…");
        progressDialog.setCancelable(false);
        progressDialog.show();

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

                mainHandler.post(() -> {
                    progressDialog.dismiss();
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(response.toString());
                        int latestVersionCode = json.optInt("versionCode", 0);
                        String latestVersionName = json.optString("versionName", "");
                        String changelog = json.optString("changelog", "");
                        String downloadUrl = json.optString("downloadUrl", "");
                        boolean forceUpdate = json.optBoolean("forceUpdate", false);

                        int currentVersionCode = getPackageManager()
                                .getPackageInfo(getPackageName(), 0).versionCode;

                        if (latestVersionCode > currentVersionCode) {
                            showUpdateDialog(latestVersionName, changelog, downloadUrl, forceUpdate);
                        } else {
                            Toast.makeText(MineActivity.this, "已是最新版本", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(MineActivity.this, "已是最新版本", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(MineActivity.this, "检查更新失败，请检查网络", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showUpdateDialog(String latestVersion, String changelog, String downloadUrl, boolean forceUpdate) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("发现新版本 v" + latestVersion)
                .setMessage("更新内容：\n" + changelog)
                .setPositiveButton("立即更新", null);

        if (!forceUpdate) {
            builder.setNegativeButton("稍后再说", null);
        }

        AlertDialog dialog = builder.create();

        if (forceUpdate) {
            // 强制更新：不可点击外部、不可用返回键关闭
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnKeyListener((d, keyCode, event) -> keyCode == android.view.KeyEvent.KEYCODE_BACK);
            dialog.show();
            // 覆盖更新按钮点击：打开下载地址但对话框不消失
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(downloadUrl));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "无法打开下载链接", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(downloadUrl));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "无法打开下载链接", Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HistoryDbHelper dbHelper = HistoryDbHelper.getInstance(this);
        TextView tvHistoryCount = findViewById(R.id.tvHistoryCount);
        int count = dbHelper.getCount();
        tvHistoryCount.setText("历史记录（" + count + "）");
    }
}