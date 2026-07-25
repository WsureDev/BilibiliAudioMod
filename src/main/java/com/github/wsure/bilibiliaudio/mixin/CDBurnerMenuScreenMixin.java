package com.github.wsure.bilibiliaudio.mixin;

import com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.SetMusicIDMessage;
import com.github.wsure.bilibiliaudio.api.BiliClient;
import com.github.wsure.bilibiliaudio.api.BiliVideoInfo;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 注入 NetMusic CD 刻录机界面，让玩家直接输入 BV 号制作 B 站唱片。
 * <p>
 * 在原 handleCraftButton() 逻辑之前拦截：若输入匹配 BV 号，走 B 站异步解析，
 * 把 bilibili://BVxxxx 写入 SongInfo 并发包给服务端；否则放行原网易云逻辑。
 */
@Mixin(value = CDBurnerMenuScreen.class, remap = false)
public class CDBurnerMenuScreenMixin {

    @Shadow
    private EditBox textField;

    @Shadow
    private Component tips;

    @Inject(method = "handleCraftButton", at = @At("HEAD"), cancellable = true)
    private void bilibili_audio$handleBv(CallbackInfo ci) {
        String input = textField.getValue();
        if (input == null || input.isEmpty()) {
            return;
        }
        String bvid = BiliClient.extractBvid(input);
        if (bvid == null) {
            return;
        }

        CDBurnerMenuScreen self = (CDBurnerMenuScreen) (Object) this;
        ItemStack cd = self.getMenu().getInput().getStackInSlot(0);
        if (cd.isEmpty()) {
            this.tips = Component.translatable("gui.netmusic.cd_burner.cd_is_empty");
            ci.cancel();
            return;
        }
        ItemMusicCD.SongInfo existing = ItemMusicCD.getSongInfo(cd);
        if (existing != null && existing.readOnly) {
            this.tips = Component.translatable("gui.netmusic.cd_burner.cd_read_only");
            ci.cancel();
            return;
        }

        this.tips = Component.literal("§e正在解析 B 站视频 " + bvid + " ...");
        final String finalBvid = bvid;
        CompletableFuture.supplyAsync(() -> {
            try {
                return BiliClient.getInstance().resolve(finalBvid);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor()).whenComplete((info, err) -> {
            if (err != null) {
                self.getMinecraft().execute(() ->
                        this.tips = Component.literal("§c解析失败: " + err.getMessage()));
                BiliConfig.LOGGER.error("CD 刻录机解析 B 站视频 {} 失败", finalBvid, err);
                return;
            }
            ItemMusicCD.SongInfo song = new ItemMusicCD.SongInfo(
                    BiliConfig.BILI_SCHEME + info.getBvid(),
                    info.getTitle(),
                    info.getDurationSec() > 0 ? info.getDurationSec() : 0,
                    false);
            self.getMinecraft().execute(() -> {
                NetworkHandler.CHANNEL.sendToServer(new SetMusicIDMessage(song));
                this.tips = Component.literal("§a已刻录: §6" + info.getTitle());
            });
        });
        ci.cancel();
    }
}
