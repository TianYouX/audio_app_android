package com.example.audio_app;

import android.media.AudioFormat;

public class Config {
    // -------------------- 服务端及鉴权配置 --------------------
    public static final String IP_PORT = "ai-bot.universeaction.com";
    public static final String API_BASE_URL = "http://" + IP_PORT;
    public static final String WS_BASE_URL = "ws://" + IP_PORT;
    public static final String AUTHORIZATION_TOKEN = "147258369";

    // -------------------- 音频参数设置 --------------------
    // 录音（输入）：16k、单声道、16位 PCM
    public static final int RECORD_RATE = 16000;
    public static final int RECORD_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    public static final int RECORD_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT;
    public static final int FRAMES_PER_BUFFER = 2048;

    // 播放（输出）：24k、单声道、16位 PCM
    public static final int PLAYBACK_RATE = 24000;
    public static final int PLAYBACK_CHANNELS = AudioFormat.CHANNEL_OUT_MONO;
    public static final int PLAYBACK_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT;

    // -------------------- 音频检测参数 --------------------
    public static final float SHORT_SILENCE_DURATION = 0.5f;
    public static final float LONG_SILENCE_DURATION = 3f;
    public static final int SILENCE_THRESHOLD = 200;
    public static final int PRE_AUDIO_BUFFER_SIZE = 5;

    // -------------------- 睡眠时间 --------------------
    public static final int SLEEP_INTERVAL = 0;
}
