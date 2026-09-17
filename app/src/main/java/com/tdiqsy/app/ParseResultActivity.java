package com.tdiqsy.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.card.MaterialCardView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ParseResultActivity extends AppCompatActivity {

    private MaterialCardView cardResult;
    private ImageView ivCover;
    private TextView tvPlatform;
    private TextView tvAuthor;
    private TextView tvTitle;
    private Button btnSave;
    private Button btnCopyLink;
    private LinearLayout layoutQuality;
    private TextView spinnerQuality;
    private ProgressBar progressBar;
    private LinearLayout layoutSaveProgress;
    private TextView tvSaveProgress;
    private ProgressBar progressSave;

    private ApiClient apiClient;
    private ExecutorService executor;
    private Handler mainHandler;
    private VideoInfo currentVideo;
    private String currentSourceUrl;
    private HistoryDbHelper dbHelper;

    private List<String> qualityLabels = new ArrayList<>();
    private int currentQualityIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parse_result);

        apiClient = new ApiClient();
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        dbHelper = HistoryDbHelper.getInstance(this);

        initViews();
        initListeners();

        String url = getIntent().getStringExtra("url");
        if (url != null && !url.isEmpty()) {
            parseVideo(url);
        } else {
            Toast.makeText(this, "未收到视频链接", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void initViews() {
        cardResult = findViewById(R.id.cardResult);
        ivCover = findViewById(R.id.ivCover);
        tvPlatform = findViewById(R.id.tvPlatform);
        tvAuthor = findViewById(R.id.tvAuthor);
        tvTitle = findViewById(R.id.tvTitle);
        btnSave = findViewById(R.id.btnSave);
        btnCopyLink = findViewById(R.id.btnCopyLink);
        layoutQuality = findViewById(R.id.layoutQuality);
        spinnerQuality = findViewById(R.id.spinnerQuality);
        progressBar = findViewById(R.id.progressBar);
        layoutSaveProgress = findViewById(R.id.layoutSaveProgress);
        tvSaveProgress = findViewById(R.id.tvSaveProgress);
        progressSave = findViewById(R.id.progressSave);
    }

    private void initListeners() {
        btnCopyLink.setOnClickListener(v -> {
            if (currentVideo != null && currentVideo.videoUrl != null) {
                copyToClipboard(currentVideo.videoUrl, "视频链接已复制");
            }
        });

        btnSave.setOnClickListener(v -> {
            if (currentVideo != null && currentVideo.isImage()) {
                // 图集内容：保存第一张
                if (currentVideo.imageUrls != null && !currentVideo.imageUrls.isEmpty()) {
                    saveSingleImage(currentVideo.imageUrls.get(0));
                }
            } else if (currentVideo != null) {
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

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    // ── 解析视频 ──────────────────────────────────────────────

    private void parseVideo(String url) {
        currentSourceUrl = url;
        setLoading(true);
        cardResult.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                VideoInfo info = apiClient.parseVideo(url);
                mainHandler.post(() -> {
                    setLoading(false);
                    showParsedResult(info);
                });
            } catch (ApiClient.ApiException e) {
                mainHandler.post(() -> {
                    setLoading(false);
                    Toast.makeText(ParseResultActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setLoading(false);
                    Toast.makeText(ParseResultActivity.this, "网络错误，请稍后重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showParsedResult(VideoInfo info) {
        currentVideo = info;
        showResult(info);
        dbHelper.insert(info, currentSourceUrl);
    }

    private void showResult(VideoInfo info) {
        cardResult.setVisibility(View.VISIBLE);
        tvPlatform.setText(info.platformName);
        tvAuthor.setText(info.author);
        tvTitle.setText(info.title);

        if (info.isImage()) {
            btnSave.setText("保存图片");
            layoutQuality.setVisibility(View.GONE);
        } else {
            btnSave.setText("保存视频");
        }

        if (info.cover != null && !info.cover.isEmpty()) {
            final String coverUrl = info.cover;
            executor.execute(() -> {
                try {
                    Bitmap bmp = loadImageSampled(coverUrl, 720, 480);
                    if (bmp != null) {
                        mainHandler.post(() -> ivCover.setImageBitmap(bmp));
                    }
                } catch (Exception ignored) {}
            });
        }

        ivCover.setVisibility(View.VISIBLE);
        setupQualitySpinner(info);
        refreshActualVideoSize(info);
    }

    // ── 清晰度 ──────────────────────────────────────────────

    private void setupQualitySpinner(VideoInfo info) {
        qualityLabels.clear();
        currentQualityIndex = 0;
        if (info.qualityItems != null && !info.qualityItems.isEmpty()) {
            for (VideoInfo.QualityItem q : info.qualityItems) {
                qualityLabels.add(q.label);
            }
            spinnerQuality.setText(qualityLabels.get(0));
            layoutQuality.setVisibility(View.VISIBLE);
        } else {
            layoutQuality.setVisibility(View.GONE);
        }
    }

    private void showQualityDialog() {
        if (qualityLabels.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("选择清晰度")
                .setItems(qualityLabels.toArray(new String[0]), (dialog, which) -> {
                    if (currentVideo != null && currentVideo.qualityItems != null && which < currentVideo.qualityItems.size()) {
                        currentQualityIndex = which;
                        spinnerQuality.setText(qualityLabels.get(which));
                        VideoInfo.QualityItem q = currentVideo.qualityItems.get(which);
                        currentVideo.videoUrl = q.url;
                        currentVideo.sizeMb = q.size;
                        currentVideo.format = q.format;
                        refreshActualVideoSize(currentVideo);
                    }
                })
                .show();
    }

    // ── 保存 ──────────────────────────────────────────────

    private void saveVideoWithProgress() {
        if (currentVideo == null || currentVideo.videoUrl == null) return;
        layoutSaveProgress.setVisibility(View.VISIBLE);
        tvSaveProgress.setText("正在保存… 0%");
        progressSave.setProgress(0);
        btnSave.setEnabled(false);

        final String url = currentVideo.videoUrl;
        executor.execute(() -> {
            boolean ok = saveVideoToGallerySync(url);
            mainHandler.post(() -> {
                layoutSaveProgress.setVisibility(View.GONE);
                btnSave.setEnabled(true);
                Toast.makeText(this, ok ? "视频已保存" : "保存失败", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void saveSingleImage(final String url) {
        if (url == null || url.isEmpty()) return;
        btnSave.setEnabled(false);
        btnSave.setText("保存中…");
        executor.execute(() -> {
            final boolean ok = saveSingleImageSync(url);
            mainHandler.post(() -> {
                btnSave.setEnabled(true);
                btnSave.setText("保存图片");
                Toast.makeText(this, ok ? "图片已保存" : "图片保存失败", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private boolean saveVideoToGallerySync(String url) {
        try {
            String urlStr = normalizeUrl(url);
            String fileName = "watermark_" + System.currentTimeMillis() + ".mp4";
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
                sendBroadcast(new Intent(
                        Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.fromFile(file)));
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

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
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.fromFile(file)));
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void streamTo(String urlStr, java.io.OutputStream os) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        java.io.InputStream is = conn.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            os.write(buf, 0, n);
        }
        os.close();
        is.close();
        conn.disconnect();
    }

    // ── 工具方法 ──────────────────────────────────────────────

    private void refreshActualVideoSize(final VideoInfo info) {
        final String targetUrl = info.videoUrl;
        if (targetUrl == null || targetUrl.isEmpty()) return;
        executor.execute(() -> {
            final long size = getContentLength(targetUrl);
            if (size <= 0) return;
            mainHandler.post(() -> {
                if (currentVideo == info && targetUrl.equals(currentVideo.videoUrl)) {
                    currentVideo.sizeMb = formatBytes(size);
                }
            });
        });
    }

    private long getContentLength(String urlStr) {
        urlStr = normalizeUrl(urlStr);
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.connect();
            long len = conn.getContentLengthLong();
            conn.disconnect();
            if (len > 0) return len;

            conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.connect();
            int code = conn.getResponseCode();
            String contentRange = conn.getHeaderField("Content-Range");
            String contentLength = conn.getHeaderField("Content-Length");
            conn.disconnect();
            if (contentRange != null) {
                int slash = contentRange.lastIndexOf('/');
                if (slash >= 0) {
                    try { return Long.parseLong(contentRange.substring(slash + 1).trim()); } catch (Exception ignored) {}
                }
            }
            if (contentLength != null) {
                try { return Long.parseLong(contentLength.trim()); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.1fMB", bytes / (1024.0 * 1024.0));
        } else if (bytes >= 1024) {
            return String.format(java.util.Locale.CHINA, "%.0fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }

    private String normalizeUrl(String url) {
        if (url != null && url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }

    private Bitmap loadImageSampled(String urlStr, int reqW, int reqH) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        java.io.InputStream is = conn.getInputStream();
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, opts);
        is.close();
        conn.disconnect();

        int sample = 1;
        while (opts.outWidth / sample > reqW * 2 || opts.outHeight / sample > reqH * 2) {
            sample *= 2;
        }
        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sample;
        decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;

        java.net.HttpURLConnection conn2 = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn2.setConnectTimeout(15000);
        conn2.setReadTimeout(20000);
        conn2.setRequestProperty("User-Agent", "Mozilla/5.0");
        java.io.InputStream is2 = conn2.getInputStream();
        try {
            return BitmapFactory.decodeStream(is2, null, decodeOpts);
        } finally {
            is2.close();
            conn2.disconnect();
        }
    }

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

    private void copyToClipboard(String text, String toast) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("label", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }
}
