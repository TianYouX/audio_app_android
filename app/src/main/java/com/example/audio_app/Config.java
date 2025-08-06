package com.example.audio_app;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Config {
    // -------------------- 服务端及鉴权配置 --------------------
    public static final String IP_PORT = "ai-bot.universeaction.com";
    public static final String API_BASE_URL = "http://" + IP_PORT;
    public static final String WS_BASE_URL = "ws://" + IP_PORT;
    public static final String AUTHORIZATION_TOKEN = "147258369";

    // -------------------- 音频参数设置 --------------------
    // 录音（输入）：16k、单声道、16位 PCM
    public static final int RECORD_RATE = 16000;
    public static final int RECORD_CHANNELS = 1;
    public static final int RECORD_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT;
    public static final int FRAMES_PER_BUFFER = 2048;

    // 播放（输出）：24k、单声道、16位 PCM
    public static final int PLAYBACK_RATE = 24000;
    public static final int PLAYBACK_CHANNELS = 1;
    public static final int PLAYBACK_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT;

    // -------------------- 音频检测参数 --------------------
    public static final float SHORT_SILENCE_DURATION = 0.5f;
    public static final float LONG_SILENCE_DURATION = 1.5f;
    public static final int SILENCE_THRESHOLD = 800;
    public static final int PRE_AUDIO_BUFFER_SIZE = 5;

    // -------------------- 会话配置 --------------------
    public static final Map<String, Object> SESSION_CONFIG = new HashMap<String, Object>() {{
        put("model", "UNAL_ai_voice");
        put("modalities", Arrays.asList("audio", "text"));
        put("instructions", "普通聊天");
    }};

    // -------------------- UI配置 --------------------
    public static final String WINDOW_TITLE = "录音控制与动态图展示";
    public static final int GIF_RESOURCE_ID = R.drawable.ai_voice_pic; // 对应res/drawable中的资源

    // -------------------- 缓存配置 --------------------
    public static final String AUDIO_CACHE_DIR = "cache_audio";

    // -------------------- 日志配置 --------------------
    public static void configureLogging() {
        // Android默认使用Log类，无需特别配置
    }
}
