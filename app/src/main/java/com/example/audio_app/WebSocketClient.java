package com.example.audio_app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import android.util.Log;
import androidx.annotation.NonNull;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;
import static com.example.audio_app.Config.*;
import android.content.Context;

public class WebSocketClient {
    private static final String TAG = "WebSocketClient";
    private static final int NORMAL_CLOSURE_STATUS = 1000;
    private static final int MAX_RECONNECT_ATTEMPTS = 3; // 最大重连次数
    private static final long RECONNECT_DELAY_MS = 1000; // 重连延迟时间

    private WebSocket webSocket;
    private final AudioHandler audioHandler;
    private final OkHttpClient client;
    private boolean isConnected = false;
    private final Queue<byte[]> audioQueue = new LinkedList<>();
    private boolean isPlaying = false;
    private AudioTrack audioTrack;
    private boolean isAudioTrackInitialized = false;

    // 重连相关字段
    private String sessionId;
    private boolean shouldReconnect = true;
    private int reconnectAttempts = 0;
    private final Object reconnectLock = new Object();
    private Context context;
    private ReconnectFailedCallback reconnectFailedCallback;

    // 重连失败回调接口
    public interface ReconnectFailedCallback {
        void onReconnectFailed();
    }

    public WebSocketClient(String sessionId, AudioHandler audioHandler, Context context) {
        this.audioHandler = audioHandler;
        this.sessionId = sessionId;
        this.context = context;

        // 配置OkHttpClient (添加超时设置等).
        this.client = new OkHttpClient.Builder()
                .pingInterval(60, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();

        connect(sessionId);
    }

    // 设置回调
    public void setReconnectFailedCallback(ReconnectFailedCallback callback) {
        this.reconnectFailedCallback = callback;
    }

    private void connect(String sessionId) {
        synchronized (reconnectLock) {
            if (webSocket != null) {
                webSocket.cancel();
            }
        }

        Request request = new Request.Builder()
                .url(WS_BASE_URL + "/v1/realtime/sessions/" + sessionId)
                .addHeader("Authorization", "Bearer " + AUTHORIZATION_TOKEN)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                super.onOpen(webSocket, response);
                isConnected = true;
                reconnectAttempts = 0; // 连接成功后重置重连计数
                Log.d(TAG, "WebSocket连接已建立");
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");

                    switch (type) {
                        case "error":
                            Log.w(TAG, "服务器返回错误: " + json.optString("error"));
                            break;
                        case "response.audio.delta":
                            Log.d(TAG, "收到audio delta: " + type);
                            handleAudioDelta(json);
                            break;
                        case "response.audio_transcript.delta":
                            Log.d(TAG, "收到录音转写文本: " + json.optString("delta"));
                            break;
                        case "response.text.delta":
                            Log.d(TAG, "收到回复的文本: " + json.optString("delta"));
                            break;
                        case "response.audio.done":
                            Log.d(TAG, "回复结束标志!");

                            // 回复结束后睡眠2秒.
                            try {
                                Log.d(TAG, "睡眠开始!");
                                Thread.sleep(SLEEP_INTERVAL);
                                Log.d(TAG, "睡眠完毕!");
                            } catch (InterruptedException e) {
                                // Java睡眠需要处理中断异常.
                                Thread.currentThread().interrupt();
                                System.err.println("Sleep interrupted: " + e.getMessage());
                            }

                            audioHandler.startRecording();
                            break;
                        default:
                            Log.d(TAG, "收到未知消息类型: " + type);
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "JSON解析错误: " + e.getMessage());
                }
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                super.onClosed(webSocket, code, reason);
                isConnected = false;
                Log.d(TAG, "连接关闭: " + reason);

                // 如果不是正常关闭，尝试重连
                if (code != NORMAL_CLOSURE_STATUS && shouldReconnect) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                super.onFailure(webSocket, t, response);
                isConnected = false;

                // 详细记录错误信息.
                Log.e(TAG, "连接失败详情:");
                Log.e(TAG, "错误信息: " + t.getMessage());
                Log.e(TAG, "错误类型: " + t.getClass().getSimpleName());
                if (response != null) {
                    Log.e(TAG, "响应码: " + response.code());
                    Log.e(TAG, "响应信息: " + response.message());
                }

                // 连接失败时尝试重连
                if (shouldReconnect) {
                    scheduleReconnect();
                }
            }
        });
    }

    // 调度重连
    private void scheduleReconnect() {

        // 如果达到最大尝试次数 ，通知回调停止会话.
        synchronized (reconnectLock) {
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                if (reconnectFailedCallback != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            reconnectFailedCallback.onReconnectFailed();
                        }
                    });
                }
                return;
            }

            reconnectAttempts++;
            Log.d(TAG, "计划重连，第 " + reconnectAttempts + " 次尝试");

            // 使用Handler延迟执行重连
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (shouldReconnect && !isConnected) {
                        Log.d(TAG, "执行重连...");
                        connect(sessionId);
                    }
                }
            }, RECONNECT_DELAY_MS);
        }
    }

    private void initializeAudioTrack() {
        if (isAudioTrackInitialized) return;

        int bufferSize = AudioTrack.getMinBufferSize(
                PLAYBACK_RATE,
                PLAYBACK_CHANNELS,
                PLAYBACK_FORMAT);

        audioTrack = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                new AudioFormat.Builder()
                        .setSampleRate(PLAYBACK_RATE)
                        .setChannelMask(PLAYBACK_CHANNELS)
                        .setEncoding(PLAYBACK_FORMAT)
                        .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);

        if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            isAudioTrackInitialized = true;
            audioTrack.play();
        }
    }

    private void handleAudioDelta(JSONObject json) throws JSONException {
        String raw_pcm = json.getString("delta");
        byte[] pcmData = Base64.decode(raw_pcm, Base64.DEFAULT);
        synchronized (audioQueue) {
            audioQueue.add(pcmData);
            if (!isPlaying) {
                playNextAudio();
            }
        }
    }

    private void playNextAudio() {
        synchronized (audioQueue) {
            if (audioQueue.isEmpty()) {
                isPlaying = false;
                return;
            }

            isPlaying = true;
            byte[] pcmData = audioQueue.poll();

            // 确保AudioTrack已初始化
            initializeAudioTrack();

            // 直接写入音频数据到已初始化的AudioTrack
            audioTrack.write(pcmData, 0, pcmData.length);

            // 继续播放队列中的下一个音频片段
            playNextAudio();
        }
    }

    public void sendAudioData(byte[] pcmData) {
        if (!isConnected) {
            Log.w(TAG, "尝试发送数据但连接未就绪");
            return;
        }

        try {
            byte[] wavData = convertPcmToWav(pcmData);
            String base64Data = Base64.encodeToString(wavData, Base64.NO_WRAP);

            JSONObject json = new JSONObject();
            json.put("type", "input_audio_buffer.append");
            json.put("event_id", "evt_" + System.currentTimeMillis());
            json.put("audio", base64Data);

            webSocket.send(json.toString());
            Log.d(TAG, "已发送音频数据，长度: " + wavData.length + "字节");
        } catch (JSONException e) {
            Log.e(TAG, "构建JSON消息失败: " + e.getMessage());
        }
    }

    public void sendCommit() {
        if (!isConnected) {
            Log.w(TAG, "尝试发送commit但连接未就绪");
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("type", "input_audio_buffer.commit");
            json.put("event_id", "evt_" + System.currentTimeMillis());

            webSocket.send(json.toString());
            Log.d(TAG, "已发送commit消息");
        } catch (JSONException e) {
            Log.e(TAG, "构建commit消息失败: " + e.getMessage());
        }
    }

    public void close() {
        synchronized (reconnectLock) {
            shouldReconnect = false; // 停止自动重连
        }

        if (webSocket != null) {
            webSocket.close(NORMAL_CLOSURE_STATUS, "用户主动关闭");
        }
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
            isAudioTrackInitialized = false;
        }
        Log.d(TAG, "停止audioTrack");
        isConnected = false;
    }

    // 将pcm转换成wav，加上正确的header. (AI)
    public byte[] convertPcmToWav(byte[] pcmData) {
        // wav header参数
        long totalDataLen = pcmData.length + 36; // 36 is the header size
        long byteRate = RECORD_RATE * 2; // SampleRate * NumChannels * BitsPerSample/8

        byte[] header = new byte[44];

        // RIFF header
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte)(totalDataLen & 0xff);
        header[5] = (byte)((totalDataLen >> 8) & 0xff);
        header[6] = (byte)((totalDataLen >> 16) & 0xff);
        header[7] = (byte)((totalDataLen >> 24) & 0xff);

        // WAVE格式
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';

        // 16 for PCM
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;

        // PCM = 1
        header[20] = 1; header[21] = 0;

        // Mono = 1
        header[22] = 1; header[23] = 0;

        // Sample rate
        header[24] = (byte)(RECORD_RATE & 0xff);
        header[25] = (byte)((RECORD_RATE >> 8) & 0xff);
        header[26] = (byte)((RECORD_RATE >> 16) & 0xff);
        header[27] = (byte)((RECORD_RATE >> 24) & 0xff);

        // Byte rate
        header[28] = (byte)(byteRate & 0xff);
        header[29] = (byte)((byteRate >> 8) & 0xff);
        header[30] = (byte)((byteRate >> 16) & 0xff);
        header[31] = (byte)((byteRate >> 24) & 0xff);

        // Block align
        header[32] = 2; header[33] = 0;

        // Bits per sample
        header[34] = 16; header[35] = 0;

        // Data header
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte)(pcmData.length & 0xff);
        header[41] = (byte)((pcmData.length >> 8) & 0xff);
        header[42] = (byte)((pcmData.length >> 16) & 0xff);
        header[43] = (byte)((pcmData.length >> 24) & 0xff);

        // Combine header and PCM data
        byte[] wavData = new byte[header.length + pcmData.length];
        System.arraycopy(header, 0, wavData, 0, header.length);
        System.arraycopy(pcmData, 0, wavData, header.length, pcmData.length);

        return wavData;
    }
}