package com.tdiqsy.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends AppCompatActivity {

    private ListView listView;
    private TextView tvEmpty;
    private TextView tvClearAll;
    private HistoryDbHelper dbHelper;
    private List<HistoryDbHelper.HistoryItem> items;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        dbHelper = HistoryDbHelper.getInstance(this);
        listView = findViewById(R.id.listView);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvClearAll = findViewById(R.id.tvClearAll);

        tvClearAll.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("清空历史")
                .setMessage("确定要清空所有解析记录吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    dbHelper.deleteAll();
                    loadHistory();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            HistoryDbHelper.HistoryItem item = items.get(position);
            if (item.videoUrl != null && !item.videoUrl.isEmpty()) {
                Intent intent = new Intent(this, VideoPlayerActivity.class);
                intent.putExtra("video_url", item.videoUrl);
                intent.putExtra("video_title", item.title);
                startActivity(intent);
            }
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            HistoryDbHelper.HistoryItem item = items.get(position);
            new AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("确定要删除这条记录吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    dbHelper.deleteById(item.id);
                    loadHistory();
                })
                .setNegativeButton("取消", null)
                .show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        items = dbHelper.getAll();
        if (items.isEmpty()) {
            listView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            listView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            HistoryAdapter adapter = new HistoryAdapter(this, items);
            listView.setAdapter(adapter);
        }
    }

    private static class HistoryAdapter extends BaseAdapter {

        private final HistoryActivity activity;
        private final List<HistoryDbHelper.HistoryItem> items;
        private final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        HistoryAdapter(HistoryActivity activity, List<HistoryDbHelper.HistoryItem> items) {
            this.activity = activity;
            this.items = items;
        }

        @Override
        public int getCount() { return items.size(); }

        @Override
        public Object getItem(int position) { return items.get(position); }

        @Override
        public long getItemId(int position) { return items.get(position).id; }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            if (convertView == null) {
                convertView = activity.getLayoutInflater().inflate(
                    android.R.layout.simple_list_item_2, parent, false);
            }

            HistoryDbHelper.HistoryItem item = items.get(position);
            TextView text1 = convertView.findViewById(android.R.id.text1);
            TextView text2 = convertView.findViewById(android.R.id.text2);

            String title = item.title != null && !item.title.isEmpty() ? item.title : "无标题";
            text1.setText(title);
            text1.setMaxLines(1);
            text1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text1.setTextSize(15);
            text1.setTextColor(0xFF1C1E21);

            String info = item.platformName + " · " + (item.author != null ? item.author : "未知作者");
            String time = sdf.format(new Date(item.createdAt));
            text2.setText(info + "  " + time);
            text2.setTextSize(12);
            text2.setTextColor(0xFFBEC3C9);

            convertView.setPadding(32, 20, 32, 20);
            return convertView;
        }
    }
}