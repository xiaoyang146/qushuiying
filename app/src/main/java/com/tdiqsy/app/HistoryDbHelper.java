package com.tdiqsy.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class HistoryDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "parse_history.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_NAME = "history";

    private static HistoryDbHelper instance;

    public static synchronized HistoryDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new HistoryDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private HistoryDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_NAME + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "platform TEXT," +
            "platform_name TEXT," +
            "title TEXT," +
            "author TEXT," +
            "cover TEXT," +
            "video_url TEXT," +
            "source_url TEXT," +
            "created_at INTEGER" +
            ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    public void insert(VideoInfo info, String sourceUrl) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("platform", info.platform);
        values.put("platform_name", info.platformName);
        values.put("title", info.title);
        values.put("author", info.author);
        values.put("cover", info.cover);
        values.put("video_url", info.videoUrl);
        values.put("source_url", sourceUrl);
        values.put("created_at", System.currentTimeMillis());
        db.insert(TABLE_NAME, null, values);
    }

    public List<HistoryItem> getAll() {
        List<HistoryItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, null, null, null, null,
            "created_at DESC");
        while (cursor.moveToNext()) {
            HistoryItem item = new HistoryItem();
            item.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            item.platform = cursor.getString(cursor.getColumnIndexOrThrow("platform"));
            item.platformName = cursor.getString(cursor.getColumnIndexOrThrow("platform_name"));
            item.title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
            item.author = cursor.getString(cursor.getColumnIndexOrThrow("author"));
            item.cover = cursor.getString(cursor.getColumnIndexOrThrow("cover"));
            item.videoUrl = cursor.getString(cursor.getColumnIndexOrThrow("video_url"));
            item.sourceUrl = cursor.getString(cursor.getColumnIndexOrThrow("source_url"));
            item.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
            list.add(item);
        }
        cursor.close();
        return list;
    }

    public int getCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME, null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    public void deleteAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_NAME, null, null);
    }

    public void deleteById(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_NAME, "id = ?", new String[]{String.valueOf(id)});
    }

    public static class HistoryItem {
        public long id;
        public String platform;
        public String platformName;
        public String title;
        public String author;
        public String cover;
        public String videoUrl;
        public String sourceUrl;
        public long createdAt;
    }
}