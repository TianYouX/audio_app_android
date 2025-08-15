package com.example.audio_app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
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

public class AudioHandler {
    private static final String TAG = "AudioHandler";
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private WebSocketClient webSocketClient;
    private final Context context;
    private final Deque<byte[]> preAudioBuffer = new ArrayDeque<>(PRE_AUDIO_BUFFER_SIZE);
    private byte[] accumulatedAudio = new byte[0];
    private Long silenceStartTime = null;
    private boolean isVoiceActive = false;

    //------------回声消除AEC------------
    private final AECManager aecManager;
    //------------回声消除AEC------------

    private static final String RECORDINGS_DIR = "audio_recordings";

    public AudioHandler(Context context) {
        this.context = context.getApplicationContext();
        //------------回声消除AEC------------
        this.aecManager = new AECManager(context);
        //------------回声消除AEC------------
    }

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
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, //------------回声消除AEC(MediaRecorder.AudioSource.MIC
                    RECORD_RATE,
                    RECORD_CHANNELS,
                    RECORD_FORMAT,
                    bufferSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord初始化失败");
                return;
            }

            //------------回声消除AEC------------
            aecManager.initAEC(audioRecord.getAudioSessionId());
            aecManager.setCommunicationAudioMode();
            //------------回声消除AEC------------

            Log.d(TAG, "AudioRecord初始化成功");
            isRecording = true;

//            new Thread(this::recordingLoop).start();
            new Thread(() -> {
                Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                recordingLoop();
            }).start();

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "录音参数错误: " + e.getMessage());
        }
    }

    private void recordingLoop() {
        byte[] chunk = new byte[FRAMES_PER_BUFFER]; // 2048个字节（即一帧
        //------------回声消除AEC------------
        byte[] processedBuffer = new byte[FRAMES_PER_BUFFER];
        //------------回声消除AEC------------

        audioRecord.startRecording(); // 不是上面的那个startRecording.
        Log.d(TAG, "开始Recording Loop!");

        try {
            while (isRecording) {
                int bytesRead = audioRecord.read(chunk, 0, chunk.length); // 把音频写入buffer数组，返回读取到的字节数
                if (bytesRead <= 0) {
                    if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.e(TAG, "无效的录音操作");
                    } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "无效的录音参数");
                    }
                    continue;
                }

                //------------回声消除AEC------------
                // 使用AECManager处理音频数据
                aecManager.processAudio(chunk, processedBuffer, bytesRead);
                chunk = processedBuffer;
                //------------回声消除AEC------------

                processAudioChunk(chunk, bytesRead);
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
            preAudioBuffer.removeFirst(); // 达到或超过5个音频chunk，就移除最老的一块.
        }
        preAudioBuffer.addLast(chunk.clone());

        float rms = calculateRms(chunk, length);
        long now = System.currentTimeMillis();

//        Log.d(TAG, "rms:"+rms);
        if (rms > SILENCE_THRESHOLD) {
            //检测到声音.
            silenceStartTime = null;
            handleVoiceActive();
        } else {
            //检测到静默.
            handleSilence(now);
        }
    }

    private void handleVoiceActive() {
        if (!isVoiceActive) {
            // 开始声音活动，将预缓存的音频块复制到result数组中.
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
            byte[] newBuffer = new byte[accumulatedAudio.length + FRAMES_PER_BUFFER]; // 拓展一帧.
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


        //Log.d(TAG, "静默秒数："+silenceDuration);
        if (silenceDuration >= SHORT_SILENCE_DURATION && silenceDuration < LONG_SILENCE_DURATION) {
            // 短静默，发送audio但不commit.
            if (accumulatedAudio.length > 0) {
                if (accumulatedAudio.length > (5 + 1 + 1) * FRAMES_PER_BUFFER) { // 至少在预缓存5帧的基础上多3帧
                    Log.d(TAG, String.format("静默≥%.1fs，先发送音频（不 commit）", SHORT_SILENCE_DURATION));
                    sendAudioSegment(accumulatedAudio);
                } else {
                    Log.d(TAG, "音频太短，丢弃");
                }
                accumulatedAudio = new byte[0];
                preAudioBuffer.clear();
//                Log.d(TAG, "短静默后清空预缓存，避免音频重叠");
            }
        } else if (silenceDuration >= LONG_SILENCE_DURATION) {
            // 长静默，发送audio并commit.
            Log.d(TAG, String.format("静默≥%.1fs，发送音频并 commit", LONG_SILENCE_DURATION));
            isVoiceActive = false;
            isRecording = false;
        } else {
            // 短静默阈值之前的帧保留（防止说话过程中短暂音量低于阈值的丢帧.
            handleVoiceActive();
        }
    }

    public void stopRecording() {
        isRecording = false;
        silenceStartTime = null;
        if (accumulatedAudio.length > 0) {
            sendAudioSegment(accumulatedAudio);
        }
        sendCommit();
        accumulatedAudio = new byte[0];
        safeReleaseAudioRecord();

        //------------回声消除AEC------------
        if (aecManager != null) {
            aecManager.releaseAEC();
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
        int length = pcmData.length;

        if (length <= 0) {
            Log.w(TAG, "音频数据无效");
            return;
        }

        // 检查数据长度
        if (length % 2 != 0) {
            Log.w(TAG, "音频数据长度不是偶数: " + length);
        }

        // 计算RMS值
        float rms = calculateRms(pcmData, length);

        // 检查是否有静音数据
        boolean isSilent = rms < 10;

        // 检查数据范围
        short maxSample = 0;
        short minSample = 0;
        for (int i = 0; i < length; i += 2) {
            short sample = (short) ((pcmData[i + 1] << 8) | pcmData[i]);
            if (sample > maxSample) maxSample = sample;
            if (sample < minSample) minSample = sample;
        }

        File recordingsDir = new File(context.getExternalFilesDir(null), RECORDINGS_DIR);
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs();
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "recording_" + timeStamp + ".wav";
        File outputFile = new File(recordingsDir, fileName);

//        // 保存原始PCM数据用于对比
//        try {
//            String pcmFileName = "recording_" + timeStamp + ".pcm";
//            File pcmFile = new File(recordingsDir, pcmFileName);
//            try (FileOutputStream pcmFos = new FileOutputStream(pcmFile)) {
//                pcmFos.write(pcmData);
//                pcmFos.flush();
//                Log.d(TAG, "PCM数据已保存: " + pcmFile.getAbsolutePath() + ", 大小: " + pcmData.length + " 字节");
//            }
//        } catch (IOException e) {
//            Log.e(TAG, "保存PCM数据失败", e);
//        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] wavData = webSocketClient.convertPcmToWav(pcmData);
            fos.write(wavData);
            fos.flush();
            Log.d(TAG, "录音已保存: " + outputFile.getAbsolutePath() + ", 大小: " + wavData.length + " 字节");
        } catch (IOException e) {
            Log.e(TAG, "保存录音失败", e);
        } catch (Exception e) {
            Log.e(TAG, "转换或保存录音时发生未知错误", e);
        }
    }

    private void sendCommit() {
        if (webSocketClient != null) {
            webSocketClient.sendCommit();
        }else{
            Log.d(TAG, "webSocketClient连接未就绪，commit失败");
        }
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