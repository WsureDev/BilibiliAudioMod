package com.github.wsure.bilibiliaudio.command;

import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.wsure.bilibiliaudio.api.BiliClient;
import com.github.wsure.bilibiliaudio.api.BiliVideoInfo;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * /bilicd &lt;BV 号或链接&gt; —— 异步获取视频信息，生成一张带有 bilibili:// 标识的音乐 CD。
 * <p>
 * 不新增物品，复用 NetMusic 的 music_cd；播放时由 {@link com.github.wsure.bilibiliaudio.resolver.BiliSongUrlResolver}
 * 把标识解析为音频直链。
 */
public final class ModCommands {
    private ModCommands() {
    }

    public static void onRegister(RegisterCommandsEvent event) {
        if (!ModList.get().isLoaded(BiliConfig.NETMUSIC_MOD_ID)) {
            BiliConfig.LOGGER.warn("NetMusic 未加载，/bilicd 命令不注册");
            return;
        }
        event.getDispatcher().register(Commands.literal("bilicd")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("bvid", StringArgumentType.greedyString())
                        .executes(ModCommands::run)));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) {
        String input = StringArgumentType.getString(ctx, "bvid");
        String bvid = BiliClient.extractBvid(input);
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("该命令只能由玩家执行"));
            return 0;
        }
        if (bvid == null) {
            ctx.getSource().sendFailure(Component.literal("无法识别 BV 号: " + input));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("正在解析 B 站视频 " + bvid + " ..."), false);

        final String finalBvid = bvid;
        CompletableFuture.supplyAsync(() -> {
            try {
                return BiliClient.getInstance().resolve(finalBvid);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor())
                .whenComplete((info, err) -> {
                    if (err != null) {
                        player.getServer().execute(() -> player.sendSystemMessage(
                                Component.literal("§cB 站视频解析失败: " + err.getMessage())));
                        BiliConfig.LOGGER.error("解析 B 站视频 {} 失败", finalBvid, err);
                        return;
                    }
                    player.getServer().execute(() -> giveCd(player, info));
                });
        return 1;
    }

    private static void giveCd(ServerPlayer player, BiliVideoInfo info) {
        ItemMusicCD.SongInfo song = new ItemMusicCD.SongInfo(
                BiliConfig.BILI_SCHEME + info.getBvid(),
                info.getTitle(),
                info.getDurationSec() > 0 ? info.getDurationSec() : 0,
                false);
        ItemStack stack = new ItemStack(InitItems.MUSIC_CD.get());
        ItemMusicCD.setSongInfo(song, stack);

        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.sendSystemMessage(Component.literal("§a已获得音乐 CD: §6" + info.getTitle()));
    }
}
