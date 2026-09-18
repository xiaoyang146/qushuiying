package com.tdiqsy.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import android.widget.FrameLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ParseResultActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO = "extra_video";
    public static final String EXTRA_SOURCE_URL = "extra_source_url";

    private MaterialCardView cardResult;
    private ImageView ivCover;
    private WebView videoView;
    private View btnPlay;
    private TextView tvPlatform;
    private TextView tvAuthor;
    private TextView tvTitle;
    private Button btnSave;
    private Button btnCopyLink;
    private LinearLayout layoutQuality;
    private TextView spinnerQuality;
    private LinearLayout layoutSaveProgress;
    private TextView tvSaveProgress;
    private ProgressBar progressSave;
    private TextView tvVideoInfo;
    private TextView tvFullscreenInfo;
    private HorizontalScrollView imageScroll;
    private LinearLayout imageStrip;
    private ViewPager imagePager;

    // 全屏相关
    private FrameLayout fullscreenOverlay;
    private ImageView btnFullscreenExit;
    private View mainContent;
    private boolean isFullscreen = false;

    // HTML 播放器（WebView）相关
    private boolean playerReady = false;
    private String pendingVideoUrl = null;
    private boolean isVideoPlaying = false;

    private ExecutorService executor;
    private Handler mainHandler;
    private VideoInfo currentVideo;
    private String currentSourceUrl;

    // 清晰度
    private List<String> qualityLabels = new ArrayList<>();
    private int currentQualityIndex = 0;

    // 播放器实际时长（mm:ss），以播放器进度条为准
    private String playerDurationText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parse_result);

        // 加固：二次环境复核（防止启动后开启 VPN/代理抓包），不安全则阻断并自动退出
        if (SecurityGuard.run(this)) {
            return;
        }

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        currentVideo = (VideoInfo) getIntent().getSerializableExtra(EXTRA_VIDEO);
        currentSourceUrl = getIntent().getStringExtra(EXTRA_SOURCE_URL);

        initViews();
        initListeners();

        if (currentVideo != null) {
            renderResult(currentVideo);
        }
    }

    private void initViews() {
        cardResult = findViewById(R.id.cardResult);
        ivCover = findViewById(R.id.ivCover);
        videoView = findViewById(R.id.videoView);
        btnPlay = findViewById(R.id.btnPlay);
        tvPlatform = findViewById(R.id.tvPlatform);
        tvAuthor = findViewById(R.id.tvAuthor);
        tvTitle = findViewById(R.id.tvTitle);
        btnSave = findViewById(R.id.btnSave);
        btnCopyLink = findViewById(R.id.btnCopyLink);
        layoutQuality = findViewById(R.id.layoutQuality);
        spinnerQuality = findViewById(R.id.spinnerQuality);
        layoutSaveProgress = findViewById(R.id.layoutSaveProgress);
        tvSaveProgress = findViewById(R.id.tvSaveProgress);
        progressSave = findViewById(R.id.progressSave);
        tvVideoInfo = findViewById(R.id.tvVideoInfo);
        tvFullscreenInfo = findViewById(R.id.tvFullscreenInfo);
        imageScroll = findViewById(R.id.imageScroll);
        imageStrip = findViewById(R.id.imageStrip);
        imagePager = findViewById(R.id.imagePager);

        fullscreenOverlay = findViewById(R.id.fullscreenOverlay);
        btnFullscreenExit = findViewById(R.id.btnFullscreenExit);
        mainContent = findViewById(R.id.mainContent);

        setupWebView();
    }

    private void initListeners() {
        btnPlay.setOnClickListener(v -> {
            if (currentVideo != null) {
                if (currentVideo.isLive() && currentVideo.livePhotos != null && !currentVideo.livePhotos.isEmpty()) {
                    // 动态图：播放当前查看的那一张的短视频片段
                    playLiveAt(imagePager.getCurrentItem());
                } else if (currentVideo.videoUrl != null) {
                    playVideoInline();
                }
            }
        });

        btnFullscreenExit.setOnClickListener(v -> exitFullscreen());

        btnCopyLink.setOnClickListener(v -> {
            if (currentVideo != null && currentVideo.videoUrl != null) {
                copyToClipboard(currentVideo.videoUrl, "视频链接已复制");
            }
        });

        btnSave.setOnClickListener(v -> {
            if (currentVideo != null && currentVideo.isLive()) {
                // 实况图/动态图：保存当前项的短视频片段（动态内容）
                saveMediaByIndex(imagePager.getCurrentItem(), null);
            } else if (currentVideo != null && currentVideo.isImage()) {
                // 图集内容改为长按单张保存，按钮仅做引导提示
                Toast.makeText(this, "长按任意图片即可保存该张图片", Toast.LENGTH_SHORT).show();
            } else {
                saveVideoWithProgress();
            }
        });

        tvAuthor.setOnClickListener(v -> {
            if (currentVideo != null && currentVideo.author != null && !currentVideo.author.isEmpty()) {
                copyToClipboard(currentVideo.author, "作者名已复制");
            }
        });

        tvTitle.setOnClickListener(v -> {
            if (currentVideo != null && currentVideo.title != null && !currentVideo.title.isEmpty()) {
                copyToClipboard(currentVideo.title, "标题已复制");
            }
        });

        spinnerQuality.setOnClickListener(v -> showQualityDialog());
    }

    /** 初始化 HTML5 播放器 WebView，并注册 JS 桥接 */
    private void setupWebView() {
        WebSettings ws = videoView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        videoView.setBackgroundColor(0x00000000);
        videoView.setVerticalScrollBarEnabled(false);
        videoView.setHorizontalScrollBarEnabled(false);
        videoView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                playerReady = true;
                if (pendingVideoUrl != null) {
                    loadVideoInPlayer(pendingVideoUrl);
                    pendingVideoUrl = null;
                }
            }
        });
        videoView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        videoView.loadUrl("file:///android_asset/player.html");
    }

    /** JS 桥接：HTML 播放器 -> Android */
    private class JsBridge {
        @JavascriptInterface
        public void onReady(final double duration) {
            runOnUiThread(() -> {
                // 时间统一从播放器实际时长获取（以进度条为准），刷新信息标签
                if (duration > 0 && currentVideo != null) {
                    playerDurationText = formatTime((long) (duration * 1000));
                    updateVideoInfoLabel();
                }
            });
        }

        @JavascriptInterface
        public void onState(final boolean playing) {
            runOnUiThread(() -> isVideoPlaying = playing);
        }

        @JavascriptInterface
        public void onEnd() {
            runOnUiThread(() -> isVideoPlaying = false);
        }

        @JavascriptInterface
        public void onError() {
            runOnUiThread(() -> {
                isVideoPlaying = false;
                // 播放失败时退回封面态
                videoView.setVisibility(View.GONE);
                ivCover.setVisibility(View.VISIBLE);
                btnPlay.setVisibility(View.VISIBLE);
                Toast.makeText(ParseResultActivity.this, "视频播放失败", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void onFullscreen() {
            runOnUiThread(ParseResultActivity.this::enterFullscreen);
        }
    }

    private void copyToClipboard(String text, String toast) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("label", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    // ── 工具方法 ──────────────────────────────────────────────

    private String formatTime(long ms) {
        if (ms <= 0) return "00:00";
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return String.format(java.util.Locale.CHINA, "%02d:%02d", m, s);
    }

    /** 明文 http 升级为 https（客户端全局禁用明文流量） */
    private String normalizeUrl(String url) {
        if (url != null && url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }

    /** 转义字符串，安全注入到 JS 代码的单引号字符串中 */
    private String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** 让 HTML 播放器加载并自动播放指定地址（自动处理 HLS / 普通 mp4） */
    private void loadVideoInPlayer(String url) {
        if (videoView == null) return;
        final String safe = jsEscape(normalizeUrl(url));
        videoView.evaluateJavascript("Player.loadHlsJs('" + safe + "');", null);
    }

    // ── 视频播放（HTML5 播放器） ──────────────────────────────

    /** 播放动态图（live）第 index 张对应的短视频片段；匹配不到时按索引兜底 */
    private void playLiveAt(int index) {
        if (currentVideo == null || currentVideo.livePhotos == null
                || currentVideo.livePhotos.isEmpty()) return;
        VideoInfo.LivePhotoItem target = null;
        String currentImg = null;
        if (currentVideo.imageUrls != null && index >= 0 && index < currentVideo.imageUrls.size()) {
            currentImg = currentVideo.imageUrls.get(index);
        }
        if (currentImg != null) {
            for (VideoInfo.LivePhotoItem item : currentVideo.livePhotos) {
                if (item.imageUrl != null && item.imageUrl.equals(currentImg)) {
                    target = item;
                    break;
                }
            }
        }
        if (target == null && index >= 0 && index < currentVideo.livePhotos.size()) {
            target = currentVideo.livePhotos.get(index);
        }
        if (target == null) target = currentVideo.livePhotos.get(0);
        if (target != null && target.videoUrl != null && !target.videoUrl.isEmpty()) {
            currentVideo.videoUrl = target.videoUrl;
            playVideoInline();
        } else {
            Toast.makeText(this, "该动态图无可用视频", Toast.LENGTH_SHORT).show();
        }
    }

    private void playVideoInline() {
        if (currentVideo == null || currentVideo.videoUrl == null) return;
        try {
            videoView.setVisibility(View.VISIBLE);
            ivCover.setVisibility(View.GONE);
            btnPlay.setVisibility(View.GONE);

            if (playerReady) {
                loadVideoInPlayer(currentVideo.videoUrl);
            } else {
                pendingVideoUrl = currentVideo.videoUrl;
            }
        } catch (Exception e) {
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 停止播放并退回封面态（用于重新解析 / 离开页面） */
    private void pauseVideo() {
        isVideoPlaying = false;
        playerDurationText = "";
        if (videoView != null) {
            try {
                videoView.evaluateJavascript("Player.pause();", null);
            } catch (Exception ignored) {}
        }
        if (!isFullscreen) {
            videoView.setVisibility(View.GONE);
            if (currentVideo == null || !currentVideo.isLive()) {
                ivCover.setVisibility(View.VISIBLE);
                btnPlay.setVisibility(View.VISIBLE);
            }
        }
        tvVideoInfo.setVisibility(View.GONE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isVideoPlaying) pauseVideo();
        if (isFullscreen) exitFullscreen();
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) {
            exitFullscreen();
            return;
        }
        super.onBackPressed();
    }

    // ── 全屏播放（复用同一个 WebView，保持播放不断） ────────────

    private void enterFullscreen() {
        if (isFullscreen || currentVideo == null) return;

        // 把 WebView 从内嵌容器移动到全屏覆盖层
        FrameLayout parent = (FrameLayout) videoView.getParent();
        if (parent != null) {
            parent.removeView(videoView);
        }
        fullscreenOverlay.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mainContent.setVisibility(View.GONE);
        fullscreenOverlay.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        if (tvFullscreenInfo.getText() != null && tvFullscreenInfo.getText().length() > 0) {
            tvFullscreenInfo.setVisibility(View.VISIBLE);
        }
        isFullscreen = true;
    }

    private void exitFullscreen() {
        if (!isFullscreen) return;

        // 把 WebView 放回内嵌容器（放在封面图之上）
        FrameLayout parent = (FrameLayout) videoView.getParent();
        if (parent != null) {
            parent.removeView(videoView);
        }
        FrameLayout container = (FrameLayout) findViewById(R.id.videoContainer);
        if (container != null) {
            container.addView(videoView, 1, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        fullscreenOverlay.setVisibility(View.GONE);
        mainContent.setVisibility(View.VISIBLE);
        tvFullscreenInfo.setVisibility(View.GONE);

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) getSupportActionBar().show();

        isFullscreen = false;
    }

    private void renderResult(VideoInfo info) {
        cardResult.setVisibility(View.VISIBLE);
        tvPlatform.setText(info.platformName);
        tvAuthor.setText(info.author);
        tvTitle.setText(info.title);

        // 停止旧视频播放
        pauseVideo();

        if (info.isImage()) {
            // ── 图集/图文内容：以图片画廊代替视频播放器 ──
            imageScroll.setVisibility(View.VISIBLE);
            imageStrip.removeAllViews();
            loadImageStrip(info);
            btnSave.setText("长按图片保存");
            layoutQuality.setVisibility(View.GONE);
            // 主预览：左右滑动切换、点击放大、长按保存
            ivCover.setVisibility(View.GONE);
            videoView.setVisibility(View.GONE);
            btnPlay.setVisibility(View.GONE);
            tvVideoInfo.setVisibility(View.GONE);
            tvFullscreenInfo.setVisibility(View.GONE);
            setupImagePager(info);
            return;
        }

        if (info.isLive()) {
            // ── 封面位置直接展示动态图 ViewPager：每页一个 VideoView 自动循环播放 ──
            ivCover.setVisibility(View.GONE);
            btnPlay.setVisibility(View.GONE);
            videoView.setVisibility(View.GONE);
            imageScroll.setVisibility(View.VISIBLE);
            imageStrip.removeAllViews();
            loadImageStrip(info);
            btnSave.setText("长按图片保存");
            layoutQuality.setVisibility(View.GONE);
            tvVideoInfo.setVisibility(View.GONE);
            tvFullscreenInfo.setVisibility(View.GONE);
            // 主预览：ViewPager 每页一个 VideoView 播放动态视频
            setupLivePager(info);
            return;
        }

        // ── 视频内容 ──
        imageScroll.setVisibility(View.GONE);
        imageStrip.removeAllViews();
        imagePager.setVisibility(View.GONE);
        btnSave.setText("保存视频");

        if (info.cover != null && !info.cover.isEmpty()) {
            final String coverUrl = info.cover;
            executor.execute(() -> {
                try {
                    android.graphics.Bitmap bmp = loadImageSampled(coverUrl, 720, 480);
                    if (bmp != null) {
                        mainHandler.post(() -> ivCover.setImageBitmap(bmp));
                    }
                } catch (Exception ignored) {}
            });
        }

        ivCover.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.GONE);
        btnPlay.setVisibility(View.VISIBLE);
        isVideoPlaying = false;

        updateVideoInfoLabel();
        setupQualitySpinner(info);
        refreshActualVideoSize(info);
    }

    /**
     * 从实际视频地址获取真实文件大小（HEAD 优先），覆盖后端返回的大小。
     * 后端接口给出的 size 常与所选清晰度不匹配，以实际地址为准。
     */
    private void refreshActualVideoSize(final VideoInfo info) {
        final String targetUrl = info.videoUrl;
        if (targetUrl == null || targetUrl.isEmpty()) return;
        executor.execute(() -> {
            final long size = getContentLength(targetUrl);
            if (size <= 0) return;
            mainHandler.post(() -> {
                // 仅当仍是同一个视频且清晰度未再切换时，才应用该大小
                if (currentVideo == info && targetUrl.equals(currentVideo.videoUrl)) {
                    currentVideo.sizeMb = formatBytes(size);
                    updateVideoInfoLabel();
                }
            });
        });
    }

    /** 获取 URL 对应文件的字节数（HEAD 优先，失败则 GET+Range 回退） */
    private long getContentLength(String urlStr) {
        urlStr = normalizeUrl(urlStr);
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.connect();
            long len = conn.getContentLengthLong();
            conn.disconnect();
            if (len > 0) return len;

            // HEAD 不支持时，用 GET + Range 只取前 1 字节
            conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.connect();
            int code = conn.getResponseCode();
            String contentRange = conn.getHeaderField("Content-Range");
            String contentLength = conn.getHeaderField("Content-Length");
            conn.disconnect();
            if (contentRange != null) {
                int slash = contentRange.lastIndexOf('/');
                if (slash >= 0) {
                    try {
                        return Long.parseLong(contentRange.substring(slash + 1).trim());
                    } catch (Exception ignored) {}
                }
            }
            if (contentLength != null) {
                try {
                    return Long.parseLong(contentLength.trim());
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** 字节数格式化为可读字符串（MB / KB / B） */
    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.1fMB", bytes / (1024.0 * 1024.0));
        } else if (bytes >= 1024) {
            return String.format(java.util.Locale.CHINA, "%.0fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }

    /**
     * 更新播放器右上角视频信息标签（格式 / 时间 / 大小，多行显示）。
     */
    private void updateVideoInfoLabel() {
        if (currentVideo == null) {
            tvVideoInfo.setVisibility(View.GONE);
            tvFullscreenInfo.setVisibility(View.GONE);
            return;
        }
        VideoInfo info = currentVideo;
        java.util.List<String> parts = new java.util.ArrayList<>();

        // 格式
        String format = info.format == null ? "" : info.format.trim().toLowerCase();
        int slash = format.indexOf('/');
        if (slash >= 0) format = format.substring(slash + 1);
        if (!format.isEmpty()) {
            parts.add("格式: " + format.toUpperCase());
        }

        // 时间：优先用播放器实际时长；未播放前用后端值（无法解析则省略）
        String durText = !playerDurationText.isEmpty()
                ? playerDurationText
                : formatDurationText(info.duration);
        if (!durText.isEmpty()) {
            parts.add("时间: " + durText);
        }

        // 大小
        String size = info.sizeMb == null ? "" : info.sizeMb.trim();
        if (!size.isEmpty()) {
            parts.add("大小: " + size);
        }

        if (parts.isEmpty()) {
            tvVideoInfo.setVisibility(View.GONE);
            tvFullscreenInfo.setVisibility(View.GONE);
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(parts.get(i));
        }
        String text = sb.toString();
        tvVideoInfo.setText(text);
        tvFullscreenInfo.setText(text);
        tvVideoInfo.setVisibility(View.VISIBLE);
        tvFullscreenInfo.setVisibility(View.VISIBLE);
    }

    /**
     * 将时长转成 mm:ss（支持秒、毫秒、秒.小数、mm:ss、hh:mm:ss）。
     * 无法解析或明显异常的值返回空串，避免显示脏数据。
     */
    private String formatDurationText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";

        long totalSec = -1;

        // 纯数字：>600000 视为毫秒，否则视为秒
        if (t.matches("\\d+")) {
            try {
                long v = Long.parseLong(t);
                totalSec = (v > 600000 ? v : v * 1000L) / 1000;
            } catch (Exception e) {
                return "";
            }
        }
        // 秒.小数，如 "1781.07"（秒）
        else if (t.matches("\\d+\\.\\d+")) {
            try {
                totalSec = (long) Double.parseDouble(t);
            } catch (Exception e) {
                return "";
            }
        }
        // mm:ss(.sss) 或 hh:mm:ss(.sss)
        else {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d{1,3}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?$").matcher(t);
            if (m.matches()) {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                if (b >= 60) return ""; // 秒非法
                if (m.group(3) != null) {
                    int c = Integer.parseInt(m.group(3));
                    if (c >= 60) return ""; // 秒非法
                    totalSec = a * 3600L + b * 60L + c;
                } else {
                    totalSec = a * 60L + b;
                }
            }
        }

        // 明显异常（0 或超过 24 小时）一律视为无效
        if (totalSec <= 0 || totalSec > 24 * 3600) return "";
        return String.format(java.util.Locale.CHINA, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    /**
     * 采样解码网络图片，避免大图（如 4K 图集）解码时 OOM。
     */
    private android.graphics.Bitmap loadImageSampled(String urlStr, int reqW, int reqH) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        java.io.InputStream is = conn.getInputStream();
        // 第一次读取仅解析 bounds
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeStream(is, null, opts);
        is.close();
        conn.disconnect();

        int sample = 1;
        while (opts.outWidth / sample > reqW * 2 || opts.outHeight / sample > reqH * 2) {
            sample *= 2;
        }
        android.graphics.BitmapFactory.Options decodeOpts = new android.graphics.BitmapFactory.Options();
        decodeOpts.inSampleSize = sample;
        decodeOpts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;

        java.net.HttpURLConnection conn2 = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn2.setConnectTimeout(15000);
        conn2.setReadTimeout(20000);
        conn2.setRequestProperty("User-Agent", "Mozilla/5.0");
        java.io.InputStream is2 = conn2.getInputStream();
        try {
            return android.graphics.BitmapFactory.decodeStream(is2, null, decodeOpts);
        } finally {
            is2.close();
            conn2.disconnect();
        }
    }

    /** 加载图集缩略图条：每屏默认展示约 5 张，超出部分左右滑动查看；点击放大、长按保存 */
    private void loadImageStrip(VideoInfo info) {
        if (info.imageUrls == null || info.imageUrls.isEmpty()) return;
        final int total = info.imageUrls.size();
        int thumbSize = (int) (64 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < total; i++) {
            final String imgUrl = info.imageUrls.get(i);
            ImageView thumb = new ImageView(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(thumbSize, thumbSize);
            lp.setMargins(0, 0, (int) (6 * getResources().getDisplayMetrics().density), 0);
            thumb.setLayoutParams(lp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundResource(R.drawable.bg_cover);
            final int idx = i;
            thumb.setOnClickListener(v -> viewImageFullscreen(idx, info.imageUrls));
            thumb.setOnLongClickListener(v -> {
                saveSingleImage(imgUrl);
                return true;
            });
            imageStrip.addView(thumb);
            executor.execute(() -> {
                try {
                    android.graphics.Bitmap bmp = loadImageSampled(imgUrl, 240, 240);
                    if (bmp != null) {
                        mainHandler.post(() -> {
                            if (idx < imageStrip.getChildCount()) {
                                ((ImageView) imageStrip.getChildAt(idx)).setImageBitmap(bmp);
                            }
                        });
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    /** 主预览画廊：左右滑动切换、点击放大、长按保存 */

    /** 实况图主展示：封面位置 ViewPager 每页一个 VideoView 播放动态视频 */
    private void setupLivePager(VideoInfo info) {
        if (info.livePhotos == null || info.livePhotos.isEmpty()) {
            imagePager.setVisibility(View.GONE);
            return;
        }
        // 防止 VideoView 溢出 ViewPager 边界（穿布局）
        imagePager.setClipChildren(true);
        imagePager.setClipToPadding(true);
        imagePager.setAdapter(new LivePagerAdapter(info,
                () -> saveMediaByIndex(imagePager.getCurrentItem(), null)));
        // 缩略图点击切换到对应页
        for (int i = 0; i < imageStrip.getChildCount(); i++) {
            final int idx = i;
            View thumb = imageStrip.getChildAt(i);
            thumb.setOnClickListener(v -> imagePager.setCurrentItem(idx, true));
        }
        imagePager.setCurrentItem(0);
        imagePager.setVisibility(View.VISIBLE);
    }

    private void setupImagePager(VideoInfo info) {
        final java.util.List<String> urls = info.imageUrls;
        if (urls == null || urls.isEmpty()) {
            imagePager.setVisibility(View.GONE);
            return;
        }
        imagePager.setAdapter(new ImagePagerAdapter(urls, false,
                () -> viewImageFullscreen(imagePager.getCurrentItem(), urls),
                () -> saveMediaByIndex(imagePager.getCurrentItem(), null)));
        imagePager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}
            @Override public void onPageSelected(int position) {
                // 动态图内容：滑动到第几张就自动播放该张的动态视频（对应播放整段）
                if (currentVideo != null && currentVideo.isLive()
                        && currentVideo.livePhotos != null && !currentVideo.livePhotos.isEmpty()) {
                    pauseVideo();
                    playLiveAt(position);
                }
            }
            @Override public void onPageScrollStateChanged(int state) {}
        });
        imagePager.setCurrentItem(0);
        imagePager.setVisibility(View.VISIBLE);
    }

    /** 全屏查看图片：左右滑动切换、双指缩放、点击隐藏/显示工具条、长按保存 */
    private void viewImageFullscreen(int index, java.util.List<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        final int size = urls.size();
        android.view.View v = getLayoutInflater().inflate(R.layout.dialog_image_viewer, null);
        final ViewPager pager = v.findViewById(R.id.viewerPager);
        final TextView counter = v.findViewById(R.id.viewerCounter);
        final View topBar = v.findViewById(R.id.viewerTopBar);
        final ImageView close = v.findViewById(R.id.viewerClose);
        final View bottomBar = v.findViewById(R.id.viewerBottomBar);
        final Button saveBtn = v.findViewById(R.id.viewerSave);

        pager.setAdapter(new ImagePagerAdapter(urls, true,
                () -> {
                    int vis = topBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
                    topBar.setVisibility(vis);
                    bottomBar.setVisibility(vis);
                },
                () -> saveMediaByIndex(pager.getCurrentItem(), saveBtn)));
        pager.setCurrentItem(index);
        counter.setText((index + 1) + "/" + size);
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                counter.setText((position + 1) + "/" + size);
            }
        });

        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
        close.setOnClickListener(vv -> { if (holder[0] != null) holder[0].dismiss(); });
        saveBtn.setOnClickListener(vv -> saveMediaByIndex(pager.getCurrentItem(), saveBtn));

        holder[0] = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(v)
                .create();
        holder[0].show();
    }

    /** 加载单张图到指定图片视图（异步） */
    private void loadBitmapInto(final String url, final ImageView target) {
        executor.execute(() -> {
            try {
                final android.graphics.Bitmap bmp = loadImageSampled(url, 1280, 1280);
                if (bmp != null) {
                    mainHandler.post(() -> target.setImageBitmap(bmp));
                }
            } catch (Exception ignored) {}
        });
    }

    /** 长按保存单张图片（无需进度 UI） */
    private void saveSingleImage(final String url) {
        if (url == null || url.isEmpty()) return;
        executor.execute(() -> {
            final boolean ok = saveSingleImageSync(url);
            mainHandler.post(() -> Toast.makeText(this, ok ? "图片已保存" : "图片保存失败", Toast.LENGTH_SHORT).show());
        });
    }

    /**
     * 按当前图集索引保存媒体：
     * - live（实况图/动态图）内容：优先保存该项的动态视频片段（MP4），无视频时保存静态图；
     * - 普通图集：保存原图（自动探测 GIF/WebP 等格式，保留动画帧）。
     */
    private void saveMediaByIndex(final int index, final Button saveBtn) {
        if (saveBtn != null) {
            saveBtn.setEnabled(false);
            saveBtn.setText("保存中…");
        }
        executor.execute(() -> {
            boolean ok = false;
            boolean isVideo = false;
            String url = null;
            if (currentVideo != null) {
                // 先取当前正在查看的图 URL（画廊第 index 张）
                String displayUrl = null;
                if (currentVideo.imageUrls != null
                        && index >= 0 && index < currentVideo.imageUrls.size()) {
                    displayUrl = currentVideo.imageUrls.get(index);
                }
                if (currentVideo.isLive() && currentVideo.livePhotos != null
                        && !currentVideo.livePhotos.isEmpty()) {
                    // 1) 按 imageUrl 精确匹配 live 项（应对图集顺序与 livePhotos 错位）
                    VideoInfo.LivePhotoItem matched = null;
                    if (displayUrl != null) {
                        for (VideoInfo.LivePhotoItem item : currentVideo.livePhotos) {
                            if (item.imageUrl != null && item.imageUrl.equals(displayUrl)) {
                                matched = item;
                                break;
                            }
                        }
                    }
                    // 2) 匹配不到则按索引兜底
                    if (matched == null && index >= 0 && index < currentVideo.livePhotos.size()) {
                        matched = currentVideo.livePhotos.get(index);
                    }
                    if (matched != null) {
                        if (matched.videoUrl != null && !matched.videoUrl.isEmpty()) {
                            url = matched.videoUrl;
                            isVideo = true;
                        } else if (matched.imageUrl != null && !matched.imageUrl.isEmpty()) {
                            url = matched.imageUrl;
                        }
                    } else if (displayUrl != null) {
                        url = displayUrl;
                    }
                } else if (displayUrl != null) {
                    url = displayUrl;
                }
            }
            if (url != null) {
                try {
                    if (isVideo) {
                        ok = saveVideoToGallerySync(url);
                    } else {
                        ok = saveSingleImageSync(url);
                    }
                } catch (Throwable ignored) {
                }
            }
            final boolean okF = ok;
            final boolean videoF = isVideo;
            mainHandler.post(() -> {
                if (saveBtn != null) {
                    saveBtn.setEnabled(true);
                    saveBtn.setText("保存当前图片");
                }
                Toast.makeText(ParseResultActivity.this,
                        okF ? (videoF ? "动态图已保存" : "图片已保存") : "保存失败",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** 保存动态图/实况图视频片段（MP4）到系统相册 */
    private boolean saveVideoToGallerySync(String url) {
        try {
            String urlStr = normalizeUrl(url);
            String fileName = "watermark_live_" + System.currentTimeMillis() + ".mp4";
            String mime = "video/mp4";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, mime);
                values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/WatermarkRemover");
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    streamTo(urlStr, os);
                    return true;
                }
            } else {
                java.io.File dir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_MOVIES), "WatermarkRemover");
                if (!dir.exists()) dir.mkdirs();
                java.io.File file = new java.io.File(dir, fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                streamTo(urlStr, fos);
                sendBroadcast(new android.content.Intent(
                        android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.fromFile(file)));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 全屏查看器内保存当前图片（带按钮进度提示） */
    private void saveCurrentImageWithUi(final java.util.List<String> urls, final int index, final Button saveBtn) {
        if (urls == null || index < 0 || index >= urls.size()) return;
        final String url = urls.get(index);
        if (saveBtn != null) {
            saveBtn.setEnabled(false);
            saveBtn.setText("保存中…");
        }
        executor.execute(() -> {
            final boolean ok = saveSingleImageSync(url);
            mainHandler.post(() -> {
                if (saveBtn != null) {
                    saveBtn.setEnabled(true);
                    saveBtn.setText("保存当前图片");
                }
                Toast.makeText(this, ok ? "图片已保存" : "图片保存失败", Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** 保存单张图片到系统相册（自动探测格式） */
    private boolean saveSingleImageSync(String url) {
        try {
            String urlStr = normalizeUrl(url);
            String ext = detectImageExt(urlStr);
            String fileName = "watermark_image_" + System.currentTimeMillis() + ext;
            String mime = "image/" + ext.substring(1);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatermarkRemover");
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    streamTo(urlStr, os);
                    return true;
                }
            } else {
                java.io.File dir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_PICTURES), "WatermarkRemover");
                if (!dir.exists()) dir.mkdirs();
                java.io.File file = new java.io.File(dir, fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                streamTo(urlStr, fos);
                sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.fromFile(file)));
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 通过响应头字节探测图片格式（多数 CDN 地址无扩展名） */
    private String detectImageExt(String urlStr) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.connect();
        java.io.InputStream is = conn.getInputStream();
        byte[] h = new byte[12];
        int r = 0;
        while (r < h.length) {
            int n = is.read(h, r, h.length - r);
            if (n < 0) break;
            r += n;
        }
        is.close();
        conn.disconnect();
        if (r >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return ".jpg";
        if (r >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return ".png";
        if (r >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return ".gif";
        if (r >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return ".webp";
        return ".jpg";
    }

    private android.graphics.Bitmap loadImageDirect(String urlStr) {
        try {
            return loadImageSampled(urlStr, 1280, 1280);
        } catch (Exception e) {
            return null;
        }
    }

    /** 图集翻页适配器：每页一个可缩放图片视图 */
    private class ImagePagerAdapter extends PagerAdapter {
        final java.util.List<String> urls;
        final boolean zoomEnabled;
        final java.lang.Runnable onTap;
        final java.lang.Runnable onLongPress;

        ImagePagerAdapter(java.util.List<String> urls, boolean zoomEnabled,
                          java.lang.Runnable onTap, java.lang.Runnable onLongPress) {
            this.urls = urls;
            this.zoomEnabled = zoomEnabled;
            this.onTap = onTap;
            this.onLongPress = onLongPress;
        }

        @Override
        public int getCount() {
            return urls == null ? 0 : urls.size();
        }

        @Override
        public boolean isViewFromObject(android.view.View view, Object object) {
            return view == object;
        }

        @Override
        public Object instantiateItem(android.view.ViewGroup container, int position) {
            ZoomableImageView page = new ZoomableImageView(ParseResultActivity.this, onTap, onLongPress, zoomEnabled);
            page.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            container.addView(page);
            loadBitmapInto(urls.get(position), page);
            return page;
        }

        @Override
        public void destroyItem(android.view.ViewGroup container, int position, Object object) {
            container.removeView((android.view.View) object);
        }
    }

    /** 实况图分页适配器：每页一个 VideoView 自动循环播放动态图视频 */
    private class LivePagerAdapter extends PagerAdapter {
        private final VideoInfo info;
        private final Runnable onSave;

        LivePagerAdapter(VideoInfo info, Runnable onSave) {
            this.info = info;
            this.onSave = onSave;
        }

        @Override
        public int getCount() {
            return info.livePhotos != null ? info.livePhotos.size() : 0;
        }

        @Override
        public boolean isViewFromObject(View v, Object o) { return v == o; }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            VideoInfo.LivePhotoItem item = info.livePhotos.get(position);

            FrameLayout frame = new FrameLayout(ParseResultActivity.this);
            frame.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // 静态预览图作为占位
            ImageView preview = new ImageView(ParseResultActivity.this);
            preview.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            frame.addView(preview);

            // 异步加载预览图
            final String imgUrl = item.imageUrl;
            executor.execute(() -> {
                try {
                    android.graphics.Bitmap bmp = loadImageSampled(imgUrl, 720, 1280);
                    if (bmp != null) {
                        mainHandler.post(() -> preview.setImageBitmap(bmp));
                    }
                } catch (Exception ignored) {}
            });

            // 动态视频播放器
            VideoView vv = new VideoView(ParseResultActivity.this);
            FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);
            vv.setLayoutParams(vp);
            vv.setZOrderOnTop(true);

            try {
                if (item.videoUrl != null && !item.videoUrl.isEmpty()) {
                    vv.setVideoURI(Uri.parse(item.videoUrl));
                }
            } catch (Exception ignored) {}

            vv.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                preview.setVisibility(View.GONE);  // 视频就绪，隐藏预览图
                vv.start();
            });

            vv.setOnErrorListener((mp, what, extra) -> true);

            frame.addView(vv);

            // 点击保存当前动态图
            frame.setOnLongClickListener(v -> {
                if (onSave != null) onSave.run();
                return true;
            });

            container.addView(frame);
            return frame;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            FrameLayout frame = (FrameLayout) object;
            for (int i = 0; i < frame.getChildCount(); i++) {
                View child = frame.getChildAt(i);
                if (child instanceof VideoView) {
                    ((VideoView) child).stopPlayback();
                }
            }
            container.removeView(frame);
        }
    }


    /** 可缩放图片视图：双指缩放 + 拖拽平移；未缩放时由父容器（ViewPager）处理左右滑动 */
    public static class ZoomableImageView extends androidx.appcompat.widget.AppCompatImageView {
        private static final float MAX_SCALE = 5f;
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private float currentScale = 1f;
        private boolean zoomEnabled;
        private boolean scaling;
        private float lastX, lastY;
        private final android.view.ScaleGestureDetector scaleDetector;
        private final java.lang.Runnable onSingleTap;
        private final java.lang.Runnable onLongPress;

        // 手动手势状态：长按/点击/移动取消，避免误触发
        private final android.os.Handler gHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final float touchSlop;
        private java.lang.Runnable pendingLongPress;
        private boolean longPressTriggered;
        private boolean potentialTap;
        private float downX, downY;

        public ZoomableImageView(android.content.Context context,
                                 java.lang.Runnable onSingleTap,
                                 java.lang.Runnable onLongPress,
                                 boolean zoomEnabled) {
            super(context);
            this.onSingleTap = onSingleTap;
            this.onLongPress = onLongPress;
            this.zoomEnabled = zoomEnabled;
            touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
            setScaleType(ScaleType.MATRIX);

            scaleDetector = new android.view.ScaleGestureDetector(context,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                            scaling = true;
                            cancelOwnLongPress();
                            potentialTap = false;
                            return true;
                        }

                        @Override
                        public boolean onScale(android.view.ScaleGestureDetector d) {
                            float factor = d.getScaleFactor();
                            float next = currentScale * factor;
                            if (next < 1f) factor = 1f / currentScale;
                            else if (next > MAX_SCALE) factor = MAX_SCALE / currentScale;
                            currentScale *= factor;
                            matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                            setImageMatrix(matrix);
                            return true;
                        }

                        @Override
                        public void onScaleEnd(android.view.ScaleGestureDetector d) {
                            scaling = false;
                            clamp();
                        }
                    });
        }

        private void cancelOwnLongPress() {
            if (pendingLongPress != null) {
                gHandler.removeCallbacks(pendingLongPress);
                pendingLongPress = null;
            }
        }

        @Override
        public void setImageBitmap(android.graphics.Bitmap bm) {
            super.setImageBitmap(bm);
            fitCenter();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            fitCenter();
        }

        private void fitCenter() {
            if (getWidth() == 0 || getHeight() == 0) {
                post(this::fitCenter);
                return;
            }
            android.graphics.Bitmap bmp = getBitmap();
            if (bmp == null || bmp.isRecycled()) return;
            int vw = getWidth(), vh = getHeight();
            int iw = bmp.getWidth(), ih = bmp.getHeight();
            if (iw == 0 || ih == 0) return;
            float s = Math.min((float) vw / iw, (float) vh / ih);
            if (s <= 0) s = 1f;
            matrix.reset();
            matrix.postScale(s, s);
            matrix.postTranslate((vw - iw * s) / 2f, (vh - ih * s) / 2f);
            currentScale = 1f;
            setImageMatrix(matrix);
        }

        private android.graphics.Bitmap getBitmap() {
            android.graphics.drawable.Drawable d = getDrawable();
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            }
            return null;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (zoomEnabled) {
                scaleDetector.onTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    downX = event.getX();
                    downY = event.getY();
                    potentialTap = true;
                    longPressTriggered = false;
                    scheduleLongPress();
                    // 始终先消费 DOWN，保证后续能收到 MOVE/UP/CANCEL，从而正确区分点击/长按/滑动
                    return true;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    // 双指开始缩放：取消点击/长按判定
                    cancelOwnLongPress();
                    potentialTap = false;
                    return true;
                case android.view.MotionEvent.ACTION_POINTER_UP:
                    // 同步坐标基准，避免缩放结束后的首次拖动出现跳变
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (scaling) {
                        // 双指缩放中：拦截父容器，避免误触发翻页
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    if (potentialTap && !longPressTriggered
                            && (Math.abs(event.getX() - downX) > touchSlop
                            || Math.abs(event.getY() - downY) > touchSlop)) {
                        potentialTap = false;
                        cancelOwnLongPress();
                    }
                    if (zoomEnabled && currentScale > 1.01f) {
                        // 已放大：拖拽平移，拦截父容器翻页
                        matrix.postTranslate(event.getX() - lastX, event.getY() - lastY);
                        setImageMatrix(matrix);
                        getParent().requestDisallowInterceptTouchEvent(true);
                        lastX = event.getX();
                        lastY = event.getY();
                        return true;
                    }
                    // 未放大：放开父容器，让其处理左右滑动切换
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (potentialTap && !longPressTriggered) {
                        if (onSingleTap != null) onSingleTap.run();
                    }
                    cancelOwnLongPress();
                    potentialTap = false;
                    longPressTriggered = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (zoomEnabled && currentScale <= 1.01f) currentScale = 1f;
                    clamp();
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    cancelOwnLongPress();
                    potentialTap = false;
                    longPressTriggered = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    clamp();
                    return true;
            }
            return true;
        }

        private void scheduleLongPress() {
            cancelOwnLongPress();
            pendingLongPress = () -> {
                if (potentialTap && !longPressTriggered) {
                    longPressTriggered = true;
                    potentialTap = false;
                    if (onLongPress != null) onLongPress.run();
                }
            };
            gHandler.postDelayed(pendingLongPress, 500);
        }

        private void clamp() {
            android.graphics.Bitmap bmp = getBitmap();
            if (bmp == null || bmp.isRecycled() || getWidth() == 0) return;
            float[] vals = new float[9];
            matrix.getValues(vals);
            float scale = vals[android.graphics.Matrix.MSCALE_X];
            float tx = vals[android.graphics.Matrix.MTRANS_X];
            float ty = vals[android.graphics.Matrix.MTRANS_Y];
            int vw = getWidth(), vh = getHeight();
            int iw = bmp.getWidth(), ih = bmp.getHeight();
            float sw = iw * scale, sh = ih * scale;
            float baseTx = (vw - sw) / 2f;
            float baseTy = (vh - sh) / 2f;
            float maxTx = Math.max(0, (sw - vw) / 2f);
            float maxTy = Math.max(0, (sh - vh) / 2f);
            vals[android.graphics.Matrix.MTRANS_X] = Math.max(baseTx - maxTx, Math.min(baseTx + maxTx, tx));
            vals[android.graphics.Matrix.MTRANS_Y] = Math.max(baseTy - maxTy, Math.min(baseTy + maxTy, ty));
            matrix.setValues(vals);
            setImageMatrix(matrix);
        }
    }

    // ── 清晰度选择（底部弹层） ────────────────────────────────

    private void setupQualitySpinner(VideoInfo info) {
        if (info.qualityOptions == null || info.qualityOptions.size() <= 1) {
            layoutQuality.setVisibility(View.GONE);
            return;
        }

        qualityLabels = buildQualityLabels(info);
        currentQualityIndex = 0;
        info.videoUrl = info.qualityOptions.get(0);
        spinnerQuality.setText("👉 " + qualityLabels.get(0) + " (当前选择)");
        layoutQuality.setVisibility(View.VISIBLE);
    }

    private List<String> buildQualityLabels(VideoInfo info) {
        List<String> labels = new ArrayList<>();
        if (info.qualityOptions == null) return labels;
        for (int i = 0; i < info.qualityOptions.size(); i++) {
            labels.add(resolutionLabel(info.qualityOptions.get(i), i));
        }
        return labels;
    }

    /**
     * 根据地址推断清晰度标签，统一显示对应分辨率（4K / 1080P / 720P / 540P …）。
     * 地址不含分辨率的，按常见中文清晰度词映射，最后再按档位兜底。
     */
    private String resolutionLabel(String url, int index) {
        String u = (url == null ? "" : url).toLowerCase();
        // 从高到低判断，避免低档位先命中
        if (u.contains("4k") || u.contains("2160") || u.contains("3840") || u.contains("uhd")) return "4K";
        if (u.contains("1080") || u.contains("1920") || u.contains("fhd") || u.contains("fullhd")) return "1080P";
        if (u.contains("720") || u.contains("1280")) return "720P";
        if (u.contains("960") || u.contains("540")) return "540P";
        if (u.contains("480") || u.contains("640")) return "480P";
        if (u.contains("360")) return "360P";
        if (u.contains("240")) return "240P";
        if (u.contains("144")) return "144P";
        // 部分平台用中文清晰度命名，映射为标准分辨率
        if (u.contains("原画")) return "原画";
        if (u.contains("超清")) return "1080P";
        if (u.contains("高清")) return "720P";
        if (u.contains("标清")) return "480P";
        if (u.contains("流畅")) return "360P";
        // 地址完全无法判断时，按清晰度档位命名（越靠前越清晰）
        if (index == 0) return "超清";
        if (index == 1) return "高清";
        if (index == 2) return "标清";
        return "流畅";
    }

    /** 弹出清晰度底部选择层：从底部滑入，文本垂直居中，选中项前加 👉 */
    private void showQualityDialog() {
        if (currentVideo == null || qualityLabels.isEmpty()) return;

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = getLayoutInflater().inflate(R.layout.dialog_quality_sheet, null);
        LinearLayout list = sheet.findViewById(R.id.qualityList);
        list.removeAllViews();

        int itemH = (int) (52 * getResources().getDisplayMetrics().density);
        int primaryColor = 0xFF1A73E8;
        int normalColor = 0xFF1C1E21;
        int dividerColor = 0xFFE4E6EB;

        for (int i = 0; i < qualityLabels.size(); i++) {
            final int pos = i;
            boolean selected = (i == currentQualityIndex);

            TextView item = new TextView(this);
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemH));
            item.setGravity(Gravity.CENTER); // 文本垂直（及水平）居中
            item.setTextSize(15);
            item.setText(selected ? ("👉 " + qualityLabels.get(i) + " (当前选择)") : qualityLabels.get(i));
            item.setTextColor(selected ? primaryColor : normalColor);
            item.setTypeface(android.graphics.Typeface.DEFAULT, selected
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            item.setClickable(true);
            item.setFocusable(true);
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            item.setBackgroundResource(ripple.resourceId);

            item.setOnClickListener(v -> {
                currentQualityIndex = pos;
                currentVideo.videoUrl = currentVideo.qualityOptions.get(pos);
                spinnerQuality.setText("👉 " + qualityLabels.get(pos) + " (当前选择)");
                // 若正在播放，立即切换清晰度
                if (isVideoPlaying && videoView.getVisibility() == View.VISIBLE) {
                    loadVideoInPlayer(currentVideo.videoUrl);
                }
                // 刷新该清晰度对应的真实文件大小
                refreshActualVideoSize(currentVideo);
                dialog.dismiss();
            });
            list.addView(item);

            if (i < qualityLabels.size() - 1) {
                View divider = new View(this);
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(dividerColor);
                list.addView(divider);
            }
        }

        sheet.findViewById(R.id.btnQualityCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(sheet);
        dialog.show();
    }

    // ── 保存视频 ──────────────────────────────────────────────

    private void saveVideoWithProgress() {
        if (currentVideo == null || currentVideo.videoUrl == null || currentVideo.videoUrl.isEmpty()) {
            Toast.makeText(this, "没有可保存的视频", Toast.LENGTH_SHORT).show();
            return;
        }

        layoutSaveProgress.setVisibility(View.VISIBLE);
        progressSave.setProgress(0);
        tvSaveProgress.setText("正在保存… 0%");
        btnSave.setEnabled(false);

        executor.execute(() -> {
            try {
                String fileName = "watermark_" + System.currentTimeMillis() + ".mp4";
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/WatermarkRemover");
                    android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                        downloadFileWithProgress(currentVideo.videoUrl, os);
                        mainHandler.post(() -> {
                            layoutSaveProgress.setVisibility(View.GONE);
                            btnSave.setEnabled(true);
                            Toast.makeText(ParseResultActivity.this, "保存成功", Toast.LENGTH_SHORT).show();
                        });
                    }
                } else {
                    java.io.File dir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_MOVIES), "WatermarkRemover");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File file = new java.io.File(dir, fileName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                    downloadFileWithProgress(currentVideo.videoUrl, fos);
                    mainHandler.post(() -> {
                        layoutSaveProgress.setVisibility(View.GONE);
                        btnSave.setEnabled(true);
                        Toast.makeText(ParseResultActivity.this, "保存成功", Toast.LENGTH_SHORT).show();
                        android.content.Intent mediaScan = new android.content.Intent(
                            android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScan.setData(android.net.Uri.fromFile(file));
                        sendBroadcast(mediaScan);
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    layoutSaveProgress.setVisibility(View.GONE);
                    btnSave.setEnabled(true);
                    Toast.makeText(ParseResultActivity.this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void downloadFileWithProgress(String urlStr, java.io.OutputStream os) throws Exception {
        // 加固：明文 http 一律升级为 https（客户端已全局禁用明文流量）
        urlStr = normalizeUrl(urlStr);
        if (urlStr == null || (!urlStr.startsWith("https://") && !urlStr.startsWith("http://"))) {
            throw new java.io.IOException("非法下载地址");
        }
        java.net.URL url = new java.net.URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.connect();

        long totalSize = conn.getContentLengthLong();
        if (totalSize < 0) totalSize = 10 * 1024 * 1024;

        java.io.InputStream is = conn.getInputStream();
        byte[] buffer = new byte[8192];
        int len;
        long downloaded = 0;
        int lastPercent = 0;

        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
            downloaded += len;
            int percent = (int) (downloaded * 100 / totalSize);
            if (percent > lastPercent && percent <= 100) {
                lastPercent = percent;
                final int p = percent;
                mainHandler.post(() -> {
                    progressSave.setProgress(p);
                    tvSaveProgress.setText("正在保存… " + p + "%");
                });
            }
        }
        os.flush();
        os.close();
        is.close();
        conn.disconnect();
    }

    /** 保存图集/图文的所有图片到系统相册 */
    private void saveImages() {
        if (currentVideo == null || currentVideo.imageUrls == null || currentVideo.imageUrls.isEmpty()) {
            Toast.makeText(this, "没有可保存的图片", Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<String> urls = currentVideo.imageUrls;
        layoutSaveProgress.setVisibility(View.VISIBLE);
        progressSave.setProgress(0);
        btnSave.setEnabled(false);

        executor.execute(() -> {
            int done = 0;
            final int total = urls.size();
            for (int i = 0; i < total; i++) {
                try {
                    String urlStr = urls.get(i);
                    urlStr = normalizeUrl(urlStr);
                    String ext = ".jpg";
                    String lower = urlStr == null ? "" : urlStr.toLowerCase();
                    if (lower.endsWith(".gif")) ext = ".gif";
                    else if (lower.endsWith(".png")) ext = ".png";
                    else if (lower.endsWith(".webp")) ext = ".webp";
                    String fileName = "watermark_image_" + System.currentTimeMillis() + "_" + i + ext;

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
                        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/" + (ext.substring(1)));
                        values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatermarkRemover");
                        android.net.Uri uri = getContentResolver().insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (uri != null) {
                            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                            streamTo(urlStr, os);
                        }
                    } else {
                        java.io.File dir = new java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_PICTURES), "WatermarkRemover");
                        if (!dir.exists()) dir.mkdirs();
                        java.io.File file = new java.io.File(dir, fileName);
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                        streamTo(urlStr, fos);
                        sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                            android.net.Uri.fromFile(file)));
                    }
                    done++;
                } catch (Exception ignored) {}
                final int p = (int) ((i + 1) * 100f / total);
                final int d = done;
                mainHandler.post(() -> {
                    progressSave.setProgress(p);
                    tvSaveProgress.setText("正在保存图片 " + d + "/" + total + "… " + p + "%");
                });
            }

            final int saved = done;
            mainHandler.post(() -> {
                layoutSaveProgress.setVisibility(View.GONE);
                btnSave.setEnabled(true);
                Toast.makeText(ParseResultActivity.this, saved > 0 ? ("已保存 " + saved + " 张图片") : "图片保存失败", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void streamTo(String urlStr, java.io.OutputStream os) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.connect();
        java.io.InputStream is = conn.getInputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
        }
        os.flush();
        os.close();
        is.close();
        conn.disconnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (videoView != null) {
            try {
                videoView.removeAllViews();
                videoView.destroy();
            } catch (Exception ignored) {}
        }
    }
}
