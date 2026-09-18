package com.tdiqsy.app;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoPlayerActivity extends AppCompatActivity {

    private VideoView videoView;
    private TextView tvTitle;
    private ImageView btnBack;
    private ImageView btnDownload;

    // 控制组件
    private ProgressBar progressBuffering;
    private ImageView ivCenterStatus;
    private View controlBar;
    private ImageView btnPlayPause;
    private TextView tvCurrent;
    private SeekBar seekBar;
    private TextView tvTotal;
    private ImageView btnFullscreenToggle;

    private String videoUrl;
    private String videoTitle;
    private ExecutorService executor;
    private Handler mainHandler;

    private boolean isPrepared = false;
    private boolean isPlaying = false;
    private boolean isUserSeeking = false;

    private final Handler controlHandler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsRunnable;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;

    private boolean isFullscreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        videoUrl = getIntent().getStringExtra("video_url");
        videoTitle = getIntent().getStringExtra("video_title");

        initViews();
        initListeners();
        playVideo();
        controlBar.setVisibility(View.VISIBLE);
        ivCenterStatus.setVisibility(View.VISIBLE);
        scheduleHideControls();
    }

    private void initViews() {
        videoView = findViewById(R.id.videoView);
        tvTitle = findViewById(R.id.tvTitle);
        btnBack = findViewById(R.id.btnBack);
        btnDownload = findViewById(R.id.btnDownload);
        progressBuffering = findViewById(R.id.progressBuffering);
        ivCenterStatus = findViewById(R.id.ivCenterStatus);
        controlBar = findViewById(R.id.controlBar);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        tvCurrent = findViewById(R.id.tvCurrent);
        seekBar = findViewById(R.id.seekBar);
        tvTotal = findViewById(R.id.tvTotal);
        btnFullscreenToggle = findViewById(R.id.btnFullscreenToggle);

        if (videoTitle != null && !videoTitle.isEmpty()) {
            tvTitle.setText(videoTitle);
        }
    }

    private void initListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnDownload.setOnClickListener(v -> saveVideo());

        btnPlayPause.setOnClickListener(v -> {
            if (!isPrepared) return;
            if (isPlaying) {
                pause();
            } else {
                resumePlay();
            }
        });

        // 点击画面：切换控制条显示
        videoView.setOnClickListener(v -> toggleControls());
        // 点击居中大图标：播放
        ivCenterStatus.setOnClickListener(v -> resumePlay());

        btnFullscreenToggle.setOnClickListener(v -> {
            if (isFullscreen) exitFullscreen(); else enterFullscreen();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && videoView != null) {
                    int duration = videoView.getDuration();
                    if (duration > 0) {
                        videoView.seekTo(progress * duration / 1000);
                        tvCurrent.setText(formatTime(progress * duration / 1000));
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { isUserSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                isUserSeeking = false;
                scheduleHideControls();
            }
        });
    }

    private void playVideo() {
        if (videoUrl == null || videoUrl.isEmpty()) {
            Toast.makeText(this, "视频链接无效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        progressBuffering.setVisibility(View.VISIBLE);
        try {
            videoView.setVideoURI(Uri.parse(videoUrl));
        } catch (Exception e) {
            progressBuffering.setVisibility(View.GONE);
            Toast.makeText(this, "视频地址异常", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        videoView.setOnPreparedListener(mp -> {
            isPrepared = true;
            progressBuffering.setVisibility(View.GONE);
            ivCenterStatus.setVisibility(View.GONE);
            seekBar.setMax(1000);
            tvTotal.setText(formatTime(videoView.getDuration()));
            tvCurrent.setText("00:00");
            videoView.start();
            isPlaying = true;
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            startProgressUpdates();
            scheduleHideControls();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            progressBuffering.setVisibility(View.GONE);
            ivCenterStatus.setVisibility(View.VISIBLE);
            ivCenterStatus.setImageResource(R.drawable.ic_play);
            Toast.makeText(VideoPlayerActivity.this, "视频播放失败", Toast.LENGTH_SHORT).show();
            return true;
        });

        videoView.setOnCompletionListener(mp -> {
            isPlaying = false;
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            ivCenterStatus.setVisibility(View.VISIBLE);
            ivCenterStatus.setImageResource(R.drawable.ic_play);
            showControls();
        });

        // 缓冲开始/结束反馈
        videoView.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                progressBuffering.setVisibility(View.VISIBLE);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                progressBuffering.setVisibility(View.GONE);
            }
            return true;
        });
    }

    private void resumePlay() {
        if (!isPrepared) return;
        try {
            videoView.start();
        } catch (Exception e) { return; }
        isPlaying = true;
        ivCenterStatus.setVisibility(View.GONE);
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        startProgressUpdates();
        scheduleHideControls();
    }

    private void pause() {
        if (!isPrepared) return;
        if (videoView.isPlaying()) {
            videoView.pause();
        }
        isPlaying = false;
        stopProgressUpdates();
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        ivCenterStatus.setVisibility(View.VISIBLE);
        ivCenterStatus.setImageResource(R.drawable.ic_play);
        showControls();
    }

    // ── 控制条显隐 ──────────────────────────────────────────

    private void toggleControls() {
        if (controlBar.getVisibility() == View.VISIBLE) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void showControls() {
        controlBar.setVisibility(View.VISIBLE);
        scheduleHideControls();
    }

    private void hideControls() {
        controlBar.setVisibility(View.GONE);
    }

    private void scheduleHideControls() {
        controlHandler.removeCallbacks(hideControlsRunnable);
        hideControlsRunnable = () -> {
            if (isPlaying) controlBar.setVisibility(View.GONE);
        };
        controlHandler.postDelayed(hideControlsRunnable, 3000);
    }

    // ── 进度更新 ──────────────────────────────────────────────

    private void startProgressUpdates() {
        stopProgressUpdates();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (videoView != null && isPlaying && !isUserSeeking) {
                    int duration = videoView.getDuration();
                    int current = videoView.getCurrentPosition();
                    if (duration > 0) {
                        seekBar.setProgress(current * 1000 / duration);
                        tvCurrent.setText(formatTime(current));
                    }
                }
                progressHandler.postDelayed(this, 300);
            }
        };
        progressHandler.post(progressRunnable);
    }

    private void stopProgressUpdates() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private String formatTime(int ms) {
        if (ms <= 0) return "00:00";
        int totalSec = ms / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    // ── 全屏 ──────────────────────────────────────────────────

    private void enterFullscreen() {
        isFullscreen = true;
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        findViewById(R.id.controlBar).setVisibility(View.VISIBLE);
        btnFullscreenToggle.setImageResource(R.drawable.ic_fullscreen_exit);
        hideSystemUI();
    }

    private void exitFullscreen() {
        isFullscreen = false;
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        btnFullscreenToggle.setImageResource(R.drawable.ic_fullscreen);
        showSystemUI();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void showSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    // ── 保存视频 ──────────────────────────────────────────────

    private void saveVideo() {
        Toast.makeText(this, "正在保存…", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                String fileName = "watermark_removed_" + System.currentTimeMillis() + ".mp4";
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/WatermarkRemover");
                    android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                        downloadFile(videoUrl, os);
                        mainHandler.post(() -> Toast.makeText(VideoPlayerActivity.this, "保存成功", Toast.LENGTH_SHORT).show());
                    } else {
                        mainHandler.post(() -> Toast.makeText(this, "无法创建文件", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    java.io.File dir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_MOVIES), "WatermarkRemover");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File file = new java.io.File(dir, fileName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                    downloadFile(videoUrl, fos);
                    mainHandler.post(() -> {
                        Toast.makeText(VideoPlayerActivity.this, "保存成功", Toast.LENGTH_SHORT).show();
                        android.content.Intent mediaScan = new android.content.Intent(
                            android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScan.setData(android.net.Uri.fromFile(file));
                        sendBroadcast(mediaScan);
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(VideoPlayerActivity.this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void downloadFile(String urlStr, java.io.OutputStream os) throws Exception {
        if (urlStr != null && urlStr.startsWith("http://")) {
            urlStr = "https://" + urlStr.substring("http://".length());
        }
        if (urlStr == null || (!urlStr.startsWith("https://") && !urlStr.startsWith("http://"))) {
            throw new java.io.IOException("非法下载地址");
        }
        java.net.URL url = new java.net.URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
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
    protected void onPause() {
        super.onPause();
        if (videoView.isPlaying()) {
            videoView.pause();
            isPlaying = false;
            stopProgressUpdates();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        controlHandler.removeCallbacks(hideControlsRunnable);
        stopProgressUpdates();
        videoView.stopPlayback();
        executor.shutdown();
    }
}