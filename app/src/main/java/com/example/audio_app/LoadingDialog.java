package com.example.audio_app;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.util.Log;

public class LoadingDialog extends Dialog {
    private static final String TAG = "LoadingDialog";
    
    private TextView titleText;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button cancelButton;
    private OnCancelListener onCancelListener;
    
    private int currentAttempt = 0;
    private int maxAttempts = 5;
    private boolean isReconnecting = false;

    public interface OnCancelListener {
        void onCancelReconnect();
    }

    public LoadingDialog(Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.loading_dialog);
        
        // 设置弹窗大小和位置
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
        
        initViews();
        setupListeners();
    }

    private void initViews() {
        titleText = findViewById(R.id.loading_title);
        statusText = findViewById(R.id.loading_status);
        progressBar = findViewById(R.id.loading_progress);
        cancelButton = findViewById(R.id.cancel_button);
        
        // 设置进度条最大值
        progressBar.setMax(maxAttempts);
        progressBar.setProgress(0);
    }

    private void setupListeners() {
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onCancelListener != null) {
                    onCancelListener.onCancelReconnect();
                }
                dismiss();
            }
        });
    }

    public void setOnCancelListener(OnCancelListener listener) {
        this.onCancelListener = listener;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        progressBar.setMax(maxAttempts);
    }

    public void startReconnecting() {
        isReconnecting = true;
        currentAttempt = 0;
        updateUI();
        show();
    }

    public void updateAttempt(int attempt) {
        currentAttempt = attempt;
        updateUI();
    }

    public void onReconnectSuccess() {
        isReconnecting = false;
        titleText.setText("重连成功！");
        statusText.setText("连接已恢复");
        progressBar.setProgress(maxAttempts);
        
        // 延迟关闭弹窗
        titleText.postDelayed(new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        }, 1500);
    }

    public void onReconnectFailed() {
        isReconnecting = false;
        titleText.setText("重连失败");
        statusText.setText("已达到最大重连次数");
        progressBar.setProgress(maxAttempts);
        
        // 延迟关闭弹窗
        titleText.postDelayed(new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        }, 2000);
    }

    private void updateUI() {
        if (titleText != null) {
            titleText.setText("正在重连...");
            statusText.setText("第" + currentAttempt + "次尝试重连");
            progressBar.setProgress(currentAttempt);
        }
    }

    public boolean isReconnecting() {
        return isReconnecting;
    }
} 