package com.github.wsure.bilibiliaudio.client;

import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.wsure.bilibiliaudio.config.BiliConfig;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 把 javax.sound.sampled.AudioInputStream 适配为 Minecraft 的 AudioStream。
 * 复刻 NetMusicAudioStream 1.1.8 构造函数的格式转换逻辑：
 * 1. jaad 解码为 PCM big-endian
 * 2. AudioSystem 转 PCM_SIGNED 16-bit little-endian
 * 3. 根据 ENABLE_STEREO 配置转 mono/stereo
 */
public class BiliAudioStreamAdapter implements AudioStream {

    private final AudioInputStream stream;
    private final int frameSize;
    private final byte[] frame;

    public BiliAudioStreamAdapter(AudioInputStream jaadStream) throws Exception {
        AudioFormat originalFormat = jaadStream.getFormat();
        BiliConfig.LOGGER.info("[兼容] jaad 原始格式: {} Hz, {} bit, {} ch, bigEndian={}",
                (int) originalFormat.getSampleRate(), originalFormat.getSampleSizeInBits(),
                originalFormat.getChannels(), originalFormat.isBigEndian());

        // 步骤 1: 转为 PCM_SIGNED 16-bit little-endian（和 NetMusicAudioStream 1.1.8 一致）
        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                originalFormat.getSampleRate(), 16,
                originalFormat.getChannels(),
                originalFormat.getChannels() * 2,
                originalFormat.getSampleRate(), false);
        AudioInputStream targetStream = AudioSystem.getAudioInputStream(targetFormat, jaadStream);

        // 步骤 2: 如果 ENABLE_STEREO，转为 mono（和 NetMusicAudioStream 1.1.8 一致）
        if (GeneralConfig.ENABLE_STEREO.get()) {
            AudioFormat monoFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    originalFormat.getSampleRate(), 16,
                    1, 2, originalFormat.getSampleRate(), false);
            this.stream = AudioSystem.getAudioInputStream(monoFormat, targetStream);
        } else {
            this.stream = targetStream;
        }

        this.frameSize = this.stream.getFormat().getFrameSize();
        this.frame = new byte[frameSize];
    }

    @Override
    public AudioFormat getFormat() {
        return stream.getFormat();
    }

    @Override
    public ByteBuffer read(int size) {
        ByteBuffer byteBuffer = BufferUtils.createByteBuffer(size);
        int bytesRead = 0, count = 0;
        do {
            try {
                count = this.stream.read(frame);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (count != -1) {
                byteBuffer.put(frame, 0, count);
            }
        } while (count != -1 && (bytesRead += frameSize) < size);
        byteBuffer.flip();

        // Debug: check first read for non-zero data
        if (firstRead) {
            firstRead = false;
            int maxVal = 0;
            int limit = Math.min(byteBuffer.remaining(), 64);
            for (int i = 0; i < limit - 1; i += 2) {
                short s = (short) ((byteBuffer.get(i) & 0xFF) | (byteBuffer.get(i + 1) << 8));
                if (Math.abs(s) > maxVal) maxVal = Math.abs(s);
            }
            BiliConfig.LOGGER.info("[兼容] 首次 read: {} bytes, max amplitude={} (format={})",
                byteBuffer.remaining(), maxVal, stream.getFormat());
        }

        return byteBuffer;
    }
    private boolean firstRead = true;

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
