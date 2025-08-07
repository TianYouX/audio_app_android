package com.example.audio_app;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.example.audio_app.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private Button recordButton;
    private Button sessionButton;
    private TextView statusText;
    private AudioHandler audioHandler;
    private SessionManager sessionManager;
    private ActivityMainBinding binding;

    // 测试回音消除------------
    private MediaPlayer mediaPlayer;
    private Button playButton;
    private boolean isPlaying = false;
    // 测试回音消除------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 测试回音消除------------
        mediaPlayer = MediaPlayer.create(this, R.raw.aec_testing);
        mediaPlayer.setLooping(true);
        playButton = findViewById(R.id.playButton);
        // 测试回音消除------------

        //gif.
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Glide.with(this)
                .asGif()
                .load(R.drawable.ai_voice_pic)
                .into(binding.gifView);


        sessionButton = findViewById(R.id.session_button);
        recordButton = findViewById(R.id.record_button);
        statusText = findViewById(R.id.status_text);

        audioHandler = new AudioHandler(getApplicationContext());
        sessionManager = new SessionManager();
        recordButton.setText("开始交流");
    }

    // 初始化：创建会话，创建websocket客户端，设置相互引用.
    public void initAll(View view) {
        closeAll();
        binding.gifView.setVisibility(View.INVISIBLE);
        recordButton.setText("开始交流");

        new Thread(() -> {
            if (sessionManager.createSession()) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "会话创建成功", Toast.LENGTH_SHORT).show();
                    recordButton.setEnabled(true);
                });
                // 创建WebSocket客户端.
                sessionManager.connectWebSocket(audioHandler);
                // 设置相互引用.
                audioHandler.setWebSocketClient(sessionManager.getWebSocketClient());

                runOnUiThread(() -> statusText.setText("Session ID: " + sessionManager.getSessionId()));
            } else {
                runOnUiThread(() -> {
                    statusText.setText("会话创建失败");
                });
            }
        }).start();
    }

    // 开始交流.
    public void startChatting(View view) {
        if(recordButton.getText() == "开始交流"){
            // 开始录音.
            audioHandler.startRecording();

            // ui表现.
            binding.gifView.setVisibility(View.VISIBLE);
            recordButton.setText("停止交流");
        }else{
            //关闭会话
            closeAll();
            statusText.setText("会话已关闭");
            recordButton.setEnabled(false);

            // ui表现.
            binding.gifView.setVisibility(View.INVISIBLE);
            recordButton.setText("开始交流");
        }
    }

    // 关闭所有.
    public void closeAll(){
        sessionManager.close();
        if (audioHandler != null) {
            audioHandler.stopRecording();
            audioHandler.stopPlayback();
        }
    }

    // 测试回音消除------------
    public void aec(View view){
        if (isPlaying) {
            // 如果正在播放，停止播放
            mediaPlayer.pause();
            playButton.setText("播放");
            isPlaying = false;
            Log.d("MainActivityCheck", "正在播放，停止音频");
        } else {
            // 如果没有播放，开始播放
            mediaPlayer.start();
            playButton.setText("停止");
            isPlaying = true;
            Log.d("MainActivityCheck", "开始播放音频");
        }
    }
    // 测试回音消除------------

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeAll();

        // 测试回音消除------------
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        // 测试回音消除------------
    }
}
