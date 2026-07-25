package com.github.wsure.bilibiliaudio.resolver;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.wsure.bilibiliaudio.api.BiliClient;
import com.github.wsure.bilibiliaudio.api.BiliVideoInfo;
import com.github.wsure.bilibiliaudio.client.CdnUrlCache;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import net.minecraft.Util;

import java.util.concurrent.CompletableFuture;

/**
 * B 站 BV 号解析核心逻辑，不依赖 NetMusic 的接口。
 * 被 {@link BiliSongUrlResolverV15}（1.5.1 路径）和 Mixin（1.1.8 路径）复用。
 */
public final class BiliResolveCore {

    private BiliResolveCore() {
    }

    public static boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        if (songInfo == null || songInfo.songUrl == null) {
            return false;
        }
        return songInfo.songUrl.startsWith(BiliConfig.BILI_SCHEME)
                || BiliClient.extractBvid(songInfo.songUrl) != null;
    }

    /**
     * 异步解析 BV 号为音频直链，直接修改传入的 songInfo。
     */
    public static CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        String bvid = BiliClient.extractBvid(songInfo.songUrl);
        if (bvid == null) {
            BiliConfig.LOGGER.warn("无法从 {} 解析出 BV 号", songInfo.songUrl);
            return CompletableFuture.completedFuture(songInfo);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                BiliVideoInfo info = BiliClient.getInstance().resolve(bvid);
                songInfo.songUrl = info.getAudioUrl();
                if (info.getDurationSec() > 0) {
                    songInfo.songTime = info.getDurationSec();
                }
                if (!BiliClient.getInstance().isLogin() && songInfo.songName != null) {
                    songInfo.songName = "§e[匿名]§r " + songInfo.songName;
                }
                CdnUrlCache.put(info.getAudioUrl(), info.getBackupAudioUrls());
                BiliConfig.LOGGER.info("已解析 B 站音频: {} [{}] -> {} ({} 个 CDN 节点)", bvid,
                        BiliClient.getInstance().isLogin() ? "登录态" : "匿名 try_look",
                        info.getAudioUrl(), 1 + info.getBackupAudioUrls().size());
                return songInfo;
            } catch (Throwable t) {
                BiliConfig.LOGGER.error("解析 B 站音频 {} 失败", bvid, t);
                return songInfo;
            }
        }, Util.backgroundExecutor());
    }
}
