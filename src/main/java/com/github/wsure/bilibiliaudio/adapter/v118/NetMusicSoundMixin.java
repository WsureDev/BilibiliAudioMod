package com.github.wsure.bilibiliaudio.adapter.v118;

import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.github.wsure.bilibiliaudio.client.BiliAudioStreamAdapter;
import com.github.wsure.bilibiliaudio.client.BiliStreamCore;
import com.github.wsure.bilibiliaudio.client.CdnUrlCache;
import com.github.wsure.bilibiliaudio.compat.NetMusicCompat;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import net.minecraft.Util;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.resources.sounds.Sound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URL;
import java.util.concurrent.CompletableFuture;

/**
 * 1.1.8 路径：在 NetMusicSound.getStream() 头部拦截，如果 URL 是 B 站 CDN 直链，
 * 直接用 BiliStreamCore 打开流（jaad 解码 + Referer 头），绕过 NetMusicAudioStream 构造函数。
 * <p>
 * 1.5.1 下 handler 链已处理 B 站 URL，getStream() 内的 NetMusicAudioStream 构造不会失败，
 * 此 Mixin 的 canHandle 检查也会通过但不会造成问题（BiliStreamCore.handle 会返回有效流）。
 * 但为避免双重处理，仅在 1.1.8 模式下（没有 AudioStreamHandlerManager）激活。
 */
@Mixin(value = NetMusicSound.class, remap = false)
public class NetMusicSoundMixin {

    @Shadow
    private URL songUrl;

    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true, require = 0)
    private void bilibili_audio$redirectStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping,
                                                  CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        if (songUrl == null || !BiliStreamCore.canHandle(songUrl)) {
            return;
        }
        // 1.5.1+ 已通过 AudioStreamHandlerManager 注册 handler，跳过避免双重处理
        if (NetMusicCompat.hasStreamHandlerManager()) {
            return;
        }
        BiliConfig.LOGGER.info("[兼容] 拦截 NetMusicSound.getStream，用 BiliStreamCore 打开: {}", songUrl.getHost());
        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            try {
                java.util.List<java.net.URL> urls = CdnUrlCache.get(songUrl.toString());
                if (urls.isEmpty()) urls = java.util.Collections.singletonList(songUrl);
                javax.sound.sampled.AudioInputStream jaadStream = BiliStreamCore.handle(urls);
                return (AudioStream) new BiliAudioStreamAdapter(jaadStream);
            } catch (Exception e) {
                BiliConfig.LOGGER.error("[兼容] BiliStreamCore 打开流失败", e);
                return null;
            }
        }, Util.backgroundExecutor()));
    }
}
