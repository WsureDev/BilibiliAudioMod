package com.github.wsure.bilibiliaudio;

import com.github.wsure.bilibiliaudio.adapter.v15.BiliAudioStreamHandlerV15;
import com.github.wsure.bilibiliaudio.client.BiliLoginScreen;
import com.github.wsure.bilibiliaudio.command.ModCommands;
import com.github.wsure.bilibiliaudio.compat.NetMusicCompat;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import com.github.wsure.bilibiliaudio.adapter.v15.BiliSongUrlResolverV15;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.nio.file.Files;

@Mod(BiliConfig.MOD_ID)
public class BilibiliAudioMod {
    public BilibiliAudioMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.addListener(ModCommands::onRegister);
        BiliConfig.LOGGER.info("Bilibili Audio Player 初始化中（不新增物品，复用 NetMusic）");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        if (!ModList.get().isLoaded(BiliConfig.NETMUSIC_MOD_ID)) {
            BiliConfig.LOGGER.warn("NetMusic 未加载，本 mod 无复用目标，将不生效。请同时安装 NetMusic。");
            return;
        }
        event.enqueueWork(() -> {
            boolean hasCookie = Files.isRegularFile(BiliConfig.COOKIE_FILE);

            if (NetMusicCompat.hasResolverManager()) {
                // 1.5.1+ 路径：注册 resolver
                NetMusicCompat.registerResolver(new BiliSongUrlResolverV15());
                BiliConfig.LOGGER.info("已注册 BiliSongUrlResolver（1.5.1 路径）；cookie 文件 {}",
                        hasCookie ? "已存在" : "不存在");
            } else {
                // 1.1.8 路径：不注册 resolver，靠 NetMusicAudioStreamMixin 在客户端拦截
                BiliConfig.LOGGER.info("NetMusic 1.1.8 模式：使用 Mixin 注入 NetMusicAudioStream；cookie 文件 {}",
                        hasCookie ? "已存在" : "不存在");
            }
        });
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(BiliConfig.MOD_ID).ifPresent(c ->
                c.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                                (mc, parent) -> new BiliLoginScreen(parent))));
        BiliConfig.LOGGER.info("已注册 Bilibili Audio 配置页（Mods 菜单 Config -> Cookie 设置）");

        if (!ModList.get().isLoaded(BiliConfig.NETMUSIC_MOD_ID)) {
            return;
        }
        event.enqueueWork(() -> {
            if (NetMusicCompat.hasStreamHandlerManager()) {
                // 1.5.1+ 路径：注册 stream handler
                NetMusicCompat.registerStreamHandler(new BiliAudioStreamHandlerV15());
                BiliConfig.LOGGER.info("已注册 BiliAudioStreamHandler（1.5.1 路径）");
            } else {
                // 1.1.8 路径：靠 NetMusicAudioStreamMixin 拦截
                BiliConfig.LOGGER.info("NetMusic 1.1.8 模式：使用 Mixin 注入音频流");
            }
        });
    }
}
