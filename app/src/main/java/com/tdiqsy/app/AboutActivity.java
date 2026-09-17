package com.tdiqsy.app;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ImageView ivIcon = findViewById(R.id.ivAboutIcon);
        TextView tvName = findViewById(R.id.tvAboutName);
        TextView tvVersion = findViewById(R.id.tvAboutVersion);

        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(getPackageName(), 0);
            ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo));
            tvName.setText(pm.getApplicationLabel(appInfo));
            String version = pm.getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText("Version " + version);
        } catch (Exception e) {
            tvName.setText("去水印");
            tvVersion.setText("Version 1.0.0");
        }

        // 用户协议
        findViewById(R.id.itemUserAgreement).setOnClickListener(v ->
                openContent("agreement"));
        // 隐私协议
        findViewById(R.id.itemPrivacyPolicy).setOnClickListener(v ->
                openContent("privacy"));
        // 免责声明
        findViewById(R.id.itemDisclaimer).setOnClickListener(v ->
                openContent("disclaimer"));
    }

    private void openContent(String type) {
        Intent intent = new Intent(this, ContentDetailActivity.class);
        intent.putExtra("type", type);
        startActivity(intent);
    }
}