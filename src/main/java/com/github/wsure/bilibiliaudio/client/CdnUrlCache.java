package com.github.wsure.bilibiliaudio.client;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存 主URL -> 备用URL列表 的映射，供 BiliStreamCore 在主节点不可用时切换。
 */
public final class CdnUrlCache {
    private static final Map<String, List<URL>> CACHE = new ConcurrentHashMap<>();

    private CdnUrlCache() {
    }

    public static void put(String mainUrl, List<String> backupUrls) {
        if (mainUrl == null) return;
        try {
            URL main = new URL(mainUrl);
            java.util.List<URL> urls = new java.util.ArrayList<>();
            urls.add(main);
            if (backupUrls != null) {
                for (String b : backupUrls) {
                    if (b != null && !b.isEmpty()) {
                        urls.add(new URL(b));
                    }
                }
            }
            CACHE.put(mainUrl, Collections.unmodifiableList(urls));
        } catch (Exception ignored) {
        }
    }

    public static List<URL> get(String url) {
        if (url == null) return Collections.emptyList();
        List<URL> urls = CACHE.get(url);
        return urls != null ? urls : Collections.emptyList();
    }
}
