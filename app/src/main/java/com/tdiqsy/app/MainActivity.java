package com.tdiqsy.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.Button;
import android.widget.Switch;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText etUrl;
    private Button tvPaste;
    private Button btnParse;
    private Switch switchLive;
    private ProgressBar progressBar;
    private TextView tvHistory;
    private TextView tvDailyQuote;
    private View navHome;
    private View navMine;

    private ApiClient apiClient;
    private ExecutorService executor;
    private Handler mainHandler;
    private String currentSourceUrl;
    private HistoryDbHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 加固：二次环境复核（防止启动后开启 VPN/代理抓包），不安全则阻断并自动退出
        if (SecurityGuard.run(this)) {
            return;
        }

        apiClient = new ApiClient();
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        dbHelper = HistoryDbHelper.getInstance(this);

        initViews();
        initListeners();
        alignSubtitleToTitle();
        showHomePageUpdate();
        loadDailyQuote();
    }
    private void initViews() {
        etUrl = findViewById(R.id.etUrl);
        tvPaste = findViewById(R.id.tvPaste);
        switchLive = findViewById(R.id.switchLive);
        btnParse = findViewById(R.id.btnParse);
        progressBar = findViewById(R.id.progressBar);
        tvHistory = findViewById(R.id.tvHistory);
        tvDailyQuote = findViewById(R.id.tvDailyQuote);
        navHome = findViewById(R.id.navHome);
        navMine = findViewById(R.id.navMine);
    }

    private void initListeners() {
        tvPaste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    etUrl.setText(clip.getItemAt(0).getText());
                }
            }
        });

        btnParse.setOnClickListener(v -> parseVideo());

        tvHistory.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, HistoryActivity.class));
            overridePendingTransition(0, 0);
        });

        navHome.setOnClickListener(v -> { /* already home */ });
        navMine.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, MineActivity.class));
            overridePendingTransition(0, 0);
        });
    }

    /**
     * 动态计算副标题 letterSpacing，使其与标题两端对齐
     */
    private void alignSubtitleToTitle() {
        TextView tvMainTitle = findViewById(R.id.tvMainTitle);
        TextView tvMainSubtitle = findViewById(R.id.tvMainSubtitle);

        tvMainTitle.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                tvMainTitle.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                float titleWidth = tvMainTitle.getPaint().measureText("头大i去水印");
                float subtitleWidth = tvMainSubtitle.getPaint().measureText("简约 · 专业 · 高效");

                if (titleWidth > subtitleWidth) {
                    int charCount = "简约 · 专业 · 高效".length();
                    float extraSpace = titleWidth - subtitleWidth;
                    float letterSpacing = extraSpace / (charCount - 1) / tvMainSubtitle.getTextSize();
                    tvMainSubtitle.setLetterSpacing(letterSpacing);
                }
            }
        });
    }
    // ── 更新弹窗（仅展示启动页检测结果，只在首页弹一次） ──────

    private void showHomePageUpdate() {
        // 同一进程内只弹一次
        if (!UpdateManager.shouldShowDialog()) return;
        if (!UpdateManager.shouldShowUpdate(this)) return;
        UpdateManager.markDialogShown();
        showUpdateDialog(UpdateManager.getLatestVersionName(),
                UpdateManager.getChangelog(),
                UpdateManager.getDownloadUrl(),
                UpdateManager.isForceUpdate());
    }

    /** 加载每日一言（后台请求，完成后开启跑马灯滚动） */
    private void loadDailyQuote() {
        executor.execute(() -> {
            String quote = null;
            try {
                java.net.URL url = new java.net.URL("https://ok1666.cn/adminqsy/meiri/yiyan.php");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                int code = conn.getResponseCode();
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                org.json.JSONObject json = new org.json.JSONObject(sb.toString());
                org.json.JSONObject data = json.optJSONObject("data");
                String content = data != null ? data.optString("content", "") : "";
                String author = data != null ? data.optString("author", "") : "";
                if (!content.isEmpty()) {
                    quote = content + (author.isEmpty() ? "" : " —— " + author);
                }
            } catch (Exception ignored) {
            }

            final String result = quote;
            mainHandler.post(() -> {
                if (result != null && !result.isEmpty()) {
                    tvDailyQuote.setText(result);
                } else {
                    tvDailyQuote.setText("每日一言，伴你开启美好的一天");
                }
                // 需 setSelected=true 才能持续跑马灯滚动
                tvDailyQuote.setSelected(true);
            });
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

    // ── 解析视频 ──────────────────────────────────────────────

    private void parseVideo() {
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请先输入视频链接", Toast.LENGTH_SHORT).show();
            return;
        }

        currentSourceUrl = url;
        setLoading(true);

        executor.execute(() -> {
            try {
                VideoInfo info = apiClient.parseVideo(url);
                mainHandler.post(() -> {
                    setLoading(false);

                    // ── 智能开关提示：解析类型与开关不匹配时弹窗提醒 ──
                    if (info.isLive() && switchLive != null && !switchLive.isChecked()) {
                        // live 内容但开关关闭：提示用户开启
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("检测到动态图")
                                .setMessage("该链接包含Live动态图内容，是否开启\"是否为live动态图\"开关后重新解析？")
                                .setPositiveButton("开启并重新解析", (d, w) -> {
                                    switchLive.setChecked(true);
                                    parseVideo();
                                })
                                .setNegativeButton("使用静态封面", (d, w) -> {
                                    // 继续当前静态封面展示
                                    launchParseResult(info);
                                })
                                .setCancelable(false)
                                .show();
                        return;
                    }
                    if (!info.isLive() && switchLive != null && switchLive.isChecked()) {
                        // 非 live 内容但开关开启：提示用户关闭
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("非动态图内容")
                                .setMessage("该链接不包含Live动态图，建议关闭\"是否为live动态图\"开关以获得最佳体验。")
                                .setPositiveButton("关闭开关", (d, w) -> switchLive.setChecked(false))
                                .setNegativeButton("忽略", null)
                                .show();
                        // 不 return，继续正常展示
                    }

                    if (info.isLive() && switchLive != null && !switchLive.isChecked()) {
                        // 开关关闭：收集所有 live 图的封面作为静态图片展示
                        List<String> allCovers = new ArrayList<>();
                        if (info.livePhotos != null) {
                            for (VideoInfo.LivePhotoItem lp : info.livePhotos) {
                                if (lp.imageUrl != null && !lp.imageUrl.isEmpty()
                                        && !allCovers.contains(lp.imageUrl)) {
                                    allCovers.add(lp.imageUrl);
                                }
                            }
                        }
                        info.livePhotos = null;
                        info.contentType = "image";
                        info.videoUrl = null;
                        if (!allCovers.isEmpty()) {
                            info.imageUrls = allCovers;
                            info.cover = allCovers.get(0);
                        } else if (info.imageUrls != null && !info.imageUrls.isEmpty()) {
                            info.cover = info.imageUrls.get(0);
                        }
                    }
                    launchParseResult(info);
                });
            } catch (ApiClient.ApiException e) {
                mainHandler.post(() -> {
                    setLoading(false);
                    Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setLoading(false);
                    Toast.makeText(MainActivity.this, "网络错误，请稍后重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        btnParse.setEnabled(!loading);
        etUrl.setEnabled(!loading);
        tvPaste.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnParse.setText(loading ? "解析中…" : "解析视频");
    }

    /** 解析成功：写入历史并跳转到「解析结果」界面展示 */
    private void launchParseResult(VideoInfo info) {
        dbHelper.insert(info, currentSourceUrl);

        Intent intent = new Intent(MainActivity.this, ParseResultActivity.class);
        intent.putExtra(ParseResultActivity.EXTRA_VIDEO, info);
        intent.putExtra(ParseResultActivity.EXTRA_SOURCE_URL, currentSourceUrl);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
