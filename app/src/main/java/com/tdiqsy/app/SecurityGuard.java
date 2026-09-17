package com.tdiqsy.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 环境安全守卫（安全加固）
 *
 * 在 APP 启动时调用 {@link #run(Activity)}：
 *  - 若检测到模拟器 / Root / VPN / 代理 / 抓包 Hook / 调试器等不安全环境，
 *    在全屏覆盖一层阻断提示并自动退出应用（强制退出进程）。
 *  - 若环境安全，返回 false，业务代码继续正常执行。
 */
public final class SecurityGuard {

    private static volatile boolean shown = false;

    private SecurityGuard() {
    }

    /**
     * 执行安全检测；若环境不安全则覆盖全屏提示并退出。
     *
     * @return true=检测到不安全环境（已触发阻断与退出流程）；false=环境安全
     */
    public static boolean run(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        List<String> reasons = EnvDetector.detect(activity);
        if (reasons == null || reasons.isEmpty()) return false;
        blockAndExit(activity, reasons);
        return true;
    }

    private static void blockAndExit(Activity activity, List<String> reasons) {
        if (shown) return; // 进程内已阻断过，避免重复叠加
        shown = true;

        // 全屏阻断视图
        LinearLayout overlay = new LinearLayout(activity);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xFFF5F6FA);
        overlay.setKeepScreenOn(true);

        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        TextView title = new TextView(activity);
        title.setText("安全环境检测未通过");
        title.setTextColor(Color.rgb(198, 40, 40));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        overlay.addView(title, wrap);

        TextView sub = new TextView(activity);
        sub.setText("应用将在数秒后自动退出");
        sub.setTextColor(Color.rgb(120, 120, 120));
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = 16;
        subLp.bottomMargin = 24;
        overlay.addView(sub, subLp);

        StringBuilder detail = new StringBuilder();
        for (String r : reasons) {
            if (detail.length() > 0) detail.append("\n");
            detail.append("• ").append(r);
        }
        TextView reason = new TextView(activity);
        reason.setText(detail.toString());
        reason.setTextColor(Color.rgb(90, 90, 90));
        reason.setTextSize(14);
        reason.setGravity(Gravity.CENTER);
        overlay.addView(reason, wrap);

        // 用全屏 LayoutParams 覆盖，阻断所有界面交互
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
        activity.addContentView(overlay, lp);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.finishAffinity();
                } catch (Throwable ignored) {
                    try {
                        activity.finish();
                    } catch (Throwable ignored2) {
                    }
                }
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable ignored3) {
                }
                // 兜底：确保进程退出
                Runtime.getRuntime().exit(0);
            }
        }, 1800);
    }
}