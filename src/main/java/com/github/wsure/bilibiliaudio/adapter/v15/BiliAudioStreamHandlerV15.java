package com.github.wsure.bilibiliaudio.adapter.v15;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.wsure.bilibiliaudio.client.BiliStreamCore;
import com.github.wsure.bilibiliaudio.client.CdnUrlCache;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 * 1.5.1 路径：实现 IAudioStreamHandler 接口，注册到 AudioStreamHandlerManager。
 * 仅在 NetMusic 1.5.1+ 存在时加载。
 */
public class BiliAudioStreamHandlerV15 implements IAudioStreamHandler {
    @Override
    public boolean canHandle(URL url) {
        return BiliStreamCore.canHandle(url);
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        List<URL> urls = CdnUrlCache.get(url.toString());
        if (urls.isEmpty()) urls = Collections.singletonList(url);
        return BiliStreamCore.handle(urls);
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
