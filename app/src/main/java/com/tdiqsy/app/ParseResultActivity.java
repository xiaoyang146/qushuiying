package com.tdiqsy.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ParseResultActivity extends AppCompatActivity {

    private VideoInfo currentVideo;
    private String currentSourceUrl;

    private ImageView ivCover;
    private WebView videoView;
    private FrameLayout btnPlay;
    private ProgressBar progressBuffering;
    private SeekBar seekBar;
    private ImageView btnFullscreen;
    private TextView tvTime;
    private TextView tvVideoInfo;
    private TextView tvPlatform;
    private TextView tvAuthor;
    private TextView tvTitle;
    private TextView spinnerQuality;
    private LinearLayout layoutQuality;
    private LinearLayout layoutSaveProgress;
    private TextView tvSaveProgress;
    private ProgressBar progressSave;
    private TextView btnSave;
    private TextView btnCopyLink;
    private ViewPager imagePager;
    private HorizontalScrollView imageScroll;
    private LinearLayout imageStrip;

    private boolean isVideoPlaying = false;
    private boolean isUserSeeking = false;
    private int currentQualityIdx = 0;
    private int savedSeekPos = 0;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HistoryDbHelper dbHelper;

    // ========== LIFECYCLE ==========

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parse_result);

        dbHelper = new HistoryDbHelper(this);

        // 绑定视图
        ivCover = findViewById(R.id.ivCover);
        videoView = findViewById(R.id.videoView);
        btnPlay = findViewById(R.id.btnPlay);
        progressBuffering = findViewById(R.id.progressBuffering);
        seekBar = findViewById(R.id.seekBar);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        tvTime = findViewById(R.id.tvTime);
        tvVideoInfo = findViewById(R.id.tvVideoInfo);
        tvPlatform = findViewById(R.id.tvPlatform);
        tvAuthor = findViewById(R.id.tvAuthor);
        tvTitle = findViewById(R.id.tvTitle);
        spinnerQuality = findViewById(R.id.spinnerQuality);
        layoutQuality = findViewById(R.id.layoutQuality);
        layoutSaveProgress = findViewById(R.id.layoutSaveProgress);
        tvSaveProgress = findViewById(R.id.tvSaveProgress);
        progressSave = findViewById(R.id.progressSave);
        btnSave = findViewById(R.id.btnSave);
        btnCopyLink = findViewById(R.id.btnCopyLink);
        imagePager = findViewById(R.id.imagePager);
        imageScroll = findViewById(R.id.imageScroll);
        imageStrip = findViewById(R.id.imageStrip);

        // 返回按钮
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // WebView 初始化
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
                if (!"about:blank".equals(url)) {
                    progressBuffering.setVisibility(View.GONE);
                    btnPlay.setVisibility(View.GONE);
                }
            }
        });

        videoView.addJavascriptInterface(new JsApi(), "Android");

        // 播放按钮
        btnPlay.setOnClickListener(v -> playVideo());

        // 全屏按钮
        btnFullscreen.setOnClickListener(v -> openFullscreen());

        // SeekBar
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) tvTime.setText(formatTime((long) progress * getVideoDuration() / 1000));
            }
            @Override
            public void onStartTrackingTouch(SeekBar s) { isUserSeeking = true; }
            @Override
            public void onStopTrackingTouch(SeekBar s) {
                isUserSeeking = false;
                seekTo(s.getProgress() * getVideoDuration() / 1000);
            }
        });

        // 保存按钮
        btnSave.setOnClickListener(v -> saveVideo());

        // 复制链接
        btnCopyLink.setOnClickListener(v -> copyLink());

        // 清晰度选择
        spinnerQuality.setOnClickListener(v -> showQualityDialog());

        // 接收 VideoInfo
        currentVideo = (VideoInfo) getIntent().getSerializableExtra("videoInfo");
        currentSourceUrl = getIntent().getStringExtra("sourceUrl");

        if (currentVideo != null) {
            showResult(currentVideo);
            dbHelper.insert(currentVideo, currentSourceUrl);
        } else {
            Toast.makeText(this, "解析数据获取失败", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseVideo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pauseVideo();
        executor.shutdownNow();
        if (dbHelper != null) dbHelper.close();
    }

    // ========== RESULT DISPLAY ==========

    private void showResult(VideoInfo info) {
        tvPlatform.setText(info.platformName);
        tvAuthor.setText(info.author);
        tvTitle.setText(info.title);

        pauseVideo();

        if (info.isImage()) {
            imageScroll.setVisibility(View.VISIBLE);
            imageStrip.removeAllViews();
            loadImageStrip(info);
            btnSave.setText("长按图片保存");
            layoutQuality.setVisibility(View.GONE);
            ivCover.setVisibility(View.GONE);
            videoView.setVisibility(View.GONE);
            btnPlay.setVisibility(View.GONE);
            tvVideoInfo.setVisibility(View.GONE);
            setupImagePager(info);
            return;
        }

        if (info.isLive()) {
            ivCover.setVisibility(View.GONE);
            btnPlay.setVisibility(View.GONE);
            videoView.setVisibility(View.GONE);
            imageScroll.setVisibility(View.VISIBLE);
            imageStrip.removeAllViews();
            loadImageStrip(info);
            btnSave.setText("长按图片保存");
            layoutQuality.setVisibility(View.GONE);
            tvVideoInfo.setVisibility(View.GONE);
            setupLivePager(info);
            return;
        }

        // 视频内容
        imageScroll.setVisibility(View.GONE);
        imageStrip.removeAllViews();
        imagePager.setVisibility(View.GONE);
        btnSave.setText("保存视频");

        loadCover(info);

        ivCover.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.GONE);
        btnPlay.setVisibility(View.VISIBLE);
        isVideoPlaying = false;

        updateVideoInfoLabel();
        setupQualitySpinner(info);
        refreshActualVideoSize(info);
    }

    private void loadCover(VideoInfo info) {
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
    }

    private void updateVideoInfoLabel() {
        if (currentVideo == null) return;
        StringBuilder sb = new StringBuilder();
        if (currentVideo.format != null && !currentVideo.format.isEmpty())
            sb.append(currentVideo.format.toUpperCase());
        if (currentVideo.duration != null && !currentVideo.duration.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(currentVideo.duration);
        }
        if (currentVideo.sizeMb != null && !currentVideo.sizeMb.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(currentVideo.sizeMb);
        }
        if (sb.length() > 0) {
            tvVideoInfo.setText(sb.toString());
            tvVideoInfo.setVisibility(View.VISIBLE);
        } else {
            tvVideoInfo.setVisibility(View.GONE);
        }
    }

    private void setupQualitySpinner(VideoInfo info) {
        if (info.qualityOptions != null && info.qualityOptions.size() > 1) {
            layoutQuality.setVisibility(View.VISIBLE);
            spinnerQuality.setText(info.qualityOptions.get(0));
            currentQualityIdx = 0;
        } else {
            layoutQuality.setVisibility(View.GONE);
        }
    }

    private void showQualityDialog() {
        if (currentVideo == null || currentVideo.qualityOptions == null || currentVideo.qualityOptions.size() <= 1)
            return;
        String[] options = currentVideo.qualityOptions.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择视频源")
                .setSingleChoiceItems(options, currentQualityIdx, (dialog, which) -> {
                    currentQualityIdx = which;
                    spinnerQuality.setText(options[which]);
                    String url = currentVideo.qualityOptions.get(which);
                    currentVideo.videoUrl = url;
                    if (isVideoPlaying) {
                        savedSeekPos = getVideoCurrentPosition();
                    }
                    pauseVideo();
                    btnPlay.setVisibility(View.GONE);
                    updateVideoInfoLabel();
                    refreshActualVideoSize(currentVideo);
                    dialog.dismiss();
                })
                .show();
    }

    private void refreshActualVideoSize(final VideoInfo info) {
        final String targetUrl = info.videoUrl;
        if (targetUrl == null || targetUrl.isEmpty()) return;
        executor.execute(() -> {
            final long size = getContentLength(targetUrl);
            if (size <= 0) return;
            mainHandler.post(() -> {
                if (currentVideo == info && targetUrl.equals(currentVideo.videoUrl)) {
                    currentVideo.sizeMb = formatBytes(size);
                    updateVideoInfoLabel();
                }
            });
        });
    }

    // ========== VIDEO PLAYBACK ==========

    private void playVideo() {
        if (currentVideo == null || currentVideo.videoUrl == null || currentVideo.videoUrl.isEmpty()) {
            Toast.makeText(this, "没有可播放的视频地址", Toast.LENGTH_SHORT).show();
            return;
        }
        ivCover.setVisibility(View.GONE);
        videoView.setVisibility(View.VISIBLE);
        btnPlay.setVisibility(View.GONE);
        progressBuffering.setVisibility(View.VISIBLE);

        String html = buildPlayerHtml(currentVideo.videoUrl);
        videoView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        isVideoPlaying = true;

        seekBar.setVisibility(View.VISIBLE);
        btnFullscreen.setVisibility(View.VISIBLE);
        tvTime.setVisibility(View.VISIBLE);
    }

    private void pauseVideo() {
        if (videoView != null) {
            videoView.loadUrl("about:blank");
            videoView.setVisibility(View.GONE);
        }
        isVideoPlaying = false;
        ivCover.setVisibility(View.VISIBLE);
        btnPlay.setVisibility(View.VISIBLE);
        progressBuffering.setVisibility(View.GONE);
        seekBar.setVisibility(View.GONE);
        btnFullscreen.setVisibility(View.GONE);
        tvTime.setVisibility(View.GONE);
    }

    private int getVideoDuration() { return 1000; }
    private int getVideoCurrentPosition() { return 0; }

    private void seekTo(int millis) {
        // JS seekTo
        videoView.loadUrl("javascript:seekTo(" + millis + ")");
    }

    private String buildPlayerHtml(String videoUrl) {
        String normalizedUrl = normalizeUrl(videoUrl);
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">" +
                "<style>body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh;overflow:hidden}" +
                "video{width:100%;height:100%;object-fit:contain;background:#000}</style></head><body>" +
                "<video id=\"v\" src=\"" + escapeHtml(normalizedUrl) + "\" controls playsinline autoplay" +
                " ontimeupdate=\"Android.onTimeUpdate(this.currentTime,this.duration)\"" +
                " onwaiting=\"Android.onBuffering(true)\" onplaying=\"Android.onBuffering(false)\"" +
                " onended=\"Android.onEnded()\"></video>" +
                "<script>" +
                "var v=document.getElementById('v');" +
                "function seekTo(t){v.currentTime=t/1000;v.play();}" +
                "</script></body></html>";
    }

    // ========== SAVE / COPY ==========

    private void saveVideo() {
        if (currentVideo == null) { Toast.makeText(this, "没有可保存的内容", Toast.LENGTH_SHORT).show(); return; }
        if (currentVideo.isImage() || currentVideo.isLive()) {
            Toast.makeText(this, "请长按图片保存", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = currentVideo.videoUrl;
        if (url == null || url.isEmpty()) { Toast.makeText(this, "没有可保存的地址", Toast.LENGTH_SHORT).show(); return; }

        if (!checkWritePermission()) {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
            return;
        }

        layoutSaveProgress.setVisibility(View.VISIBLE);
        tvSaveProgress.setText("正在保存… 0%");
        progressSave.setProgress(0);

        executor.execute(() -> {
            try {
                String fileName = sanitizeFileName(currentVideo.title, currentVideo.platform)
                        + "." + (currentVideo.format != null ? currentVideo.format : "mp4");
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File outFile = new File(dir, fileName);

                downloadFile(url, outFile, (pct) -> {
                    mainHandler.post(() -> {
                        tvSaveProgress.setText("正在保存… " + pct + "%");
                        progressSave.setProgress(pct);
                    });
                });

                mainHandler.post(() -> {
                    layoutSaveProgress.setVisibility(View.GONE);
                    sendMediaScan(outFile);
                    Toast.makeText(this, "已保存到 " + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    layoutSaveProgress.setVisibility(View.GONE);
                    Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void copyLink() {
        if (currentVideo == null || currentVideo.videoUrl == null || currentVideo.videoUrl.isEmpty()) {
            Toast.makeText(this, "没有可复制的链接", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("video_url", currentVideo.videoUrl));
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
    }

    // ========== IMAGE GALLERY ==========

    private void loadImageStrip(VideoInfo info) {
        imageStrip.removeAllViews();
        List<String> urls = getDisplayUrls(info);
        if (urls.isEmpty()) { imageScroll.setVisibility(View.GONE); return; }
        imageScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < urls.size(); i++) {
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (72 * getResources().getDisplayMetrics().density),
                    ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd((int) (8 * getResources().getDisplayMetrics().density));
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF333333);
            Glide.with(this).load(urls.get(i)).diskCacheStrategy(DiskCacheStrategy.ALL).into(iv);
            final int idx = i;
            iv.setOnClickListener(v -> { if (imagePager != null) imagePager.setCurrentItem(idx, false); });
            imageStrip.addView(iv);
        }
    }

    private List<String> getDisplayUrls(VideoInfo info) {
        List<String> out = new ArrayList<>();
        if (info.imageUrls != null) out.addAll(info.imageUrls);
        if (info.livePhotos != null) {
            for (VideoInfo.LivePhotoItem lp : info.livePhotos) {
                if (lp.imageUrl != null && !out.contains(lp.imageUrl)) out.add(lp.imageUrl);
            }
        }
        if (out.isEmpty() && info.cover != null) out.add(info.cover);
        return out;
    }

    private void setupImagePager(VideoInfo info) {
        List<String> urls = getDisplayUrls(info);
        if (urls.isEmpty()) { imagePager.setVisibility(View.GONE); return; }
        imagePager.setVisibility(View.VISIBLE);
        imagePager.setAdapter(new ImagePagerAdapter(urls));
        imagePager.setOffscreenPageLimit(2);
    }

    private void setupLivePager(VideoInfo info) {
        if (info.livePhotos == null || info.livePhotos.isEmpty()) {
            imagePager.setVisibility(View.GONE);
            return;
        }
        imagePager.setClipChildren(true);
        imagePager.setClipToPadding(true);
        imagePager.setAdapter(new LivePagerAdapter(info));
        imagePager.setOffscreenPageLimit(1);
        imagePager.setVisibility(View.VISIBLE);

        new Handler().postDelayed(() -> {
            if (imagePager.getAdapter() != null && imagePager.getChildCount() > 0) {
                View v = imagePager.getChildAt(0);
                if (v instanceof FrameLayout) startLiveVideo((FrameLayout) v, 0, info.livePhotos);
            }
        }, 300);

        imagePager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                int childCount = imagePager.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    View v = imagePager.getChildAt(i);
                    if (v instanceof FrameLayout) {
                        if (i == position) startLiveVideo((FrameLayout) v, i, info.livePhotos);
                        else stopLiveVideo((FrameLayout) v);
                    }
                }
            }
        });
    }

    private void startLiveVideo(FrameLayout container, int idx, List<VideoInfo.LivePhotoItem> photos) {
        if (idx < 0 || idx >= photos.size()) return;
        String url = photos.get(idx).videoUrl;
        if (url == null || url.isEmpty()) return;
        WebView wv = container.findViewWithTag("live_wv");
        if (wv == null) {
            wv = new WebView(this);
            wv.setTag("live_wv");
            wv.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setAllowFileAccess(true);
            wv.setBackgroundColor(0xFF000000);
            container.addView(wv, 0);
        }
        wv.setVisibility(View.VISIBLE);
        String html = buildPlayerHtml(url);
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void stopLiveVideo(FrameLayout container) {
        WebView wv = container.findViewWithTag("live_wv");
        if (wv != null) {
            wv.loadUrl("about:blank");
            wv.setVisibility(View.GONE);
        }
    }

    // ========== DOWNLOAD ==========

    interface ProgressCallback { void onProgress(int pct); }

    private void downloadFile(String urlStr, File outFile, ProgressCallback cb) throws Exception {
        urlStr = normalizeUrl(urlStr);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        long totalSize = conn.getContentLengthLong();
        conn.connect();

        try (InputStream is = conn.getInputStream();
             OutputStream os = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            long downloaded = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                os.write(buf, 0, n);
                downloaded += n;
                if (totalSize > 0 && cb != null) {
                    cb.onProgress((int) (downloaded * 100 / totalSize));
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void sendMediaScan(File file) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(file));
        sendBroadcast(intent);
    }

    private boolean checkWritePermission() {
        return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
    }

    // ========== UTILITY ==========

    private String normalizeUrl(String url) {
        if (url == null) return "";
        return url.replace("http://", "https://");
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String sanitizeFileName(String title, String platform) {
        String name = (title != null && !title.isEmpty()) ? title : "video";
        if (platform != null && !platform.isEmpty()) name = platform + "_" + name;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }

    private String formatTime(long seconds) {
        long min = seconds / 60;
        long sec = seconds % 60;
        return String.format("%02d:%02d", min, sec);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024));
    }

    private long getContentLength(String urlStr) {
        urlStr = normalizeUrl(urlStr);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.connect();
            long len = conn.getContentLengthLong();
            conn.disconnect();
            if (len > 0) return len;

            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.connect();
            len = conn.getContentLengthLong();
            conn.disconnect();
            return Math.max(len, 0);
        } catch (Exception e) {
            return -1;
        }
    }

    private android.graphics.Bitmap loadImageSampled(String urlStr, int maxW, int maxH) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.connect();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(conn.getInputStream(), null, opts);
            conn.disconnect();

            int sampleSize = 1;
            while (opts.outWidth / sampleSize > maxW || opts.outHeight / sampleSize > maxH) sampleSize *= 2;

            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            return android.graphics.BitmapFactory.decodeStream(conn.getInputStream(), null, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private void openFullscreen() {
        if (currentVideo == null) return;
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra("videoUrl", currentVideo.videoUrl);
        intent.putExtra("title", currentVideo.title);
        startActivity(intent);
    }

    // ========== JS API ==========

    class JsApi {
        @JavascriptInterface
        public void onTimeUpdate(double current, double duration) {
            mainHandler.post(() -> {
                if (!isUserSeeking && duration > 0) {
                    seekBar.setProgress((int) (current * 1000 / duration));
                }
                tvTime.setText(formatTime((long) current) + " / " + formatTime((long) duration));
            });
        }

        @JavascriptInterface
        public void onBuffering(boolean buffering) {
            mainHandler.post(() -> {
                progressBuffering.setVisibility(buffering ? View.VISIBLE : View.GONE);
            });
        }

        @JavascriptInterface
        public void onEnded() {
            mainHandler.post(() -> {
                pauseVideo();
                ivCover.setVisibility(View.VISIBLE);
                btnPlay.setVisibility(View.VISIBLE);
            });
        }
    }

    // ========== ADAPTERS ==========

    class ImagePagerAdapter extends PagerAdapter {
        private final List<String> urls;

        ImagePagerAdapter(List<String> urls) { this.urls = urls; }

        @Override
        public int getCount() { return urls.size(); }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) { return view == object; }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            FrameLayout fl = new FrameLayout(ParseResultActivity.this);
            fl.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fl.setClipChildren(true);

            final TouchImageView iv = new TouchImageView(ParseResultActivity.this);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF000000);

            Glide.with(ParseResultActivity.this)
                    .load(urls.get(position))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(iv);

            iv.setOnLongClickListener(v -> {
                new AlertDialog.Builder(ParseResultActivity.this)
                        .setTitle("保存图片")
                        .setMessage("是否保存此图片到相册？")
                        .setPositiveButton("保存", (d, w) -> saveSingleImage(urls.get(position)))
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });

            fl.addView(iv);
            container.addView(fl);
            return fl;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }
    }

    class LivePagerAdapter extends PagerAdapter {
        private final VideoInfo info;

        LivePagerAdapter(VideoInfo info) { this.info = info; }

        @Override
        public int getCount() { return info.livePhotos != null ? info.livePhotos.size() : 0; }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) { return view == object; }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            FrameLayout fl = new FrameLayout(ParseResultActivity.this);
            fl.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fl.setClipChildren(true);
            container.addView(fl);
            return fl;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }
    }

    /** 可缩放 ImageView */
    class TouchImageView extends androidx.appcompat.widget.AppCompatImageView {
        private float lastDist = 0;
        private float lastX = 0, lastY = 0;
        private float scale = 1f;
        private boolean potentialTap = false;
        private boolean longPressTriggered = false;
        private final Handler longPressHandler = new Handler(Looper.getMainLooper());
        private Runnable longPressRunnable;

        TouchImageView(android.content.Context context) {
            super(context);
            setClickable(true);
            setOnClickListener(v -> {
                // 点击放大
                android.content.Intent i = new android.content.Intent();
                i.setAction(android.content.Intent.ACTION_VIEW);
                // 不做具体放大，仅示意
            });
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            int count = event.getPointerCount();
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    potentialTap = true;
                    longPressTriggered = false;
                    cancelLongPress();
                    longPressRunnable = () -> {
                        longPressTriggered = true;
                        potentialTap = false;
                        performLongClick();
                    };
                    longPressHandler.postDelayed(longPressRunnable, 500);
                    break;

                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    potentialTap = false;
                    cancelLongPress();
                    if (count == 2) {
                        lastDist = spacing(event);
                    }
                    break;

                case android.view.MotionEvent.ACTION_MOVE:
                    if (count == 2) {
                        float newDist = spacing(event);
                        if (lastDist > 10 && Math.abs(newDist - lastDist) > 5) {
                            scale *= newDist / lastDist;
                            scale = Math.max(0.5f, Math.min(5f, scale));
                            setScaleX(scale);
                            setScaleY(scale);
                            lastDist = newDist;
                            potentialTap = false;
                            cancelLongPress();
                        }
                    } else if (count == 1) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            if (scale > 1f) {
                                setTranslationX(getTranslationX() + dx);
                                setTranslationY(getTranslationY() + dy);
                            }
                            lastX = event.getX();
                            lastY = event.getY();
                            potentialTap = false;
                            cancelLongPress();
                        }
                    }
                    break;

                case android.view.MotionEvent.ACTION_UP:
                    if (potentialTap && !longPressTriggered) {
                        performClick();
                    }
                    cancelLongPress();
                    potentialTap = false;
                    longPressTriggered = false;
                    break;
            }
            return true;
        }

        private float spacing(android.view.MotionEvent event) {
            float x = event.getX(0) - event.getX(1);
            float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(x * x + y * y);
        }

        private void cancelLongPress() {
            if (longPressRunnable != null) {
                longPressHandler.removeCallbacks(longPressRunnable);
            }
        }
    }

    private void saveSingleImage(String urlStr) {
        executor.execute(() -> {
            try {
                String name = "qsy_" + System.currentTimeMillis() + ".jpg";
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File outFile = new File(dir, name);
                downloadFile(urlStr, outFile, null);
                sendMediaScan(outFile);
                mainHandler.post(() -> Toast.makeText(ParseResultActivity.this,
                        "图片已保存", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(ParseResultActivity.this,
                        "保存失败", Toast.LENGTH_SHORT).show());
            }
        });
    }
}