package com.example.audio_app;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Log;

// 回声消除管理器，负责管理AEC的初始化、配置和清理.
public class AECManager {
    private static final String TAG = "AECManager";
    
    private final Context context;
    private AcousticEchoCanceler aec;
    private boolean aecEnabled = false;
    private boolean isInitialized = false;

    public AECManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // 初始化AEC
    public boolean initAEC(int audioSessionId) {
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                // 释放之前的AEC实例
                releaseAEC();
                
                // 创建新的AEC实例
                aec = AcousticEchoCanceler.create(audioSessionId);
                if (aec != null) {
                    aec.setEnabled(true);
                    aecEnabled = true;
                    isInitialized = true;
                    Log.d(TAG, "AEC初始化成功");
                    return true;
                } else {
                    Log.w(TAG, "AEC创建失败");
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "AEC初始化失败: " + e.getMessage());
                return false;
            }
        } else {
            Log.w(TAG, "此设备不支持AEC");
            return false;
        }
    }

    // 设置音频模式为通信模式
    public void setCommunicationAudioMode() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(true);
            } catch (Exception e) {
                Log.e(TAG, "设置音频模式失败: " + e.getMessage());
            }
        }
    }

    // 处理音频数据（应用AEC效果）
    public void processAudio(byte[] inputBuffer, byte[] outputBuffer, int length) {
        if (aecEnabled && isInitialized) {
            try {
                // 复制输入数据到输出缓冲区
                System.arraycopy(inputBuffer, 0, outputBuffer, 0, length);
                // 这里可以添加更复杂的AEC处理逻辑
                // 目前硬件AEC已经在底层工作，这里只是数据传递

            } catch (Exception e) {
                // 如果AEC处理失败，直接复制原始数据
                System.arraycopy(inputBuffer, 0, outputBuffer, 0, length);
            }
        } else {
            // AEC未启用，直接复制数据
            System.arraycopy(inputBuffer, 0, outputBuffer, 0, length);
        }
    }

    // 检查AEC是否可用
    public boolean isAECAvailable() {
        return AcousticEchoCanceler.isAvailable();
    }

    // 检查AEC是否已启用
    public boolean isAECEnabled() {
        return aecEnabled && isInitialized;
    }

    // 启用/禁用AEC
    public void setAECEnabled(boolean enabled) {
        if (aec != null && isInitialized) {
            try {
                aec.setEnabled(enabled);
                aecEnabled = enabled;
                Log.d(TAG, "AEC " + (enabled ? "启用" : "禁用"));
            } catch (Exception e) {
                Log.e(TAG, "设置AEC状态失败: " + e.getMessage());
            }
        }
    }

    // 释放AEC资源
    public void releaseAEC() {
        if (aec != null) {
            try {
                if (aecEnabled) {
                    aec.setEnabled(false);
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "AEC禁用时出错: " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "AEC禁用时发生未知错误: " + e.getMessage());
            }

            try {
                aec.release();
            } catch (IllegalStateException e) {
                Log.w(TAG, "AEC释放时出错: " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "AEC释放时发生未知错误: " + e.getMessage());
            }

            aec = null;
            aecEnabled = false;
            isInitialized = false;
            Log.d(TAG, "AEC资源已释放");
        }
    }

    // 获取AEC状态信息
    public String getAECStatus() {
        return String.format("AEC状态 - 可用: %s, 已初始化: %s, 已启用: %s", 
            isAECAvailable(), isInitialized, aecEnabled);
    }
} 