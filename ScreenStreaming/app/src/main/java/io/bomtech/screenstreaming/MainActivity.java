package io.bomtech.screenstreaming;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import io.bomtech.screenstreaming.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity implements SignalingClient.SignalingListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_MEDIA_PROJECTION = 1;
    // Thay thế bằng địa chỉ IP của máy tính chạy Signaling Server
    // Nếu dùng emulator trên cùng máy: "ws://10.0.2.2:8080"
    // Nếu dùng thiết bị thật trong cùng mạng LAN: "ws://<IP_MAY_TINH_CUA_BAN>:8080"
    private static final String SIGNALING_SERVER_URL = "ws://192.168.1.103:8080";

    private ActivityMainBinding binding;
    private SignalingClient signalingClient;
    private boolean isStreaming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Khởi tạo JNI Bridge và WebRTC Streamer
        // Truyền một instance của JniBridge (hoặc null nếu các callback là static hoàn toàn và không cần context)
        // Tuy nhiên, để nhất quán với việc g_jniBridgeInstance được tạo global ref trong C++ từ jobject,
        // chúng ta nên truyền một instance.
        JniBridge.nativeInit(new JniBridge());

        // Khởi tạo SignalingClient
        signalingClient = new SignalingClient(SIGNALING_SERVER_URL, this);
        JniBridge.setSignalingClient(signalingClient); // Cung cấp signaling client cho JniBridge

        Button startButton = binding.buttonStartStream;
        Button stopButton = binding.buttonStopStream;

        startButton.setOnClickListener(v -> {
            if (!isStreaming) {
                Log.d(TAG, "Attempting to start streaming...");
                signalingClient.connect(); // Kết nối đến signaling server trước
                // Việc startMediaProjection sẽ được gọi trong onWebSocketOpen
            } else {
                Toast.makeText(this, "Already streaming", Toast.LENGTH_SHORT).show();
            }
        });

        stopButton.setOnClickListener(v -> {
            if (isStreaming) {
                Log.d(TAG, "Stopping streaming...");
                JniBridge.nativeStopStreaming();
                signalingClient.disconnect();
                stopScreenCaptureService();
                isStreaming = false;
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Not streaming", Toast.LENGTH_SHORT).show();
            }
        });

        stopButton.setEnabled(false); // Ban đầu nút stop bị vô hiệu hóa
    }

    private void startMediaProjectionRequest() {
        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager != null) {
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        } else {
            Log.e(TAG, "MediaProjectionManager is null");
            Toast.makeText(this, "Cannot start screen capture", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "Media projection permission GRANTED");
                startScreenCaptureService(resultCode, data);
                JniBridge.nativeStartStreaming(); // Bắt đầu WebRTC streaming sau khi có quyền
                isStreaming = true;
                binding.buttonStartStream.setEnabled(false);
                binding.buttonStopStream.setEnabled(true);
                Toast.makeText(this, "Streaming started", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Media projection permission DENIED");
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
                signalingClient.disconnect(); // Ngắt kết nối nếu không có quyền
            }
        }
    }

    private void startScreenCaptureService(int resultCode, Intent data) {
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(ScreenCaptureService.EXTRA_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        }
        Log.d(TAG, "ScreenCaptureService started");
    }

    private void stopScreenCaptureService() {
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        stopService(serviceIntent);
        Log.d(TAG, "ScreenCaptureService stopped");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isStreaming) {
            JniBridge.nativeStopStreaming();
            stopScreenCaptureService();
        }
        if (signalingClient != null) {
            signalingClient.disconnect();
        }
    }

    // --- SignalingListener Callbacks ---
    @Override
    public void onOfferReceived(String sdp) {
        Log.d(TAG, "Offer received from signaling server: " + sdp);
        runOnUiThread(() -> JniBridge.nativeOnOfferReceived(sdp));
    }

    @Override
    public void onAnswerReceived(String sdp) {
        Log.d(TAG, "Answer received from signaling server: " + sdp);
        runOnUiThread(() -> JniBridge.nativeOnAnswerReceived(sdp));
    }

    @Override
    public void onIceCandidateReceived(String sdpMid, int sdpMLineIndex, String sdp) {
        Log.d(TAG, "ICE candidate received: mid=" + sdpMid + ", index=" + sdpMLineIndex + ", sdp=" + sdp);
        runOnUiThread(() -> JniBridge.nativeOnIceCandidateReceived(sdpMid, sdpMLineIndex, sdp));
    }

    @Override
    public void onWebSocketOpen() {
        Log.d(TAG, "WebSocket connection opened.");
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Connected to Signaling Server", Toast.LENGTH_SHORT).show();
            // Sau khi kết nối WebSocket thành công, yêu cầu quyền ghi màn hình
            startMediaProjectionRequest();
        });
    }

    @Override
    public void onWebSocketClose() {
        Log.d(TAG, "WebSocket connection closed.");
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Disconnected from Signaling Server", Toast.LENGTH_SHORT).show();
            if (isStreaming) { // Nếu đang stream mà websocket bị đóng, dừng stream
                JniBridge.nativeStopStreaming();
                stopScreenCaptureService();
                isStreaming = false;
                binding.buttonStartStream.setEnabled(true);
                binding.buttonStopStream.setEnabled(false);
            }
        });
    }

    @Override
    public void onWebSocketError(Exception ex) {
        Log.e(TAG, "WebSocket error: " + ex.getMessage());
        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Signaling error: " + ex.getMessage(), Toast.LENGTH_LONG).show());
    }
}