package com.github.wsure.bilibiliaudio.client;

import com.github.wsure.bilibiliaudio.config.BiliConfig;

import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * B 站音频流处理核心逻辑，不依赖 NetMusic 的接口。
 * 被 {@link BiliAudioStreamHandlerV15}（1.5.1 路径）和 Mixin（1.1.8 路径）复用。
 */
public final class BiliStreamCore {

    private static final int BUFFER_SIZE = 1 << 20;

    private BiliStreamCore() {
    }

    public static boolean canHandle(URL url) {
        if (url == null) {
            return false;
        }
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String path = url.getPath();
        return host.endsWith("bilivideo.com")
                || host.endsWith("bilivideo.net")
                || host.endsWith("bilivideo.cn")
                || host.contains("bilivideo")
                || host.endsWith("mountaintoys.cn")
                || (path != null && path.contains("upgcxcode"));
    }

    public static AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        AudioFileReader jaad = findJaadReader();
        if (jaad != null) {
            File tempFile = downloadToTemp(url);
            try {
                AudioInputStream in = jaad.getAudioInputStream(tempFile);
                BiliConfig.LOGGER.info("jaad 解码就绪: {} Hz, {} bit, {} ch for {}",
                        (int) in.getFormat().getSampleRate(), in.getFormat().getSampleSizeInBits(),
                        in.getFormat().getChannels(), url.getHost());
                final File tf = tempFile;
                return new AudioInputStream(wrapSafe(in), in.getFormat(), in.getFrameLength()) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        deleteTempQuietly(tf);
                    }
                };
            } catch (UnsupportedAudioFileException | IOException e) {
                BiliConfig.LOGGER.warn("jaad RAF 解码失败 {}，回退流式: {}", url.getHost(), e.toString());
                deleteTempQuietly(tempFile);
            }
        }
        return decodeStream(openBuffered(url), url);
    }

    private static File downloadToTemp(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", BiliConfig.USER_AGENT);
        conn.setRequestProperty("Referer", BiliConfig.BILI_REFERER);
        conn.setRequestProperty("Range", "bytes=0-");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        InputStream raw;
        try {
            raw = conn.getInputStream();
        } catch (IOException e) {
            BiliConfig.LOGGER.warn("音频 CDN 连接失败: HTTP {} for {} - {}",
                    conn.getResponseCode(), url.getHost(), e.toString());
            throw e;
        }
        BiliConfig.LOGGER.info("音频 CDN 连接: HTTP {} (Content-Length={}) for {}",
                conn.getResponseCode(), conn.getContentLengthLong(), url.getHost());

        Path tempPath = Files.createTempFile("bili_audio_", ".mp4");
        File tempFile = tempPath.toFile();
        tempFile.deleteOnExit();
        try (var fos = Files.newOutputStream(tempPath)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = raw.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        } finally {
            raw.close();
        }
        return tempFile;
    }

    private static BufferedInputStream openBuffered(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", BiliConfig.USER_AGENT);
        conn.setRequestProperty("Referer", BiliConfig.BILI_REFERER);
        conn.setRequestProperty("Range", "bytes=0-");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        InputStream raw = conn.getInputStream();
        BiliConfig.LOGGER.info("音频 CDN 连接: HTTP {} (Content-Length={}) for {}",
                conn.getResponseCode(), conn.getContentLength(), url.getHost());
        return new BufferedInputStream(wrapSafe(raw), BUFFER_SIZE);
    }

    private static AudioInputStream decodeStream(BufferedInputStream bis, URL url)
            throws UnsupportedAudioFileException, IOException {
        AudioFileReader jaad = findJaadReader();
        if (jaad != null) {
            try {
                AudioInputStream in = jaad.getAudioInputStream(bis);
                BiliConfig.LOGGER.info("jaad 解码就绪: {} Hz for {}", (int) in.getFormat().getSampleRate(), url.getHost());
                return in;
            } catch (UnsupportedAudioFileException e) {
                BiliConfig.LOGGER.warn("jaad 拒绝解码 {}，回退 AudioSystem: {}", url, e.toString());
                bis.close();
                bis = openBuffered(url);
            }
        }
        AudioInputStream in = AudioSystem.getAudioInputStream(bis);
        BiliConfig.LOGGER.info("AudioSystem 解码就绪: {} Hz for {}", (int) in.getFormat().getSampleRate(), url.getHost());
        return in;
    }

    public static InputStream wrapSafe(InputStream raw) {
        return new FilterInputStream(raw) {
            @Override
            public int read() {
                try {
                    return super.read();
                } catch (IOException e) {
                    BiliConfig.LOGGER.warn("音频流读取失败(1B): {}", e.toString());
                    throw new RuntimeException(e);
                }
            }

            @Override
            public int read(byte[] b, int off, int len) {
                try {
                    return super.read(b, off, len);
                } catch (IOException e) {
                    BiliConfig.LOGGER.warn("音频流读取失败({}B): {}", len, e.toString());
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static void deleteTempQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
    }

    private static volatile AudioFileReader cachedJaad;
    private static volatile boolean jaadSearched;

    public static AudioFileReader findJaadReader() {
        if (jaadSearched) {
            return cachedJaad;
        }
        jaadSearched = true;
        String[] candidates = {
                // 本 mod shadow 的 relocate 路径（1.1.8 生产环境主力）
                "com.github.wsure.bilibiliaudio.libs.jaad.spi.javasound.AACAudioFileReader",
                // NetMusic 1.5.1 shadow 的 relocate 路径
                "com.github.tartaricacid.netmusic.soundlibs.net.sourceforge.jaad.spi.javasound.AACAudioFileReader",
                // dev 环境直连
                "net.sourceforge.jaad.spi.javasound.AACAudioFileReader"
        };
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                BiliStreamCore.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (String name : candidates) {
            for (ClassLoader cl : loaders) {
                if (cl == null) {
                    continue;
                }
                try {
                    Class<?> c = Class.forName(name, true, cl);
                    if (AudioFileReader.class.isAssignableFrom(c)) {
                        cachedJaad = (AudioFileReader) c.getDeclaredConstructor().newInstance();
                        BiliConfig.LOGGER.info("找到 jaad AAC reader: {} (classloader={})", name, cl);
                        return cachedJaad;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        BiliConfig.LOGGER.error("未找到 jaad AAC reader，B 站 MP4/AAC 音频将无法解码！"
                + "请确保 Bilibili Audio mod jar 完整（含打包的 jaad 库）。");
        return null;
    }
}
