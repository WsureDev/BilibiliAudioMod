package com.github.wsure.bilibiliaudio.adapter.v118;

import com.github.tartaricacid.netmusic.client.audio.MusicPlayManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.wsure.bilibiliaudio.api.BiliClient;
import com.github.wsure.bilibiliaudio.compat.NetMusicCompat;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import com.github.wsure.bilibiliaudio.resolver.BiliResolveCore;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URL;
import java.util.function.Function;
import net.minecraft.client.resources.sounds.SoundInstance;

/**
 * 1.1.8 路径：在 MusicPlayManager.play() HEAD 拦截，如果是 bilibili:// URL，
 * 取消原始调用，异步解析后用 CDN 直链重新调用 play()。
 * 避免阻塞 Sound executor 线程。
 */
@Mixin(value = MusicPlayManager.class, remap = false)
public class MusicPlayManagerMixin {

    @Inject(method = "play(Ljava/lang/String;Ljava/lang/String;Ljava/util/function/Function;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void bilibili_audio$resolveUrlAsync(String url, String songName,
                                                        Function<URL, SoundInstance> sound, CallbackInfo ci) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (!url.startsWith(BiliConfig.BILI_SCHEME) && BiliClient.extractBvid(url) == null) {
            return;
        }
        // 1.5.1+ 已通过 IAsyncSongUrlResolver 注册解析器，跳过避免双重解析
        if (NetMusicCompat.hasResolverManager()) {
            return;
        }
        BiliConfig.LOGGER.info("[兼容] 拦截到 B 站 URL，异步解析: {}", url);
        ci.cancel();

        ItemMusicCD.SongInfo temp = new ItemMusicCD.SongInfo(url, "", 0, false);
        BiliResolveCore.resolve(temp).thenAccept(resolved -> {
            BiliConfig.LOGGER.info("[兼容] B 站 URL 已解析: {}", resolved.songUrl);
            // 异步解析完成后，用 CDN 直链重新调用 play()
            MusicPlayManager.play(resolved.songUrl, songName, sound);
        });
    }
}
