package com.example.audio_app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.example.audio_app.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity implements WebSocketClient.ReconnectFailedCallback {
    private static final String TAG = "MainActivityCheck";
    private Button recordButton;
    private Button sessionButton;
    private LinearLayout homeLayout;
    private AudioHandler audioHandler;
    private SessionManager sessionManager;
    private ActivityMainBinding binding;
    private ImageView staticPic;

    // ------------测试回音消除------------
    private MediaPlayer mediaPlayer;
    private Button playButton;
    private boolean isPlaying = false;
    // ------------测试回音消除------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 隐藏状态栏和导航栏
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);

        setContentView(R.layout.activity_main);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
        }

        // ------------测试回音消除------------
        mediaPlayer = MediaPlayer.create(this, R.raw.aec_testing);
        mediaPlayer.setLooping(true);
        playButton = findViewById(R.id.playButton);
        // ------------测试回音消除------------

        //gif.
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Glide.with(this)
                .asGif()
                .load(R.drawable.ai_voice_pic)
                .placeholder(R.drawable.static_ai_voice_pic)
                .into(binding.gifView);


        sessionButton = findViewById(R.id.session_button);
        recordButton = findViewById(R.id.record_button);
        homeLayout = findViewById(R.id.home_layout);
        staticPic = findViewById(R.id.static_pic);

        audioHandler = new AudioHandler(getApplicationContext());
        sessionManager = new SessionManager();
        sessionManager.setReconnectFailedCallback(this); // 设置回调
        recordButton.setText("开始交流");
    }

    // 初始化：创建会话，创建websocket客户端，设置相互引用.
    public void initAll(View view) {
        closeAll();

        new Thread(() -> {
            if (sessionManager.createSession()) {
                // 创建WebSocket客户端.
                sessionManager.connectWebSocket(audioHandler);
                // 设置相互引用.
                audioHandler.setWebSocketClient(sessionManager.getWebSocketClient());
                runOnUiThread(() -> {
                    // 开始录音.
                    audioHandler.startRecording();
                    // ui表现.
                    Toast.makeText(MainActivity.this, "会话创建成功！", Toast.LENGTH_SHORT).show();
                    binding.gifView.setVisibility(View.VISIBLE);
                    staticPic.setVisibility(View.GONE);
                    recordButton.setText("停止交流");
                    Log.d(TAG, "会话创建成功！");
                });
            } else {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "会话创建失败！", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "会话创建失败！");
                });
            }
        }).start();
    }

    // 开始交流.
    public void startChatting(View view) {
        if(recordButton.getText() == "开始交流"){

            initAll(view);

        }else{
            //关闭会话.
            closeAll();

            // ui表现.
            binding.gifView.setVisibility(View.GONE);
            staticPic.setVisibility(View.VISIBLE);
            recordButton.setText("开始交流");
        }
    }

    // 关闭所有.
    public void closeAll(){
        try {
            if (sessionManager != null) {
                sessionManager.close();
            }
            if (audioHandler != null) {
                audioHandler.stopRecording();
            }
            Log.d(TAG, "关闭所有");
        } catch (Exception e) {
            Log.e("MainActivity", "关闭资源时出错: " + e.getMessage());
        }
    }

    // ------------测试回音消除------------
    public void aec(View view){
        if (isPlaying) {
            // 如果正在播放，停止播放
            mediaPlayer.pause();
            playButton.setText("播放");
            isPlaying = false;
            Log.d(TAG, "停止音频");
        } else {
            // 如果没有播放，开始播放
            mediaPlayer.start();
            playButton.setText("停止");
            isPlaying = true;
            Log.d(TAG, "开始播放音频");
        }
    }
    // ------------测试回音消除------------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1001 && grantResults.length > 0) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "需要录音权限才能使用此功能", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 实现重连失败回调
    @Override
    public void onReconnectFailed() {
        Log.d(TAG, "重连失败达到最大次数，停止会话");
        Toast.makeText(this, "重连失败达到最大次数，退出会话...", Toast.LENGTH_SHORT).show();

        closeAll();
        binding.gifView.setVisibility(View.GONE);
        staticPic.setVisibility(View.VISIBLE);
        recordButton.setText("开始交流");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeAll();

        // ------------测试回音消除------------
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        // ------------测试回音消除------------
    }
}
