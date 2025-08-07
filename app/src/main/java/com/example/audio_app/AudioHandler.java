package com.example.audio_app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

import static com.example.audio_app.Config.*;

import android.media.audiofx.AcousticEchoCanceler;

public class AudioHandler {
    private static final String TAG = "AudioHandler";
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private boolean isPlaying = false;
    private WebSocketClient webSocketClient;
    private final Context context;
    private final Deque<byte[]> preAudioBuffer = new ArrayDeque<>(PRE_AUDIO_BUFFER_SIZE);
    private byte[] accumulatedAudio = new byte[0];
    private Long silenceStartTime = null;
    private boolean isVoiceActive = false;

    // ------------回声消除AEC------------
    private AcousticEchoCanceler aec;
    private boolean aecEnabled = false;
    // ------------回声消除AEC------------

    private static final String RECORDINGS_DIR = "audio_recordings";
    private int recordingCounter = 0;

    public AudioHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------回声消除AEC------------
    // 初始化AEC.
    private void initAEC(int audioSessionId) {
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(audioSessionId);
                if (aec != null) {
                    aec.setEnabled(true);
                    aecEnabled = true;
                    Log.d(TAG, "AEC initialized and enabled");
                }
            } catch (Exception e) {
                Log.e(TAG, "AEC initialization failed: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "AEC not available on this device");
        }
    }

    // 设置音频模式为通信模式.
    private void setCommunicationAudioMode() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(true);
        }
    }
    // ------------回声消除AEC------------

    public void setWebSocketClient(WebSocketClient client) {
        this.webSocketClient = client;
    }

    // 开始录音.
    public void startRecording() throws SecurityException {
        // 检查权限.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "没有录音权限");
            throw new SecurityException("缺少RECORD_AUDIO权限");
        }

        int bufferSize = AudioRecord.getMinBufferSize(
                RECORD_RATE,
                RECORD_CHANNELS,
                RECORD_FORMAT);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, //------------回声消除AEC----(MediaRecorder.AudioSource.MIC
                    RECORD_RATE,
                    RECORD_CHANNELS,
                    RECORD_FORMAT,
                    bufferSize);

            //------------回声消除AEC------------
            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                initAEC(audioRecord.getAudioSessionId());
                setCommunicationAudioMode();
            }
            //------------回声消除AEC------------

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord初始化失败");
                return;
            }
            Log.d(TAG, "AudioRecord初始化成功");
            isRecording = true;
            new Thread(this::recordingLoop).start();
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "录音参数错误: " + e.getMessage());
        }
    }

    private void recordingLoop() {
        byte[] buffer = new byte[FRAMES_PER_BUFFER];
        //------------回声消除AEC------------
        byte[] processedBuffer = new byte[FRAMES_PER_BUFFER];
        //------------回声消除AEC------------

        audioRecord.startRecording();
        Log.d(TAG, "开始Recording Loop!");

        try {
            while (isRecording) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead <= 0) {
                    if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.e(TAG, "无效的录音操作");
                    } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "无效的录音参数");
                    }
                    continue;
                }

                //------------回声消除AEC------------
                // 如果AEC启用，处理音频数据
                if (aecEnabled) {
                    System.arraycopy(buffer, 0, processedBuffer, 0, bytesRead);
                    // 这里可以添加更复杂的AEC处理逻辑
                    // 目前只是简单传递，硬件AEC已经在底层工作
                } else {
                    System.arraycopy(buffer, 0, processedBuffer, 0, bytesRead);
                }
                buffer = processedBuffer;
                //------------回声消除AEC------------

                processAudioChunk(buffer, bytesRead);
            }
        } finally {
            // 停止录音并发送剩下语音.
            Log.d(TAG, "结束Recording Loop!");
            stopRecording();
        }
    }

    private void processAudioChunk(byte[] chunk, int length) {

        // 预缓存最近五帧.
        if (preAudioBuffer.size() >= 5) {
            preAudioBuffer.removeFirst();
        }
        preAudioBuffer.addLast(chunk.clone());

        float rms = calculateRms(chunk, length);
        long now = System.currentTimeMillis();

//        Log.d(TAG, "rms:"+rms);
        if (rms > SILENCE_THRESHOLD) {
            //检测到声音.
            handleVoiceActive();
        } else {
            //检测到静默.
            handleSilence(now);
        }
    }

    private void handleVoiceActive() {
        silenceStartTime = null;
        if (!isVoiceActive) {
            // 开始声音活动，包括pre-buffer.
            isVoiceActive = true;
            int totalLength = preAudioBuffer.stream().mapToInt(b -> b.length).sum();
            byte[] result = new byte[totalLength];
            int offset = 0;
            for (byte[] buffer : preAudioBuffer) {
                System.arraycopy(buffer, 0, result, offset, buffer.length);
                offset += buffer.length;
            }
            accumulatedAudio = result;
            Log.d(TAG, "检测到声音，开始录音，包含预缓存");
        } else {
            // 继续累计声音.
            byte[] newBuffer = new byte[accumulatedAudio.length + FRAMES_PER_BUFFER];
            System.arraycopy(accumulatedAudio, 0, newBuffer, 0, accumulatedAudio.length);
            System.arraycopy(preAudioBuffer.getLast(), 0, newBuffer, accumulatedAudio.length, FRAMES_PER_BUFFER);
            accumulatedAudio = newBuffer;
        }
    }

    private void handleSilence(long currentTime) {
        // 静默处理，只有在已录音状态才处理.

        if (!isVoiceActive) return;
        if (silenceStartTime == null) {
            silenceStartTime = currentTime;
            return;
        }
        float silenceDuration = (currentTime - silenceStartTime) / 1000f;


        Log.d(TAG, "静默秒数："+silenceDuration);
        if (silenceDuration >= SHORT_SILENCE_DURATION && silenceDuration < LONG_SILENCE_DURATION) {
            // 短静默，发送audio但不commit.
            if (accumulatedAudio.length > 0) {
                if (accumulatedAudio.length > (5 + 1 + 1) * FRAMES_PER_BUFFER) {
                    Log.d(TAG, String.format("静默≥%.1fs，先发送音频（不 commit）", SHORT_SILENCE_DURATION));
                    sendAudioSegment(accumulatedAudio);
                } else {
                    Log.d(TAG, "音频太短，丢弃");
                }
                accumulatedAudio = new byte[0];
            }
        } else if (silenceDuration >= LONG_SILENCE_DURATION) {
            // 长静默，发送audio并commit.
            Log.d(TAG, String.format("静默≥%.1fs，发送音频并 commit", LONG_SILENCE_DURATION));
            accumulatedAudio = new byte[0];
            isVoiceActive = false;

            isRecording = false;
        }
    }

    public void stopRecording() {
        isRecording = false;
        silenceStartTime = null;
        if (accumulatedAudio.length > 0) {
            sendAudioSegment(accumulatedAudio);
        }
        sendCommit();
        safeReleaseAudioRecord();

        //------------回声消除AEC------------
        // 释放AEC资源
        if (aec != null) {
            aec.setEnabled(false);
            aec.release();
            aec = null;
            aecEnabled = false;
        }
        //------------回声消除AEC------------
    }

    private float calculateRms(byte[] audioData, int length) {
        int sum = 0;
        for (int i = 0; i < length; i += 2) {
            short sample = (short) ((audioData[i + 1] << 8) | audioData[i]);
            sum += sample * sample;
        }
        return (float) Math.sqrt(sum / (length / 2.0));
    }

    private void sendAudioSegment(byte[] pcmData) {
        // 保存录音用作测试.
        if (pcmData.length > 0){
            saveRecordingToFile(pcmData);
        }

        if (webSocketClient != null) {
            webSocketClient.sendAudioData(pcmData);
        }else{
            Log.d(TAG, "webSocketClient连接未就绪，发送audio失败");
        }
    }

    // 保存录音用作测试.
    private void saveRecordingToFile(byte[] pcmData) {
        File recordingsDir = new File(context.getExternalFilesDir(null), RECORDINGS_DIR);
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs();
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "recording_" + timeStamp + ".wav";
        File outputFile = new File(recordingsDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] wavData = webSocketClient.convertPcmToWav(pcmData);
            fos.write(wavData);
            Log.d(TAG, "录音已保存: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "保存录音失败", e);
        }
    }

    private void sendCommit() {
        if (webSocketClient != null) {
            webSocketClient.sendCommit();
        }else{
            Log.d(TAG, "webSocketClient连接未就绪，commit失败");
        }
    }

