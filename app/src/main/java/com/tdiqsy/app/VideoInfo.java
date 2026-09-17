package com.tdiqsy.app;

import java.util.ArrayList;
import java.util.List;

public class VideoInfo implements java.io.Serializable {
    public String platform;
    public String platformName;
    public String title;
    public String author;
    public String authorId;
    public String cover;
    public String videoUrl;
    public String duration;
    public String description;
    // 视频格式（如 mp4/m3u8）与大小（MB 字符串，如 "5.2MB"）
    public String format;
    public String sizeMb;
    public List<String> qualityOptions = new ArrayList<>();

    // 内容类型：video（视频/默认）、image（图集/图文）
    public String contentType = "video";
    // 图集/图文的多张图片地址
    public List<String> imageUrls = new ArrayList<>();
    // 实况图（live photo）：每项包含静态图 + 短视频片段
    public List<LivePhotoItem> livePhotos = new ArrayList<>();

    public boolean isImage() {
        return "image".equalsIgnoreCase(contentType) && !imageUrls.isEmpty();
    }

    public String getSelectedQuality() {
        if (qualityOptions.size() > 1) {
            // 默认选第一个（通常是最高清）
            return qualityOptions.get(0);
        }
        return videoUrl;
    }

    public boolean isLive() {
        return "live".equalsIgnoreCase(contentType);
    }

    /** 实况图单项：静态预览图 + 短视频片段地址 */
    public static class LivePhotoItem implements java.io.Serializable {
        public String imageUrl;
        public String videoUrl;

        public LivePhotoItem(String imageUrl, String videoUrl) {
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
        }
    }
}