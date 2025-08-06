package com.example.audio_app;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.concurrent.TimeUnit;
import static com.example.audio_app.Config.*;

import android.util.Log;

public class SessionManager {
    private final OkHttpClient client;
    private String sessionId;
    private WebSocketClient webSocketClient;

    public SessionManager() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public boolean createSession() {
        String jsonBody = "{\"model\":\"UNAL_ai_voice\","
                + "\"modalities\":[\"audio\",\"text\"],"
                + "\"instructions\":\"普通聊天\"}";

        Request request = new Request.Builder()
                .url(API_BASE_URL + "/v1/realtime/sessions")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + AUTHORIZATION_TOKEN)
                .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                this.sessionId = new org.json.JSONObject(response.body().string()).getString("id");
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public void connectWebSocket(AudioHandler audioHandler) {
        if (sessionId != null) {
            this.webSocketClient = new WebSocketClient(sessionId, audioHandler);
        }
    }

    public void close() {
        if (webSocketClient != null) {
            webSocketClient.close();
            Log.d("close","关闭websocket成功");
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public WebSocketClient getWebSocketClient() {
        return webSocketClient;
    }
}