package com.github.wsure.bilibiliaudio.api;

import com.github.wsure.bilibiliaudio.config.BiliConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站 HTTP 客户端，移植自 bili_audio_poc_v2.py 的 BiliClient。
 * <p>
 * 线程安全：cookie / wbi key 缓存均通过同步方法访问。所有网络调用都是阻塞的，
 * 调用方（resolver / 命令）必须在工作线程上执行。
 */
public final class BiliClient {
    private static final Pattern BV_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("/([^/]+)\\.\\w+$");

    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String VIEW_URL = "https://api.bilibili.com/x/web-interface/view";
    private static final String PLAYURL_URL = "https://api.bilibili.com/x/player/wbi/playurl";
    private static final String TICKET_URL =
            "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ── cookie / wbi 缓存 ──────────────────────────────────────────────────
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private long cookieFileMtime = -1;
    private String imgKey = "";
    private String subKey = "";
    private boolean isLogin = false;
    private String uname = "";

    private BiliClient() {
    }

    private static final class Holder {
        static final BiliClient INSTANCE = new BiliClient();
    }

    public static BiliClient getInstance() {
        return Holder.INSTANCE;
    }

    // ── cookie ─────────────────────────────────────────────────────────────
    private synchronized void reloadCookieIfNeeded() {
        try {
            Path file = BiliConfig.COOKIE_FILE;
            if (!Files.isRegularFile(file)) {
                if (!cookies.isEmpty()) {
                    cookies.clear();
                }
                return;
            }
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (mtime == cookieFileMtime && !cookies.isEmpty()) {
                return;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            cookies.clear();
            cookieFileMtime = mtime;
            for (String part : raw.split(";")) {
                part = part.trim();
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if (!k.isEmpty()) {
                    cookies.put(k, v);
                }
            }
            // cookie 变了，wbi key / 登录态缓存失效
            imgKey = "";
            subKey = "";
            isLogin = false;
            uname = "";
        } catch (IOException e) {
            BiliConfig.LOGGER.warn("Failed to read bili cookie file {}: {}", BiliConfig.COOKIE_FILE, e.getMessage());
        }
    }

    private synchronized String cookieHeader() {
        reloadCookieIfNeeded();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private synchronized void persistCookies() {
        try {
            Path file = BiliConfig.COOKIE_FILE;
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            cookieFileMtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            BiliConfig.LOGGER.warn("Failed to persist bili cookie: {}", e.getMessage());
        }
    }

    // ── HTTP ───────────────────────────────────────────────────────────────
    private HttpRequest.Builder request(String url, String referer) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", BiliConfig.USER_AGENT)
                .header("Referer", referer)
                .header("Origin", BiliConfig.BILI_REFERER)
                .header("Accept", "application/json, text/plain, */*")
                .header("Cookie", cookieHeader());
    }

    private JsonObject getJson(String url, String referer) throws IOException, InterruptedException {
        HttpResponse<String> resp = HTTP.send(request(url, referer).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 412) {
            throw new IOException("B站风控 HTTP 412: " + url);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /**
     * POST 但参数放在 URL query（空 body）。GenWebTicket 接口只从 query 解析 context[ts]，
     * 放进 body 会返回 -400 "empty `ts` field"（与 PoC 的 requests.post(url, params=...) 行为一致）。
     */
    private JsonObject postWithQuery(String url, Map<String, String> params, String referer)
            throws IOException, InterruptedException {
        String fullUrl = url + "?" + buildQuery(params);
        HttpResponse<String> resp = HTTP.send(request(fullUrl, referer)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 412) {
            throw new IOException("B站风控 HTTP 412: " + url);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private static String buildQuery(Map<String, ?> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(WbiSigner.encode(e.getKey())).append('=')
                    .append(WbiSigner.encode(String.valueOf(e.getValue())));
        }
        return sb.toString();
    }

    private static void ensureCodeZero(JsonObject obj, String hint) throws IOException {
        int code = obj.has("code") ? obj.get("code").getAsInt() : -1;
        if (code != 0) {
            throw new IOException(hint + ": code=" + code + " message=" +
                    (obj.has("message") ? obj.get("message").getAsString() : "?"));
        }
    }

    // ── nav / wbi keys ─────────────────────────────────────────────────────
    private synchronized void ensureWbiKeys() throws IOException, InterruptedException {
        if (!imgKey.isEmpty() && !subKey.isEmpty()) {
            return;
        }
        fetchNavState();
    }

    /**
     * 请求 nav，刷新 isLogin / uname / wbi keys。expect_code_zero=False：
     * 即使未登录(code=-101)也能从 data.wbi_img 拿到 WBI keys（参考 BBDown.CheckLogin）。
     */
    private synchronized void fetchNavState() throws IOException, InterruptedException {
        JsonObject nav = getJson(NAV_URL, BiliConfig.BILI_REFERER);
        JsonObject data = nav.has("data") && nav.get("data").isJsonObject() ? nav.getAsJsonObject("data") : new JsonObject();
        isLogin = data.has("isLogin") && data.get("isLogin").getAsBoolean();
        uname = data.has("uname") && !data.get("uname").isJsonNull() ? data.get("uname").getAsString() : "";
        JsonObject wbi = data.has("wbi_img") && data.get("wbi_img").isJsonObject()
                ? data.getAsJsonObject("wbi_img") : new JsonObject();
        String imgUrl = wbi.has("img_url") ? wbi.get("img_url").getAsString() : "";
        String subUrl = wbi.has("sub_url") ? wbi.get("sub_url").getAsString() : "";
        if (imgUrl.isEmpty() || subUrl.isEmpty()) {
            throw new IOException("nav 未返回 wbi_img: " + nav);
        }
        imgKey = extractFileName(imgUrl);
        subKey = extractFileName(subUrl);
    }

    private static String extractFileName(String url) {
        Matcher m = FILE_NAME_PATTERN.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    public synchronized boolean isLogin() {
        return isLogin;
    }

    /**
     * 实时请求 nav 刷新登录态（强制，不读缓存），返回是否已登录。
     * 供配置页登录确认使用；含一次 HTTP，须在工作线程调用。
     */
    public synchronized boolean checkLogin() {
        try {
            fetchNavState();
        } catch (Exception e) {
            BiliConfig.LOGGER.warn("checkLogin 请求 nav 失败: {}", e.getMessage());
        }
        return isLogin;
    }

    public synchronized String getUname() {
        return uname;
    }

    // ── bili_ticket 自动刷新 ───────────────────────────────────────────────
    private synchronized void ensureFreshTicket() {
        reloadCookieIfNeeded();
        String ticket = cookies.getOrDefault("bili_ticket", "");
        String expStr = cookies.getOrDefault("bili_ticket_expires", "0");
        long exp;
        try {
            exp = Long.parseLong(expStr);
        } catch (NumberFormatException e) {
            exp = 0;
        }
        long now = Instant.now().getEpochSecond();
        if (!ticket.isEmpty() && exp - now > 300) {
            return;
        }
        try {
            long ts = now;
            String hexsign = hmacSha256Hex(BiliConfig.TICKET_HMAC_KEY, "ts" + ts);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("key_id", "ec02");
            form.put("hexsign", hexsign);
            form.put("context[ts]", Long.toString(ts));
            form.put("csrf", cookies.getOrDefault("bili_jct", ""));
            JsonObject payload = postWithQuery(TICKET_URL, form, BiliConfig.BILI_REFERER);
            if (!payload.has("code") || payload.get("code").getAsInt() != 0) {
                BiliConfig.LOGGER.warn("GenWebTicket 失败: {}", payload);
                return;
            }
            JsonObject d = payload.getAsJsonObject("data");
            String newTicket = d.get("ticket").getAsString();
            long createdAt = d.has("created_at") ? d.get("created_at").getAsLong() : ts;
            long ttl = d.has("ttl") ? d.get("ttl").getAsLong() : 259200L;
            cookies.put("bili_ticket", newTicket);
            cookies.put("bili_ticket_expires", Long.toString(createdAt + ttl));
            persistCookies();
            BiliConfig.LOGGER.info("已刷新 bili_ticket，有效期至 epoch {}", createdAt + ttl);
        } catch (Exception e) {
            BiliConfig.LOGGER.warn("刷新 bili_ticket 失败（不影响匿名播放）: {}", e.getMessage());
        }
    }

    private static String hmacSha256Hex(String key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    // ── 视频 / 音频 ────────────────────────────────────────────────────────
    private JsonObject getVideoData(String bvid) throws IOException, InterruptedException {
        String url = VIEW_URL + "?" + buildQuery(Map.of("bvid", bvid));
        JsonObject obj = getJson(url, BiliConfig.BILI_REFERER + "/video/" + bvid);
        ensureCodeZero(obj, "获取视频信息失败");
        return obj.getAsJsonObject("data");
    }

    private String getAudioUrl(String bvid, long cid) throws IOException, InterruptedException {
        ensureWbiKeys();
        // 用 fnval=0 请求渐进式 MP4(durl)：经典(非分片)MP4，jaad 可解码其音频轨。
        // DASH(fnval=4048) 的 .m4s 是 fragmented MP4(moof+trun)，jaad 0.9.6 读不到样本(no valid frame)。
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", cid);
        params.put("fnval", 0);
        params.put("fnver", 0);
        params.put("fourk", 0);
        params.put("qn", 64);
        params.put("otype", "json");
        if (!isLogin) {
            params.put("try_look", 1);
        }
        long wts = Instant.now().getEpochSecond();
        String query = WbiSigner.signToQuery(params, imgKey, subKey, wts);
        JsonObject obj = getJson(PLAYURL_URL + "?" + query, BiliConfig.BILI_REFERER + "/video/" + bvid);
        ensureCodeZero(obj, "获取音频直链失败");
        JsonObject data = obj.has("data") && obj.get("data").isJsonObject()
                ? obj.getAsJsonObject("data") : new JsonObject();
        if (data.has("durl") && data.get("durl").isJsonArray() && data.getAsJsonArray("durl").size() > 0) {
            JsonObject first = data.getAsJsonArray("durl").get(0).getAsJsonObject();
            if (first.has("url") && !first.get("url").isJsonNull()) {
                return first.get("url").getAsString();
            }
        }
        throw new IOException("playurl 未返回 durl(渐进式 MP4): " + data);
    }

    /**
     * 一站式解析：BV -> 视频标题 / 时长 / 音频直链。
     * 并行化：ensureFreshTicket + ensureWbiKeys + getVideoData 三路并行，getAudioUrl 等三者完成后执行。
     */
    public BiliVideoInfo resolve(String bvid) throws IOException, InterruptedException {
        // 三路并行：ticket 刷新 / wbi key 获取 / 视频信息获取（view 接口不需要 wbi 签名）
        CompletableFuture<Void> ticketFuture = CompletableFuture.runAsync(() -> {
            try { ensureFreshTicket(); } catch (Exception e) {
                BiliConfig.LOGGER.warn("并行刷新 ticket 失败（不影响播放）: {}", e.getMessage());
            }
        });
        CompletableFuture<Void> wbiFuture = CompletableFuture.runAsync(() -> {
            try { ensureWbiKeys(); } catch (Exception e) {
                BiliConfig.LOGGER.warn("并行获取 wbi key 失败: {}", e.getMessage());
            }
        });
        CompletableFuture<JsonObject> videoFuture = CompletableFuture.supplyAsync(() -> {
            try { return getVideoData(bvid); } catch (Exception e) {
                throw new RuntimeException("获取视频信息失败: " + bvid, e);
            }
        });

        CompletableFuture.allOf(ticketFuture, wbiFuture, videoFuture).join();
        JsonObject video = videoFuture.join();
        String title = video.has("title") ? video.get("title").getAsString() : bvid;
        long cid = video.has("cid") ? video.get("cid").getAsLong() : 0;
        int duration = video.has("duration") ? video.get("duration").getAsInt() : 0;
        if (cid == 0) {
            throw new IOException("视频 " + bvid + " 缺少 cid");
        }
        String audioUrl = getAudioUrl(bvid, cid);
        return new BiliVideoInfo(bvid, title, duration, audioUrl);
    }

    // ── cookie 手动设置（供配置页使用）────────────────────────────────────
    /** 返回当前存储的 cookie 头字符串，供配置页显示/复制。 */
    public synchronized String getCookieHeader() {
        reloadCookieIfNeeded();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 用用户粘贴的 cookie 头字符串覆盖存储并落盘，刷新 bili_ticket 后请求 nav 确认登录态。
     * 返回 {是否已登录, uname}。含 HTTP，须在工作线程调用。
     */
    public synchronized String[] setCookieHeader(String raw) {
        cookies.clear();
        if (raw != null) {
            for (String part : raw.split(";")) {
                part = part.trim();
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if (!k.isEmpty()) {
                    cookies.put(k, v);
                }
            }
        }
        imgKey = "";
        subKey = "";
        isLogin = false;
        uname = "";
        persistCookies();
        try {
            ensureFreshTicket();
        } catch (Exception e) {
            BiliConfig.LOGGER.warn("setCookie 后刷新 bili_ticket 失败: {}", e.getMessage());
        }
        try {
            fetchNavState();
        } catch (Exception e) {
            BiliConfig.LOGGER.warn("setCookie 后确认 nav 失败: {}", e.getMessage());
        }
        return new String[]{Boolean.toString(isLogin), uname};
    }

    // ── BV 提取 ────────────────────────────────────────────────────────────
    /** 从任意输入（纯 BV、bilibili://BV、网页 URL）中提取 BV 号，找不到返回 null。 */
    public static String extractBvid(String input) {
        if (input == null) {
            return null;
        }
        Matcher m = BV_PATTERN.matcher(input);
        return m.find() ? m.group() : null;
    }
}
