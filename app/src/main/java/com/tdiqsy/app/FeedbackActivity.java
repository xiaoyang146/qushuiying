package com.tdiqsy.app;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FeedbackActivity extends AppCompatActivity {

    private EditText etFeedback;
    private TextView tvCharCount;
    private LinearLayout layoutThumbnails;
    private TextView tvQQ;
    private TextView btnCopyWechat;
    private TextView tvWechat;

    private final ArrayList<Uri> imageUris = new ArrayList<>();
    private Uri currentPhotoUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProgressDialog progressDialog;

    private static final String API_FEEDBACK = "https://ok1666.cn/adminqsy/feedback.php";

    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    if (result.getData().getClipData() != null) {
                        int count = result.getData().getClipData().getItemCount();
                        for (int i = 0; i < count && imageUris.size() < 9; i++) {
                            Uri uri = result.getData().getClipData().getItemAt(i).getUri();
                            imageUris.add(uri);
                            addThumbnail(uri);
                        }
                    } else if (result.getData().getData() != null) {
                        Uri uri = result.getData().getData();
                        imageUris.add(uri);
                        addThumbnail(uri);
                    }
                }
            });

    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (success && currentPhotoUri != null) {
                    imageUris.add(currentPhotoUri);
                    addThumbnail(currentPhotoUri);
                }
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    openCamera();
                } else {
                    Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        etFeedback = findViewById(R.id.etFeedback);
        tvCharCount = findViewById(R.id.tvCharCount);
        layoutThumbnails = findViewById(R.id.layoutThumbnails);
        tvQQ = findViewById(R.id.tvQQ);
        btnCopyWechat = findViewById(R.id.btnCopyWechat);
        tvWechat = findViewById(R.id.tvWechat);

        // 字数统计
        etFeedback.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                tvCharCount.setText(s.length() + "/300");
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 添加照片按钮
        findViewById(R.id.btnAddImage).setOnClickListener(v -> showImagePickerDialog());

        // QQ 跳转
        tvQQ.setOnClickListener(v -> openQQProfile());

        // 复制微信号
        btnCopyWechat.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("wechat", tvWechat.getText().toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "已复制微信号", Toast.LENGTH_SHORT).show();
        });

        // 提交按钮
        findViewById(R.id.btnSubmit).setOnClickListener(v -> {
            String content = etFeedback.getText().toString().trim();
            if (content.isEmpty() && imageUris.isEmpty()) {
                Toast.makeText(this, "请输入反馈内容或上传截图", Toast.LENGTH_SHORT).show();
                return;
            }
            submitFeedback(content);
        });
    }

    private void submitFeedback(String content) {
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("正在提交反馈…");
        progressDialog.setCancelable(false);
        progressDialog.show();

        executor.execute(() -> {
            try {
                String boundary = "----FormBoundary" + System.currentTimeMillis();
                URL url = new URL(API_FEEDBACK);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream dos = new DataOutputStream(conn.getOutputStream());

                // 反馈内容
                writeFormField(dos, boundary, "content", content);

                // 设备信息
                String deviceInfo = buildDeviceInfo();
                writeFormField(dos, boundary, "device_info", deviceInfo);

                // 图片文件
                for (int i = 0; i < imageUris.size(); i++) {
                    Uri uri = imageUris.get(i);
                    String fileName = "feedback_image_" + (i + 1) + ".jpg";
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    if (inputStream != null) {
                        writeFormFile(dos, boundary, "images[]", fileName, inputStream);
                        inputStream.close();
                    }
                }

                // 结束标记
                dos.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
                dos.flush();
                dos.close();

                int responseCode = conn.getResponseCode();
                InputStream responseStream = (responseCode >= 200 && responseCode < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream));
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
                        int code = json.optInt("code", -1);
                        String msg = json.optString("msg", "提交失败");
                        if (code == 200) {
                            new AlertDialog.Builder(FeedbackActivity.this)
                                    .setTitle("提交成功")
                                    .setMessage("感谢您的反馈，我们会认真对待每一条建议！")
                                    .setPositiveButton("确定", (d, w) -> finish())
                                    .show();
                        } else {
                            Toast.makeText(FeedbackActivity.this, msg, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(FeedbackActivity.this, "反馈提交失败，请稍后重试", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(FeedbackActivity.this, "网络连接失败，请检查网络后重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void writeFormField(DataOutputStream dos, String boundary, String name, String value) throws IOException {
        dos.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        dos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes("UTF-8"));
        dos.write("\r\n".getBytes("UTF-8"));
        dos.write((value + "\r\n").getBytes("UTF-8"));
    }

    private void writeFormFile(DataOutputStream dos, String boundary, String name, String fileName,
                               InputStream inputStream) throws IOException {
        dos.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        dos.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n").getBytes("UTF-8"));
        dos.write("Content-Type: image/jpeg\r\n".getBytes("UTF-8"));
        dos.write("\r\n".getBytes("UTF-8"));

        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            dos.write(buffer, 0, bytesRead);
        }
        dos.writeBytes("\r\n");
    }

    private String buildDeviceInfo() {
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("brand", Build.BRAND);
            json.put("model", Build.MODEL);
            json.put("sdk", Build.VERSION.SDK_INT);
            json.put("versionName", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            json.put("versionCode", getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode());
            return json.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void showImagePickerDialog() {
        String[] options = {"从相册选择", "拍照"};
        new AlertDialog.Builder(this)
                .setTitle("添加照片")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openGallery();
                    } else {
                        checkCameraPermission();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        galleryLauncher.launch(intent);
    }

    private void checkCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            } else {
                openCamera();
            }
        } else {
            openCamera();
        }
    }

    private void openCamera() {
        Intent takePicture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePicture.resolveActivity(getPackageManager()) != null) {
            File photoFile;
            try {
                photoFile = createImageFile();
            } catch (IOException e) {
                Toast.makeText(this, "创建图片文件失败", Toast.LENGTH_SHORT).show();
                return;
            }
            currentPhotoUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", photoFile);
            takePicture.putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri);
            cameraLauncher.launch(currentPhotoUri);
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    private void addThumbnail(Uri uri) {
        ImageView thumbnail = new ImageView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dpToPx(64), dpToPx(64));
        params.setMargins(0, 0, dpToPx(8), 0);
        thumbnail.setLayoutParams(params);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setImageURI(uri);

        int index = imageUris.size() - 1;
        thumbnail.setOnClickListener(v -> {
            imageUris.remove(index);
            layoutThumbnails.removeView(thumbnail);
            refreshThumbnails();
        });

        layoutThumbnails.addView(thumbnail);
    }

    private void refreshThumbnails() {
        layoutThumbnails.removeAllViews();
        for (int i = 0; i < imageUris.size(); i++) {
            final int idx = i;
            ImageView thumbnail = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dpToPx(64), dpToPx(64));
            params.setMargins(0, 0, dpToPx(8), 0);
            thumbnail.setLayoutParams(params);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setImageURI(imageUris.get(i));
            thumbnail.setOnClickListener(v -> {
                imageUris.remove(idx);
                layoutThumbnails.removeView(thumbnail);
                refreshThumbnails();
            });
            layoutThumbnails.addView(thumbnail);
        }
    }

    private void openQQProfile() {
        try {
            String qqUrl = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=2502660988";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(qqUrl));
            startActivity(intent);
        } catch (Exception e) {
            try {
                String webUrl = "https://qm.qq.com/cgi-bin/qm/qr?k=2502660988";
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl));
                startActivity(intent);
            } catch (Exception ex) {
                Toast.makeText(this, "无法打开QQ", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}