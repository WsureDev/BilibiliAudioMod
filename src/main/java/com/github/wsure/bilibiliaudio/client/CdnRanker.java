package com.github.wsure.bilibiliaudio.client;

import com.github.wsure.bilibiliaudio.config.BiliConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDN 域名自适应优先级排序器。
 * <p>
 * 记录每个域名的连接耗时和成功/失败次数，按平均耗时排序，慢的往后排。
 * 排名持久化到 config/bilibili_audio/cdn_rank.json，重启后保留。
 */
public final class CdnRanker {

    private static final Path RANK_FILE =
            FMLPaths.CONFIGDIR.get().resolve("bilibili_audio").resolve("cdn_rank.json");

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, DomainStat>>() {}.getType();

    static class DomainStat {
        double avgMs;
        int samples;
        int failures;
        long lastUsed;
    }

    private static final Map<String, DomainStat> STATS = new ConcurrentHashMap<>();

    static {
        load();
    }

    private CdnRanker() {
    }

    /**
     * 记录一次成功的连接耗时。
     */
    public static void recordSuccess(String nodeKey, long elapsedMs) {
        STATS.compute(nodeKey, (k, old) -> {
            DomainStat s = old != null ? old : new DomainStat();
            // 指数移动平均，新数据权重 0.3
            if (s.samples == 0) {
                s.avgMs = elapsedMs;
            } else {
                s.avgMs = s.avgMs * 0.7 + elapsedMs * 0.3;
            }
            s.samples++;
            s.failures = Math.max(0, s.failures - 1); // 成功了就减一个失败计数
            s.lastUsed = System.currentTimeMillis();
            return s;
        });
        save();
    }

    /**
     * 记录一次连接失败。
     */
    public static void recordFailure(String nodeKey) {
        STATS.compute(nodeKey, (k, old) -> {
            DomainStat s = old != null ? old : new DomainStat();
            s.failures++;
            s.lastUsed = System.currentTimeMillis();
            // 失败了把 avgMs 拉高，排到后面
            s.avgMs = s.avgMs * 0.7 + 30000 * 0.3;
            if (s.samples == 0) s.avgMs = 30000;
            return s;
        });
        save();
    }

    /**
     * 按 adaptive 优先级排序 URL 列表。
     * 排序依据：avgMs + failures * 5000ms 惩罚，越小越优先。
     * 没记录过的域名给默认 5000ms，排中间。
     */
    public static List<java.net.URL> sort(List<java.net.URL> urls) {
        if (urls.size() <= 1) return urls;
        List<java.net.URL> result = new ArrayList<>(urls);
        result.sort((a, b) -> Double.compare(scoreOf(nodeKey(a)), scoreOf(nodeKey(b))));
        return result;
    }

    /**
     * 返回某个节点的排名分数，供日志输出。
     */
    public static double scoreOf(String nodeKey) {
        return score(nodeKey);
    }

    private static double score(String nodeKey) {
        DomainStat s = STATS.get(nodeKey);
        if (s == null) return 5000;
        return s.avgMs + s.failures * 5000.0;
    }

    /**
     * 节点唯一标识：host:port。
     */
    private static String nodeKey(java.net.URL url) {
        int port = url.getPort();
        return port == -1 ? url.getHost() : url.getHost() + ":" + port;
    }

    // ── 持久化 ──────────────────────────────────────────────

    private static void save() {
        try {
            Files.createDirectories(RANK_FILE.getParent());
            Files.writeString(RANK_FILE, GSON.toJson(STATS));
        } catch (IOException e) {
            BiliConfig.LOGGER.warn("保存 CDN 排名失败: {}", e.getMessage());
        }
    }

    private static void load() {
        try {
            if (Files.isRegularFile(RANK_FILE)) {
                String json = Files.readString(RANK_FILE);
                Map<String, DomainStat> loaded = GSON.fromJson(json, MAP_TYPE);
                if (loaded != null) {
                    STATS.putAll(loaded);
                    BiliConfig.LOGGER.info("加载 CDN 排名: {} 个域名", STATS.size());
                }
            }
        } catch (Exception e) {
            BiliConfig.LOGGER.warn("加载 CDN 排名失败: {}", e.getMessage());
        }
    }
}
