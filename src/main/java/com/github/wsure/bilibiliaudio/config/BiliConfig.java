package com.github.wsure.bilibiliaudio.config;

import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * 全局常量与日志。不持有可变状态，可被任意 side 引用。
 */
public final class BiliConfig {
    public static final String MOD_ID = "bilibili_audio";
    public static final String MOD_NAME = "Bilibili Audio";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /** NetMusic 的 mod id，用于软依赖判定 */
    public static final String NETMUSIC_MOD_ID = "netmusic";

    /** 存入音乐 CD 的原始标识 scheme，resolver 据此识别 B 站音源 */
    public static final String BILI_SCHEME = "bilibili://";

    /** 浏览器 UA，与 PoC 保持一致以降低风控概率 */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    /** 音频 CDN 防盗链所需 Referer */
    public static final String BILI_REFERER = "https://www.bilibili.com";

    /** GenWebTicket 的 HMAC key */
    public static final String TICKET_HMAC_KEY = "XgwSnGZ1p";

    /** cookie 文件路径：config/bilibili_audio/bili_cookie.txt，内容为 cookie 头字符串 */
    public static final Path COOKIE_FILE =
            FMLPaths.CONFIGDIR.get().resolve("bilibili_audio").resolve("bili_cookie.txt");

    private BiliConfig() {
    }
}
