package com.github.wsure.bilibiliaudio.adapter.v15;

import com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.wsure.bilibiliaudio.resolver.BiliResolveCore;

import java.util.concurrent.CompletableFuture;

/**
 * 1.5.1 路径：实现 IAsyncSongUrlResolver 接口，注册到 MusicPlayResolverManager。
 * 仅在 NetMusic 1.5.1+ 存在时加载。
 */
public class BiliSongUrlResolverV15 implements IAsyncSongUrlResolver {
    @Override
    public boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        return BiliResolveCore.canResolve(songInfo);
    }

    @Override
    public CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        return BiliResolveCore.resolve(songInfo);
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
