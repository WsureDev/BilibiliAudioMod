package com.github.wsure.bilibiliaudio.api;

import java.util.Collections;
import java.util.List;

/**
 * B 站视频解析结果：可直接用于填充 NetMusic 的 SongInfo。
 */
public final class BiliVideoInfo {
    private final String bvid;
    private final String title;
    private final int durationSec;
    private final String audioUrl;
    private final List<String> backupAudioUrls;

    public BiliVideoInfo(String bvid, String title, int durationSec, String audioUrl) {
        this(bvid, title, durationSec, audioUrl, Collections.emptyList());
    }

    public BiliVideoInfo(String bvid, String title, int durationSec, String audioUrl, List<String> backupAudioUrls) {
        this.bvid = bvid;
        this.title = title;
        this.durationSec = durationSec;
        this.audioUrl = audioUrl;
        this.backupAudioUrls = backupAudioUrls != null ? backupAudioUrls : Collections.emptyList();
    }

    public String getBvid() {
        return bvid;
    }

    public String getTitle() {
        return title;
    }

    public int getDurationSec() {
        return durationSec;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public List<String> getBackupAudioUrls() {
        return backupAudioUrls;
    }
}
