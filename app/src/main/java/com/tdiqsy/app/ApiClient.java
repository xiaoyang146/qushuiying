package com.tdiqsy.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ApiClient {

    private static final String BASE_URL = "https://ok1666.cn/adminqsy/api1";

    private static final LinkedHashMap<String, PlatformConfig> PLATFORMS = new LinkedHashMap<>();

    static {
        PLATFORMS.put("douyin", new PlatformConfig("抖音",
            "/douyin/douyin.php?url=",
            "(v\\.douyin\\.com|douyin\\.com|aweme\\.snssdk\\.com)"));
        PLATFORMS.put("dymusic", new PlatformConfig("抖音音乐",
            "/dymusic.php?url=",
            "(music\\.douyin\\.com|dymusic)"));
        PLATFORMS.put("kuaishou", new PlatformConfig("快手",
            "/kuaishou/ksjx.php?url=",
            "(kuaishou\\.com|ksurl\\.cn|gifshow\\.com)"));
        PLATFORMS.put("pipixia", new PlatformConfig("皮皮虾",
            "/ppxia.php?url=",
            "(pipix\\.com|ppxg\\.cn|pipixia\\.com)"));
        PLATFORMS.put("pipigx", new PlatformConfig("皮皮搞笑",
            "/pipigx.php?url=",
            "(pipigx\\.com|pig\\.cool)"));
        PLATFORMS.put("toutiao", new PlatformConfig("头条",
            "/toutiao.php?url=",
            "(toutiao\\.com|toutiao\\.cn|m\\.toutiao)"));
        PLATFORMS.put("weibo", new PlatformConfig("微博",
            "/weibo.php?url=",
            "(weibo\\.com|m\\.weibo\\.cn|weibo\\.cn)"));
        PLATFORMS.put("zuiyou", new PlatformConfig("最右",
            "/zuiyou.php?url=",
            "(zuiyou\\.app|zuimei\\.cn)"));
        PLATFORMS.put("bilibili", new PlatformConfig("哔哩哔哩",
            "/bilibili/bilibili.php?url=",
            "(bilibili\\.com|b23\\.tv|bili2233\\.cn)"));
    }

    private static class PlatformConfig {
        final String name;
        final String apiPath;
        final Pattern pattern;
        PlatformConfig(String name, String apiPath, String regex) {
            this.name = name;
            this.apiPath = apiPath;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }
    }

    public static class ApiException extends Exception {
        public ApiException(String type, String message) {
            super(message);
        }
    }

    public VideoInfo parseVideo(String videoUrl) throws ApiException {
        PlatformConfig platform = null;
        String platformKey = null;
        for (Map.Entry<String, PlatformConfig> entry : PLATFORMS.entrySet()) {
            if (entry.getValue().pattern.matcher(videoUrl).find()) {
                platform = entry.getValue();
                platformKey = entry.getKey();
                break;
            }
        }

        if (platform == null) {
            throw new ApiException("parse", "无法识别平台，请输入正确的短视频链接");
        }

        String apiUrl = BASE_URL + platform.apiPath + URLEncoder.encode(videoUrl);
        JSONObject json = httpGetJson(apiUrl);

        int code = json.optInt("code", -1);
        if (code == 429) {
            throw new ApiException("parse", "请求过于频繁，请稍后重试");
        }
        if (code != 200 && code != 0) {
            String msg = json.optString("msg", json.optString("message", "解析失败"));
            throw new ApiException("parse", msg);
        }

        // 递归扁平化整个 JSON 响应
        FlatMap flat = new FlatMap();
        flattenJson(json, "", flat);

        VideoInfo info = new VideoInfo();
        info.platform = platformKey;
        info.platformName = platform.name;

        info.title = flat.findFirst("title", "desc", "description", "share_title", "item_title");
        info.author = flat.findAuthor();
        info.authorId = flat.findFirst("author_id", "uid", "owner_id", "user_id", "aweme_id", "short_id", "unique_id");
        info.cover = flat.findUrl("cover", "video_img", "cover_url", "thumbnail", "origin_cover", "image_url", "avatar");
        info.description = flat.findFirst("description", "desc", "share_desc", "share_info");

        // 视频地址 - 收集所有可能的视频 URL（排除封面/缩略图/图集图片等字段）
        info.videoUrl = flat.findVideoUrl("url", "video_url", "play_url", "video", "play_addr", "download_url", "source_url", "video_play_url");

        // 视频信息：时长 / 格式 / 大小
        info.duration = flat.findFirst("duration", "video_duration", "timelength", "play_duration", "duration_ms", "total_time", "seconds", "video_seconds");
        info.format = flat.findFirst("format", "video_format", "video_type", "play_format", "file_format", "mime_type", "media_type", "ext", "file_type", "video_extension", "suffix", "format_name");
        info.sizeMb = flat.findSizeMb();

        // 收集所有清晰度选项（同样排除图片/封面等字段）
        List<String> qualityUrls = flat.findAllVideoUrls("url", "video_url", "play_url", "video", "play_addr", "download_url", "source_url", "video_play_url");
        info.qualityOptions = new ArrayList<>();
        if (qualityUrls != null) {
            for (String u : qualityUrls) {
                if (!info.qualityOptions.contains(u)) {
                    info.qualityOptions.add(u);
                }
            }
        }
        if (info.qualityOptions.isEmpty() && info.videoUrl != null && !info.videoUrl.isEmpty()) {
            info.qualityOptions.add(info.videoUrl);
        }

        // 内容类型：后端对图文/图集内容返回 type=image（含常见变体）；抖音 aweme_type=68 为图文
        String typeRaw = flat.findFirst("type", "content_type", "media_type", "item_type", "data_type", "category", "aweme_type")
                .trim().toLowerCase();
        if (typeRaw.contains("image") || typeRaw.contains("img") || typeRaw.contains("pic")
                || typeRaw.contains("photo") || typeRaw.contains("图")
                || "68".equals(typeRaw)) {
            info.contentType = "image";
        }
        if (typeRaw.contains("live")) {
            info.contentType = "live";
        }

        // 图集/图文的图片列表：优先从 JSON 结构中按 images 数组逐张提取（每张一个地址），
        // 提取不到再退回扁平化宽松匹配
        info.imageUrls = new ArrayList<>();
        List<String> structured = extractImageList(json);
        if (structured != null) {
            for (String u : structured) {
                if (u != null && !u.isEmpty() && !info.imageUrls.contains(u)) {
                    info.imageUrls.add(u);
                }
            }
        }
        if (info.imageUrls.isEmpty()) {
            List<String> images = flat.findAllUrls("images", "pics", "photos", "img_list", "image_list",
                    "pics_url", "pics_urls", "images_url", "images_urls", "photo_list", "pic_list",
                    "image_urls", "thumb_list", "pics_list", "images_list");
            if (images != null) {
                for (String u : images) {
                    if (u != null && !u.isEmpty() && !info.imageUrls.contains(u)) {
                        info.imageUrls.add(u);
                    }
                }
            }
        }

        // 视频地址实为图片文件（部分接口把图集首图放在 video_url 字段）
        if (info.videoUrl != null && isImageUrl(info.videoUrl)) {
            if (!info.imageUrls.contains(info.videoUrl)) {
                info.imageUrls.add(0, info.videoUrl);
            }
            info.contentType = "image";
        }

        // 有图无视频 → 图集/图文
        if ((info.videoUrl == null || info.videoUrl.isEmpty()) && !info.imageUrls.isEmpty()) {
            info.contentType = "image";
        }

        // 单图图文（只有一张图片 + 背景音乐）：无图集列表，但有封面图 → 直接用封面作为唯一图片
        if (info.imageUrls.isEmpty()
                && (info.videoUrl == null || info.videoUrl.isEmpty())
                && info.cover != null && !info.cover.isEmpty() && info.cover.startsWith("http")) {
            info.imageUrls.add(info.cover);
            info.contentType = "image";
        }

        // 封面兜底：图片内容无独立封面时，用首张图作封面
        if ("image".equalsIgnoreCase(info.contentType) && (info.cover == null || info.cover.isEmpty()) && !info.imageUrls.isEmpty()) {
            info.cover = info.imageUrls.get(0);
        }

        // 实况图（live_photo）提取：后端 type=live 时返回 live_photo 数组 [{image, video}]
        List<VideoInfo.LivePhotoItem> livePhotos = extractLivePhotoList(json);
        if (livePhotos != null && !livePhotos.isEmpty()) {
            info.livePhotos = livePhotos;
            info.contentType = "live";
            // 排序美化：动态图按 livePhotos 后端顺序排在最前（去重），其余静态图接在后面
            java.util.List<String> reordered = new ArrayList<>();
            for (VideoInfo.LivePhotoItem item : livePhotos) {
                if (item.imageUrl != null && !item.imageUrl.isEmpty()
                        && !reordered.contains(item.imageUrl)) {
                    reordered.add(item.imageUrl);
                }
            }
            if (info.imageUrls != null) {
                for (String u : info.imageUrls) {
                    if (u != null && !u.isEmpty() && !reordered.contains(u)) {
                        reordered.add(u);
                    }
                }
            }
            info.imageUrls = reordered;
            // live 内容用第一张静态图作为封面
            if (info.cover == null || info.cover.isEmpty()) {
                if (!info.imageUrls.isEmpty()) {
                    info.cover = info.imageUrls.get(0);
                }
            }
        }

        if (info.videoUrl == null || info.videoUrl.isEmpty()) {
            if (info.isImage()) {
                // 图文/图集内容：无视频地址属正常情况，放行
                return info;
            }
            if (info.isLive()) {
                // 实况图内容：视频片段在 livePhotos 中，无主视频地址属正常情况，放行
                return info;
            }
            throw new ApiException("parse", "未获取到视频地址，请检查链接是否有效");
        }

        return info;
    }

    /**
     * 从 JSON 结构中逐张提取图集图片地址。
     * 兼容：images / imagePostCover / url_list / url 等常见结构，每张图只取一个地址。
     */
    private List<String> extractImageList(JSONObject json) {
        List<String> result = new ArrayList<>();
        collectImages(json, result);
        // 过滤：只保留 http(s) 图片地址，并去重
        List<String> out = new ArrayList<>();
        for (String u : result) {
            if (u == null || !u.startsWith("http")) continue;
            // 去掉水印/缩略图标识导致的重复近似项时保留首见
            if (!out.contains(u)) out.add(u);
        }
        return out.isEmpty() ? null : out;
    }

    private void collectImages(Object node, List<String> out) {
        if (node instanceof JSONObject) {
            JSONObject jo = (JSONObject) node;
            // 关键键：images 数组（图集）
            JSONArray imagesArr = jo.optJSONArray("images");
            if (imagesArr != null) {
                for (int i = 0; i < imagesArr.length(); i++) {
                    Object item = imagesArr.opt(i);
                    if (item instanceof JSONObject) {
                        // 单张图片对象：取 url_list 首个，其次 url / uri
                        String u = pickImageUrl((JSONObject) item);
                        if (u != null) out.add(u);
                    } else if (item instanceof String) {
                        out.add((String) item);
                    }
                }
            }
            // 也兼容 image_list / pics 等
            String[] listKeys = {"image_list", "pics", "photos", "img_list", "pic_list", "url_list"};
            for (String lk : listKeys) {
                JSONArray arr = jo.optJSONArray(lk);
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.opt(i);
                        if (item instanceof JSONObject) {
                            String u = pickImageUrl((JSONObject) item);
                            if (u != null) out.add(u);
                        } else if (item instanceof String) {
                            String s = (String) item;
                            if (s.startsWith("http")) out.add(s);
                        }
                    }
                }
            }
            // 递归遍历其余字段
            JSONArray names = jo.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    Object v = jo.opt(names.optString(i));
                    if (v instanceof JSONObject || v instanceof JSONArray) {
                        collectImages(v, out);
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectImages(arr.opt(i), out);
            }
        }
    }

    /** 从单张图片对象中挑一个地址：优先动态图 > 原图 > 普通图 */
    private String pickImageUrl(JSONObject img) {
        // ── 1. url_list 中优先选动态图（GIF/动态 WebP）──
        JSONArray urlList = img.optJSONArray("url_list");
        if (urlList != null && urlList.length() > 0) {
            String animated = null, original = null, first = null;
            for (int i = 0; i < urlList.length(); i++) {
                String u = urlList.optString(i);
                if (u == null || !u.startsWith("http")) continue;
                if (first == null) first = u;
                String lower = u.toLowerCase();
                int q = lower.indexOf('?');
                String path = q >= 0 ? lower.substring(0, q) : lower;
                if (path.endsWith(".gif")) { animated = u; break; }
                if (animated == null && path.endsWith(".webp")) animated = u;
                if (original == null && (path.endsWith(".png") || path.endsWith(".jpg")
                        || path.endsWith(".jpeg"))) original = u;
            }
            if (animated != null) return animated;
            if (original != null) return original;
            if (first != null) return first;
        }

        // ── 2. 显式动态图字段（视频流 / GIF / animated）──
        // stream 对象（小红书等平台视频流地址）
        JSONObject stream = img.optJSONObject("stream");
        if (stream != null) {
            JSONArray sUrls = stream.optJSONArray("url_list");
            if (sUrls != null) {
                for (int i = 0; i < sUrls.length(); i++) {
                    String u = sUrls.optString(i);
                    if (u != null && u.startsWith("http")) return u;
                }
            }
            String su = stream.optString("url");
            if (su != null && su.startsWith("http")) return su;
        }
        // video / gif / animated 子对象
        String[] subKeys = {"video", "gif", "animated", "dynamic"};
        for (String sk : subKeys) {
            JSONObject sub = img.optJSONObject(sk);
            if (sub != null) {
                JSONArray sUrls = sub.optJSONArray("url_list");
                if (sUrls != null) {
                    for (int i = 0; i < sUrls.length(); i++) {
                        String u = sUrls.optString(i);
                        if (u != null && u.startsWith("http")) return u;
                    }
                }
                String su = sub.optString("url");
                if (su != null && su.startsWith("http")) return su;
            }
        }
        // 显式动态图 URL 字段
        String[] dynKeys = {"animated_url", "gif_url", "dynamic_url", "video_url",
                "origin_url", "play_url", "download_url"};
        for (String k : dynKeys) {
            String u = img.optString(k);
            if (u != null && u.startsWith("http")) return u;
        }

        // ── 3. 兜底：普通图片字段 ──
        String[] keys = {"url", "image_url", "uri", "thumb_url"};
        for (String k : keys) {
            String u = img.optString(k);
            if (u != null && u.startsWith("http")) return u;
        }
        return null;
    }

    /** 判断地址是否为图片文件（按扩展名） */
    private boolean isImageUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    // ── 递归扁平化 JSON ──────────────────────────────────────────

    private static class FlatMap {
        final List<FlatEntry> entries = new ArrayList<>();

        void put(String key, String value) {
            if (value != null && !value.isEmpty()) {
                entries.add(new FlatEntry(key, value));
            }
        }

        String findFirst(String... keys) {
            for (String k : keys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if (e.key.equals(kl) || e.key.endsWith("." + kl) || e.key.contains("." + kl + ".")) {
                        return e.value;
                    }
                }
            }
            // 宽松匹配
            for (String k : keys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if (e.key.contains(kl)) {
                        return e.value;
                    }
                }
            }
            return "";
        }

        /** 查找视频大小：字节数字转 MB，带单位字符串直接使用 */
        String findSizeMb() {
            // 明确表示“视频大小”的字段优先
            String raw = findFirst("video_size", "video_filesize", "video_size_bytes", "video_size_byte",
                    "file_size", "filesize", "size_bytes", "total_size", "origin_size", "play_size", "media_size");
            if (raw == null || raw.isEmpty()) {
                // 兜底通用 size：过滤掉封面/缩略图/音乐/头像等无关字段
                raw = findVideoSizeFallback();
            }
            if (raw == null || raw.isEmpty()) return "";
            String t = raw.trim();
            // 已是带单位字符串，如 "12.5MB" / "5M" / "1.2GB"
            if (t.matches("(?i).*(kb|mb|gb|k|m|g|b)$")) {
                return t;
            }
            try {
                double bytes = Double.parseDouble(t);
                if (bytes >= 1024 * 1024) {
                    return String.format(java.util.Locale.CHINA, "%.1fMB", bytes / (1024 * 1024));
                } else if (bytes > 0) {
                    return String.format(java.util.Locale.CHINA, "%.0fKB", bytes / 1024);
                }
            } catch (Exception ignored) {}
            return "";
        }

        /** 在含 "size" 的字段中找视频大小，跳过封面/缩略图/音乐等小文件字段 */
        String findVideoSizeFallback() {
            for (FlatEntry e : entries) {
                if (!e.key.contains("size")) continue;
                String k = e.key.toLowerCase();
                if (k.contains("cover") || k.contains("thumb") || k.contains("music")
                        || k.contains("avatar") || k.contains("image") || k.contains("photo")
                        || k.contains("icon") || k.contains("pic") || k.contains("audio")
                        || k.contains("width") || k.contains("height")) {
                    continue;
                }
                return e.value;
            }
            return "";
        }

        String findAuthor() {
            // 先精确匹配作者相关字段
            String[] authorKeys = {"author", "nickname", "owner_name", "author_name", "user_name", "name", "username", "nick_name"};
            for (String k : authorKeys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if (e.key.equals(kl) || e.key.endsWith("." + kl)) {
                        return e.value;
                    }
                }
            }
            // 再宽松匹配
            for (String k : authorKeys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if (e.key.contains(kl)) {
                        return e.value;
                    }
                }
            }
            return "";
        }

        String findUrl(String... keys) {
            for (String k : keys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if (e.key.equals(kl) || e.key.endsWith("." + kl)) {
                        return e.value;
                    }
                }
            }
            for (String k : keys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if (e.key.contains(kl)) {
                        return e.value;
                    }
                }
            }
            return "";
        }

        List<String> findAllUrls(String... keys) {
            List<String> result = new ArrayList<>();
            for (String k : keys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if ((e.key.equals(kl) || e.key.endsWith("." + kl) || e.key.contains(kl))
                        && e.value.startsWith("http")) {
                        if (!result.contains(e.value)) {
                            result.add(e.value);
                        }
                    }
                }
            }
            return result;
        }

        /** 查找视频地址，跳过封面/缩略图/图集图片等图片类字段 */
        String findVideoUrl(String... keys) {
            for (String k : keys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if (!isImageKey(e.key)
                            && (e.key.equals(kl) || e.key.endsWith("." + kl))) {
                        return e.value;
                    }
                }
            }
            for (String k : keys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if (!isImageKey(e.key) && e.key.contains(kl)) {
                        return e.value;
                    }
                }
            }
            return "";
        }

        /** 收集所有视频地址，跳过封面/缩略图/图集图片等图片类字段 */
        List<String> findAllVideoUrls(String... keys) {
            List<String> result = new ArrayList<>();
            for (String k : keys) {
                String kl = k.toLowerCase();
                for (FlatEntry e : entries) {
                    if (!isImageKey(e.key)
                            && (e.key.equals(kl) || e.key.endsWith("." + kl) || e.key.contains(kl))
                            && e.value.startsWith("http")) {
                        if (!result.contains(e.value)) {
                            result.add(e.value);
                        }
                    }
                }
            }
            return result;
        }

        /** 判断扁平化键路径是否指向图片/音乐/封面等非视频字段 */
        private static boolean isImageKey(String key) {
            String k = key.toLowerCase();
            return k.contains("image") || k.contains("img") || k.contains("pic")
                    || k.contains("photo") || k.contains("cover") || k.contains("thumb")
                    || k.contains("avatar") || k.contains("icon") || k.contains("poster")
                    || k.contains("music") || k.contains("audio") || k.contains("sound")
                    || k.contains("bmg") || k.contains("bgm");
        }
    }

    private static class FlatEntry {
        final String key;
        final String value;
        FlatEntry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private void flattenJson(Object obj, String prefix, FlatMap flat) {
        if (obj instanceof JSONObject) {
            JSONObject jo = (JSONObject) obj;
            JSONArray names = jo.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                Object val = jo.opt(key);
                String fullKey = prefix.isEmpty() ? key.toLowerCase() : prefix + "." + key.toLowerCase();
                flattenValue(val, fullKey, flat);
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            // 检测是否为交替 key-value 数组 (如 ["name","张三","id","123"])
            if (isAlternatingKvArray(arr)) {
                for (int i = 0; i < arr.length() - 1; i += 2) {
                    String k = arr.optString(i).toLowerCase();
                    Object v = arr.opt(i + 1);
                    String fullKey = prefix.isEmpty() ? k : prefix + "." + k;
                    flattenValue(v, fullKey, flat);
                }
            } else {
                for (int i = 0; i < arr.length(); i++) {
                    flattenJson(arr.opt(i), prefix + "[" + i + "]", flat);
                }
            }
        }
    }

    private void flattenValue(Object val, String fullKey, FlatMap flat) {
        if (val instanceof String) {
            String s = ((String) val).trim();
            if (!s.isEmpty()) {
                // 尝试解析内嵌 JSON 字符串
                if ((s.startsWith("{") || s.startsWith("[")) && s.length() > 10) {
                    try {
                        Object parsed = s.startsWith("{") ? new JSONObject(s) : new JSONArray(s);
                        flattenJson(parsed, fullKey, flat);
                        return;
                    } catch (JSONException ignored) {}
                }
                flat.put(fullKey, s);
            }
        } else if (val instanceof JSONObject || val instanceof JSONArray) {
            flattenJson(val, fullKey, flat);
        } else if (val instanceof Number || val instanceof Boolean) {
            flat.put(fullKey, String.valueOf(val));
        }
    }

    private boolean isAlternatingKvArray(JSONArray arr) {
        if (arr.length() < 2 || arr.length() % 2 != 0) return false;
        int strCount = 0;
        for (int i = 0; i < Math.min(arr.length(), 6); i++) {
            if (arr.opt(i) instanceof String) strCount++;
        }
        if (strCount != Math.min(arr.length(), 6)) return false;
        // 全是 URL 的字符串数组（如图片的 url_list）不是 key-value 对，不应误判
        int urlCount = 0;
        for (int i = 0; i < arr.length(); i++) {
            if (arr.optString(i).startsWith("http")) urlCount++;
        }
        if (urlCount == arr.length()) return false;
        return true;
    }

    // ── HTTP 请求 ─────────────────────────────────────────────────

    private JSONObject httpGetJson(String apiUrl) throws ApiException {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                conn.disconnect();
                throw new ApiException("server", "服务器返回异常 (HTTP " + responseCode + ")，请稍后重试");
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();

            String responseStr = response.toString().trim();
            if (!responseStr.startsWith("{")) {
                throw new ApiException("server", "服务端返回异常数据");
            }

            return new JSONObject(responseStr);

        } catch (UnknownHostException e) {
            throw new ApiException("network", "网络不可用，请检查手机网络连接");
        } catch (SocketTimeoutException e) {
            throw new ApiException("network", "请求超时，请检查网络后重试");
        } catch (ConnectException e) {
            throw new ApiException("network", "无法连接服务器，请检查网络");
        } catch (JSONException e) {
            throw new ApiException("parse", "数据解析失败：" + e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("unknown", "未知错误：" + e.getMessage());
        }
    }

    /**
     * 从 JSON 中提取实况图列表（live_photo）。
     * 后端格式：live_photo: [{image: "静态图URL", video: "短视频URL"}, ...]
     */
    private List<VideoInfo.LivePhotoItem> extractLivePhotoList(JSONObject json) {
        List<VideoInfo.LivePhotoItem> list = new ArrayList<>();
        try {
            JSONArray arr = json.optJSONArray("live_photo");
            if (arr == null) {
                // 也可能在 data.live_photo 中
                JSONObject data = json.optJSONObject("data");
                if (data != null) {
                    arr = data.optJSONArray("live_photo");
                }
            }
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item == null) continue;
                    String img = "";
                    String vid = "";
                    String[] imgKeys = {"image", "img", "cover", "image_url", "thumb", "thumbnail"};
                    for (String k : imgKeys) {
                        String v = item.optString(k, "");
                        if (!v.isEmpty() && (v.startsWith("http") || v.startsWith("//"))) { img = v; break; }
                    }
                    String[] vidKeys = {"video", "video_url", "play_url", "url", "download_url",
                            "mp4", "uri", "animated_url", "dynamic_url", "video_play_url"};
                    for (String k : vidKeys) {
                        String v = item.optString(k, "");
                        if (!v.isEmpty() && (v.startsWith("http") || v.startsWith("//"))) { vid = v; break; }
                    }
                    // 视频可能嵌套在 stream 对象中（如小红书 url_list）
                    if (vid.isEmpty()) {
                        JSONObject streamObj = item.optJSONObject("stream");
                        if (streamObj != null) {
                            JSONArray su = streamObj.optJSONArray("url_list");
                            if (su != null && su.length() > 0) {
                                String s = su.optString(0, "");
                                if (s.startsWith("http")) vid = s;
                            }
                            if (vid.isEmpty()) {
                                String s = streamObj.optString("url", streamObj.optString("play_url", ""));
                                if (s.startsWith("http")) vid = s;
                            }
                        }
                    }
                    if (!img.isEmpty() || !vid.isEmpty()) {
                        list.add(new VideoInfo.LivePhotoItem(img, vid));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }
}