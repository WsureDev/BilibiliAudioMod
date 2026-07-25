package com.github.wsure.bilibiliaudio.client;

import com.github.wsure.bilibiliaudio.api.BiliClient;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Bilibili Cookie 配置页（作为本 mod 在 Mods 菜单的 Config 屏幕）。
 * <p>
 * 用户从浏览器 DevTools 复制整串 cookie，粘贴到输入框，点「保存」即可。
 * 保存时会自动刷新 bili_ticket 并请求 nav 确认登录态。
 */
public class BiliLoginScreen extends Screen {
    private final Screen parent;

    private volatile String statusMsg = "";
    private volatile int statusColor = 0xAAAAAA;
    private volatile boolean loggedIn = false;
    private volatile String uname = "";

    private EditBox cookieField;
    private String cookieFieldText;

    public BiliLoginScreen(Screen parent) {
        super(Component.literal("Bilibili Cookie 设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        Font font = Minecraft.getInstance().font;

        int btnH = 20;
        int btnY = height - 28;
        int editY = height - 52;

        if (cookieFieldText == null) {
            cookieFieldText = BiliClient.getInstance().getCookieHeader();
        }
        cookieField = new SelectAllEditBox(font, 20, editY, width - 40, btnH, Component.literal("cookie"));
        cookieField.setMaxLength(8000);
        cookieField.setValue(cookieFieldText);
        cookieField.setHint(Component.literal("粘贴浏览器整串 Cookie 后点「保存」"));
        addRenderableWidget(cookieField);

        int bw = 88;
        int gap = 6;
        int totalW = bw * 2 + gap;
        int bx = (width - totalW) / 2;
        addRenderableWidget(Button.builder(Component.literal("保存"),
                b -> saveCookie()).bounds(bx, btnY, bw, btnH).build());
        addRenderableWidget(Button.builder(Component.literal("完成"),
                b -> onClose()).bounds(bx + bw + gap, btnY, bw, btnH).build());

        CompletableFuture.runAsync(() -> {
            boolean ok = BiliClient.getInstance().checkLogin();
            String name = BiliClient.getInstance().getUname();
            Minecraft.getInstance().execute(() -> {
                loggedIn = ok;
                uname = name;
                if (cookieFieldText == null || cookieFieldText.isEmpty()) {
                    cookieFieldText = BiliClient.getInstance().getCookieHeader();
                    if (cookieField != null) {
                        cookieField.setValue(cookieFieldText);
                    }
                }
            });
        }, Util.backgroundExecutor());
    }

    @Override
    public void tick() {
        super.tick();
        if (cookieField != null) {
            cookieField.tick();
            cookieFieldText = cookieField.getValue();
        }
    }

    private void saveCookie() {
        final String raw = cookieField != null ? cookieField.getValue() : "";
        cookieFieldText = raw;
        statusMsg = "正在保存 Cookie 并确认登录态...";
        statusColor = 0xFFFF55;
        CompletableFuture.runAsync(() -> {
            String[] res = BiliClient.getInstance().setCookieHeader(raw);
            Minecraft.getInstance().execute(() -> {
                loggedIn = Boolean.parseBoolean(res[0]);
                uname = res[1];
                if (loggedIn) {
                    statusMsg = "Cookie 已保存，登录成功：" + uname;
                    statusColor = 0x55FF55;
                } else {
                    statusMsg = "Cookie 已保存，但未确认登录态（cookie 可能已失效）";
                    statusColor = 0xFF5555;
                }
            });
        }, Util.backgroundExecutor());
    }

    @Override
    public void onClose() {
        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Font font = Minecraft.getInstance().font;

        drawCentered(g, font, "Bilibili Cookie 设置", 12, 0xFFFFFF);
        String loginLine = loggedIn ? ("当前已登录：" + uname) : "当前未登录（匿名 try_look 仍可播放多数视频）";
        drawCentered(g, font, loginLine, 26, loggedIn ? 0x55FF55 : 0xAAAAAA);
        drawCentered(g, font, "从浏览器复制整串 Cookie 粘贴到下方输入框，点「保存」", 40, 0xCCCCCC);
        if (!statusMsg.isEmpty()) {
            drawCentered(g, font, statusMsg, 54, statusColor);
        }

        drawCentered(g, font, "Cookie", height - 64, 0xCCCCCC);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawCentered(GuiGraphics g, Font font, String text, int y, int color) {
        g.drawString(font, text, width / 2 - font.width(text) / 2, y, color);
    }

    /** 焦点获取时全选，使粘贴覆盖而非追加。 */
    private static final class SelectAllEditBox extends EditBox {
        SelectAllEditBox(Font font, int x, int y, int w, int h, Component msg) {
            super(font, x, y, w, h, msg);
        }

        @Override
        public void setFocused(boolean focused) {
            boolean was = isFocused();
            super.setFocused(focused);
            if (focused && !was) {
                moveCursorToEnd();
                setHighlightPos(0);
            }
        }
    }
}
