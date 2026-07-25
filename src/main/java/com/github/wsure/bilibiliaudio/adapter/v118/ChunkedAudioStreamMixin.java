package com.github.wsure.bilibiliaudio.adapter.v118;

import com.github.tartaricacid.netmusic.client.audio.ChunkedAudioStream;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;

/**
 * 1.1.8 路径：重定向 ChunkedAudioStream.openChunk() 中的 url.openConnection(proxy) 调用，
 * 为 B 站 CDN URL 添加 Referer 和 User-Agent 请求头，绕过防盗链。
 * <p>
 * 1.5.1 的 ChunkedAudioStream 构造函数签名不同（不接受 URL+Proxy），
 * require=0 保证安全跳过。
 */
@Mixin(value = ChunkedAudioStream.class, remap = false)
public class ChunkedAudioStreamMixin {

    @Redirect(
            method = "openChunk",
            at = @At(value = "INVOKE", target = "Ljava/net/URL;openConnection(Ljava/net/Proxy;)Ljava/net/URLConnection;"),
            require = 0
    )
    private URLConnection bilibili_audio$addHeaders(URL url, Proxy proxy) throws java.io.IOException {
        URLConnection conn = url.openConnection(proxy);
        String host = url.getHost();
        if (host != null && (host.contains("bilivideo") || host.endsWith("mountaintoys.cn")
                || (url.getPath() != null && url.getPath().contains("upgcxcode")))) {
            conn.setRequestProperty("User-Agent", BiliConfig.USER_AGENT);
            conn.setRequestProperty("Referer", BiliConfig.BILI_REFERER);
            BiliConfig.LOGGER.info("[兼容] 为 B 站 CDN 添加 Referer: {}", host);
        }
        return conn;
    }
}