//    public void playAudio(byte[] pcmData, PlaybackListener listener) {
//        if (isPlaying) {
//            stopPlayback();
//        }
//
//        int bufferSize = AudioTrack.getMinBufferSize(
//                PLAYBACK_RATE,
//                PLAYBACK_CHANNELS,
//                PLAYBACK_FORMAT);
//
//        try {
//            audioTrack = new AudioTrack(
//                    AudioManager.STREAM_MUSIC,
//                    PLAYBACK_RATE,
//                    PLAYBACK_CHANNELS,
//                    PLAYBACK_FORMAT,
//                    bufferSize,
//                    AudioTrack.MODE_STREAM);
//
//            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
//                Log.e(TAG, "AudioTrack初始化失败");
//                return;
//            }
//
//            isPlaying = true;
//            audioTrack.play();
//            audioTrack.write(pcmData, 0, pcmData.length);
//
//            listener.onPlaybackComplete();
//        } catch (IllegalArgumentException e) {
//            Log.e(TAG, "播放参数错误: " + e.getMessage());
//        }
//    }

//    public interface PlaybackListener {
//        void onPlaybackComplete();
//    }

    public void stopPlayback() {
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
                audioTrack.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "停止播放异常: " + e.getMessage());
            }
            audioTrack = null;
        }
        isPlaying = false;
    }

    private void safeReleaseAudioRecord() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                if (audioRecord != null) {
                    audioRecord.release();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "释放录音资源异常: " + e.getMessage());
            }
            audioRecord = null;
        }
    }
}