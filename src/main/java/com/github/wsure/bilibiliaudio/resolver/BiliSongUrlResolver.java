package com.github.wsure.bilibiliaudio.resolver;

import com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.wsure.bilibiliaudio.api.BiliClient;
import com.github.wsure.bilibiliaudio.api.BiliVideoInfo;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import net.minecraft.Util;

import java.util.concurrent.CompletableFuture;

/**
 * 服务端解析器：把音乐 CD 里的 bilibili://BVxxxx 标识，异步解析为可播放的音频直链。
 * <p>
 * 注册到 NetMusic 的 {@link com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager}，
 * 由唱片机方块在播放前调用。解析在工作线程上执行，绝不阻塞主线程。
 */
public class BiliSongUrlResolver implements IAsyncSongUrlResolver {
    @Override
    public boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        if (songInfo == null || songInfo.songUrl == null) {
            return false;
        }
        // bilibili:// 自定义 scheme（命令产物）或含 BV 号的 B 站网页链接（电脑方块产物）
        return songInfo.songUrl.startsWith(BiliConfig.BILI_SCHEME)
                || BiliClient.extractBvid(songInfo.songUrl) != null;
    }

    @Override
    public CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        // songInfo 由调用方深拷贝，可直接在其上修改
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
                // isLogin 由 resolve 内的 nav 刷新决定，反映本次解析是否走登录态
                BiliConfig.LOGGER.info("已解析 B 站音频: {} [{}] -> {}", bvid,
                        BiliClient.getInstance().isLogin() ? "登录态" : "匿名 try_look",
                        info.getAudioUrl());
                return songInfo;
            } catch (Throwable t) {
                // 解析失败时回退为原始信息，NetMusic 会原样下发（客户端无可播放直链则静默失败）
                BiliConfig.LOGGER.error("解析 B 站音频 {} 失败", bvid, t);
                return songInfo;
            }
        }, Util.backgroundExecutor());
    }

    @Override
    public int getPriority() {
        // 高于 NetMusic 自带解析器，确保 bilibili:// 标识优先被本解析器接管
        return 100;
    }
}
