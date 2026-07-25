package com.github.wsure.bilibiliaudio.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * B 站 WBI 签名，与 bilibili-gate 的 encWbi 对齐：
 * <ol>
 *   <li>拼入 wts 时间戳</li>
 *   <li>过滤 !'()* 字符</li>
 *   <li>按 key 排序后 URL 编码</li>
 *   <li>w_rid = MD5(query + mixin_key)</li>
 * </ol>
 */
public final class WbiSigner {
    /** 打乱表，来自 B 站前端 wbi_img 的 mixin key 推导 */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    /** 签名时需要过滤的特殊字符 */
    private static final IntPredicate CHR_FILTER = c -> "!\'()*".indexOf(c) < 0;

    private WbiSigner() {
    }

    public static String getMixinKey(String imgKey, String subKey) {
        String raw = imgKey + subKey;
        StringBuilder sb = new StringBuilder(32);
        for (int idx : MIXIN_KEY_ENC_TAB) {
            if (idx < raw.length()) {
                sb.append(raw.charAt(idx));
            }
        }
        return sb.substring(0, Math.min(32, sb.length()));
    }

    /**
     * 对参数做 WBI 签名，返回可直接拼到请求 URL 上的完整 query 串（含 wts / w_rid，已 URL 编码、按 key 排序）。
     * <p>
     * MD5 输入与最终请求 query 完全一致，避免双重编码导致的签名不一致。
     *
     * @param params  业务参数（不含 wts / w_rid）
     * @param imgKey  从 nav.wbi_img.img_url 提取的文件名
     * @param subKey  从 nav.wbi_img.sub_url 提取的文件名
     * @param wts     当前 unix 秒（由调用方传入，便于测试与复用）
     */
    public static String signToQuery(Map<String, ?> params, String imgKey, String subKey, long wts) {
        String mixinKey = getMixinKey(imgKey, subKey);

        TreeMap<String, String> sorted = new TreeMap<>();
        sorted.put("wts", Long.toString(wts));
        for (Map.Entry<String, ?> e : params.entrySet()) {
            sorted.put(e.getKey(), filterChars(String.valueOf(e.getValue())));
        }

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }

        String wRid = md5Hex(query.toString() + mixinKey);
        query.append("&w_rid=").append(wRid);
        return query.toString();
    }

    private static String filterChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().filter(CHR_FILTER).forEach(cp -> sb.appendCodePoint(cp));
        return sb.toString();
    }

    /** 与 Python urllib.quote 一致：仅字母数字与 _.-~ 不编码 */
    static String encode(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 3);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '~') {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    public static String md5Hex(String input) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
