package com.github.wsure.bilibiliaudio.client;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.wsure.bilibiliaudio.config.BiliConfig;

import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端音频流处理器：处理 B 站音频 CDN 直链（*.bilivideo.com 等）。
 * <p>
 * 带 Referer 头绕过 B 站防盗链；用 jaad 解码（B 站渐进式 MP4 的音频轨，jaad 可解码）。
 * <p>
 * 实现要点（均经实测）：
 * <ul>
 *   <li>先下载到临时文件再用 jaad 的 RandomAccessFile 路径解析：jaad 的 MP4Container 会遍历所有
 *       box（fragmented MP4 有大量 moof+mdat 对），流式模式下必须读完整个文件才能返回；
 *       改用 RandomAccessFile 后 jaad 支持 seek，可瞬间跳过 mdat，解码就绪从 23s 降到 &lt;1s。</li>
 *   <li>显式优先 jaad 的 AudioFileReader（ServiceLoader 选取，类名含 jaad/AAC，dev 与 NetMusic shadow
 *       relocated 包均兼容），避免 mp3spi 误判 MP4 为 MPEG 导致 ArrayIndexOutOfBounds。</li>
 *   <li>read 时把 IOException 包成 RuntimeException，避免 NetMusicAudioStream 捕获 IOException 后死循环。</li>
 * </ul>
 */
public class BiliAudioStreamHandler implements IAudioStreamHandler {
    private static final int BUFFER_SIZE = 1 << 20;
    private static final long DOWNLOAD_THRESHOLD = 1 << 20;

    @Override
    public boolean canHandle(URL url) {
        if (url == null) {
            return false;
        }
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String path = url.getPath();
        boolean isBiliCdn = host.endsWith("bilivideo.com")
                || host.endsWith("bilivideo.net")
                || host.endsWith("bilivideo.cn")
                || host.contains("bilivideo")
                || host.endsWith("mountaintoys.cn")
                || (path != null && path.contains("upgcxcode"));
        return isBiliCdn;
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        AudioFileReader jaad = findJaadReader();
        if (jaad != null) {
            // 先下载到临时文件，让 jaad 走 RandomAccessFile 路径（支持 seek，可跳过 mdat）
            File tempFile = downloadToTemp(url);
            try {
                AudioInputStream in = jaad.getAudioInputStream(tempFile);
                BiliConfig.LOGGER.info("jaad 解码就绪(RAF): {} Hz, {} bit, {} ch for {}",
                        (int) in.getFormat().getSampleRate(), in.getFormat().getSampleSizeInBits(),
                        in.getFormat().getChannels(), url.getHost());
                return new AudioInputStream(wrapSafe(in), in.getFormat(), in.getFrameLength()) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        deleteTempQuietly(tempFile);
                    }
                };
            } catch (UnsupportedAudioFileException | IOException e) {
                BiliConfig.LOGGER.warn("jaad RAF 解码失败 {}，回退流式: {}", url.getHost(), e.toString());
                deleteTempQuietly(tempFile);
            }
        }
        return decodeStream(openBuffered(url), url);
    }

    private File downloadToTemp(URL url) throws IOException {
        long start = System.currentTimeMillis();
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
            int code = -1;
            try {
                code = conn.getResponseCode();
            } catch (IOException ignored) {
            }
            BiliConfig.LOGGER.warn("音频 CDN 连接失败: HTTP {} for {} - {}", code, url.getHost(), e.toString());
            throw e;
        }
        long contentLength = conn.getContentLengthLong();
        BiliConfig.LOGGER.info("音频 CDN 连接: HTTP {} (Content-Length={}) for {}",
                conn.getResponseCode(), contentLength, url.getHost());

        Path tempPath = Files.createTempFile("bili_audio_", ".mp4");
        File tempFile = tempPath.toFile();
        tempFile.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = raw.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        } finally {
            raw.close();
        }
        long elapsed = System.currentTimeMillis() - start;
        BiliConfig.LOGGER.info("音频文件已下载: {} bytes in {}ms for {}",
                tempFile.length(), elapsed, url.getHost());
        return tempFile;
    }

    private BufferedInputStream openBuffered(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", BiliConfig.USER_AGENT);
        conn.setRequestProperty("Referer", BiliConfig.BILI_REFERER);
        conn.setRequestProperty("Range", "bytes=0-");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        InputStream raw;
        try {
            raw = conn.getInputStream();
        } catch (IOException e) {
            int code = -1;
            try {
                code = conn.getResponseCode();
            } catch (IOException ignored) {
            }
            BiliConfig.LOGGER.warn("音频 CDN 连接失败: HTTP {} for {} - {}", code, url.getHost(), e.toString());
            throw e;
        }
        BiliConfig.LOGGER.info("音频 CDN 连接: HTTP {} (Content-Length={}, Content-Range={}) for {}",
                conn.getResponseCode(), conn.getContentLength(), conn.getHeaderField("Content-Range"), url.getHost());
        return new BufferedInputStream(wrapSafe(raw), BUFFER_SIZE);
    }

    private AudioInputStream decodeStream(BufferedInputStream bis, URL url)
            throws UnsupportedAudioFileException, IOException {
        AudioFileReader jaad = findJaadReader();
        if (jaad != null) {
            try {
                AudioInputStream in = jaad.getAudioInputStream(bis);
                BiliConfig.LOGGER.info("jaad 解码就绪: {} Hz, {} bit, {} ch for {}",
                        (int) in.getFormat().getSampleRate(), in.getFormat().getSampleSizeInBits(),
                        in.getFormat().getChannels(), url.getHost());
                return in;
            } catch (UnsupportedAudioFileException e) {
                BiliConfig.LOGGER.warn("jaad 拒绝解码 {}，回退 AudioSystem: {}", url, e.toString());
                try {
                    bis.close();
                } catch (IOException ignored) {
                }
                bis = openBuffered(url);
            }
        }
        AudioInputStream in = AudioSystem.getAudioInputStream(bis);
        BiliConfig.LOGGER.info("AudioSystem 解码就绪: {} Hz for {}", (int) in.getFormat().getSampleRate(), url.getHost());
        return in;
    }

    private static InputStream wrapSafe(InputStream raw) {
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

    private static AudioFileReader findJaadReader() {
        if (jaadSearched) {
            return cachedJaad;
        }
        jaadSearched = true;
        String[] candidates = {
                "net.sourceforge.jaad.spi.javasound.AACAudioFileReader",
                "com.github.tartaricacid.netmusic.soundlibs.net.sourceforge.jaad.spi.javasound.AACAudioFileReader"
        };
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                BiliAudioStreamHandler.class.getClassLoader(),
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
        BiliConfig.LOGGER.warn("未找到 jaad reader，将回退 AudioSystem（可能导致 mp3spi 误判）");
        return null;
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
