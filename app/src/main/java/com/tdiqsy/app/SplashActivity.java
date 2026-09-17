package com.tdiqsy.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    // 启动时间戳（用于等待更新检测结果）
    private final long launchTime = System.currentTimeMillis();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // 加固：启动即进行环境检测（VPN/抓包/模拟器/Root/调试等），不安全则阻断并自动退出
        if (SecurityGuard.run(this)) {
            return;
        }

        View ivAppIcon = findViewById(R.id.ivAppIcon);
        TextView tvAppName = findViewById(R.id.tvAppName);
        TextView tvTagline = findViewById(R.id.tvTagline);
        TextView tvSubTagline = findViewById(R.id.tvSubTagline);
        TextView tvVersion = findViewById(R.id.tvVersion);

        // 版本号从当前应用动态读取（勿写死）
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (versionName != null && !versionName.isEmpty()) {
                tvVersion.setText("V" + versionName);
            }
        } catch (Exception ignored) {
        }

        // 图标淡入
        ObjectAnimator iconAnim = ObjectAnimator.ofFloat(ivAppIcon, "alpha", 0f, 1f);
        iconAnim.setDuration(400);
        iconAnim.setStartDelay(100);

        // 应用名称淡入
        ObjectAnimator nameAnim = ObjectAnimator.ofFloat(tvAppName, "alpha", 0f, 1f);
        nameAnim.setDuration(400);
        nameAnim.setStartDelay(300);

        // 主标语淡入
        ObjectAnimator taglineAnim = ObjectAnimator.ofFloat(tvTagline, "alpha", 0f, 1f);
        taglineAnim.setDuration(400);
        taglineAnim.setStartDelay(450);

        // 副标语淡入
        ObjectAnimator subAnim = ObjectAnimator.ofFloat(tvSubTagline, "alpha", 0f, 1f);
        subAnim.setDuration(400);
        subAnim.setStartDelay(550);

        // 版本号淡入
        ObjectAnimator versionAnim = ObjectAnimator.ofFloat(tvVersion, "alpha", 0f, 1f);
        versionAnim.setDuration(400);
        versionAnim.setStartDelay(600);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(iconAnim, nameAnim, taglineAnim, subAnim, versionAnim);
        animatorSet.start();

        // 启动页即发起更新检测，弹窗在首页展示
        UpdateManager.checkUpdate();

        // 启动页上报心跳，用于后台在线人数统计（IP + 设备ID 每日去重）
        HeartbeatManager.ping(this);

        new Handler(Looper.getMainLooper()).postDelayed(this::goHome, 1800);
    }

    private void goHome() {
        // 等待更新检测结果（启动后最长约 3.5 秒），确保首页能弹出更新弹窗
        if (!UpdateManager.hasResult() && (System.currentTimeMillis() - launchTime) < 3500) {
            new Handler(Looper.getMainLooper()).postDelayed(this::goHome, 200);
            return;
        }
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}