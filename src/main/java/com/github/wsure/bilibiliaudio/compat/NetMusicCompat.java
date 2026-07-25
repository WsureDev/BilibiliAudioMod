package com.github.wsure.bilibiliaudio.compat;

import com.github.wsure.bilibiliaudio.config.BiliConfig;

import java.lang.reflect.Method;

/**
 * 运行时检测 NetMusic 版本，选择不同的注册/集成路径。
 * <p>
 * 1.5.1+ 有 {@code MusicPlayResolverManager} 和 {@code AudioStreamHandlerManager}，
 * 可直接注册 resolver/handler。
 * <p>
 * 1.1.8 没有这些 API，需要通过 Mixin 注入 {@code NetMusicAudioStream} 构造函数。
 */
public final class NetMusicCompat {
    private static final boolean HAS_RESOLVER_MANAGER;
    private static final boolean HAS_STREAM_HANDLER_MANAGER;

    static {
        HAS_RESOLVER_MANAGER = classExists("com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager");
        HAS_STREAM_HANDLER_MANAGER = classExists("com.github.tartaricacid.netmusic.client.api.AudioStreamHandlerManager");
        BiliConfig.LOGGER.info("NetMusic 兼容性检测: resolverManager={}, streamHandlerManager={}",
                HAS_RESOLVER_MANAGER, HAS_STREAM_HANDLER_MANAGER);
    }

    private NetMusicCompat() {
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean hasResolverManager() {
        return HAS_RESOLVER_MANAGER;
    }

    public static boolean hasStreamHandlerManager() {
        return HAS_STREAM_HANDLER_MANAGER;
    }

    /**
     * 1.5.1+ : 通过反射调用 MusicPlayResolverManager.registerResolver()
     */
    public static boolean registerResolver(Object resolver) {
        if (!HAS_RESOLVER_MANAGER) {
            return false;
        }
        try {
            Class<?> mgr = Class.forName("com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager");
            Method m = mgr.getMethod("registerResolver",
                    Class.forName("com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver"));
            m.invoke(null, resolver);
            return true;
        } catch (Exception e) {
            BiliConfig.LOGGER.error("注册 BiliSongUrlResolver 失败", e);
            return false;
        }
    }

    /**
     * 1.5.1+ : 通过反射调用 AudioStreamHandlerManager.registerHandler()
     */
    public static boolean registerStreamHandler(Object handler) {
        if (!HAS_STREAM_HANDLER_MANAGER) {
            return false;
        }
        try {
            Class<?> mgr = Class.forName("com.github.tartaricacid.netmusic.client.api.AudioStreamHandlerManager");
            Method m = mgr.getMethod("registerHandler",
                    Class.forName("com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler"));
            m.invoke(null, handler);
            return true;
        } catch (Exception e) {
            BiliConfig.LOGGER.error("注册 BiliAudioStreamHandler 失败", e);
            return false;
        }
    }
}
