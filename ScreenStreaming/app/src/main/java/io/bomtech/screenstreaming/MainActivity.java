package io.bomtech.screenstreaming;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import io.bomtech.screenstreaming.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity implements SignalingClient.SignalingListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_MEDIA_PROJECTION = 1;
    // Thay thế bằng địa chỉ IP của máy tính chạy Signaling Server
    // Nếu dùng emulator trên cùng máy: "ws://10.0.2.2:8080"
    // Nếu dùng thiết bị thật trong cùng mạng LAN: "ws://<IP_MAY_TINH_CUA_BAN>:8080"
    private static final String SIGNALING_SERVER_URL = "ws://192.168.1.109:8080"; // Ensure this is correct//ws://127.0.0.1:8000/

    private ActivityMainBinding binding;
    private SignalingClient signalingClient;
    private boolean isStreaming = false;
    private boolean isSignalingConnected = false; // To track WebSocket connection state
    private JniBridge.NativeCallback nativeCallback = new JniBridge.NativeCallback() {
        @Override
        public void onNativeDataChannelClose() {
            Log.d(TAG, "Data channel is ready.");
            // Handle data channel readiness if needed
        }

        @Override
        public void onNativeDataChannelOpen() {
            Log.d(TAG, "Data channel is opened already.");
        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        JniBridge.nativeInit(new JniBridge());
        signalingClient = new SignalingClient(SIGNALING_SERVER_URL, this);
        JniBridge.setSignalingClient(signalingClient);
        JniBridge.setNativeCallback(nativeCallback);

        Button startButton = binding.buttonStartStream;
        Button stopButton = binding.buttonStopStream;

        startButton.setOnClickListener(v -> {
            if (!isStreaming && !isSignalingConnected) {
                Log.d(TAG, "Start button clicked. Connecting to signaling server...");
                Toast.makeText(this, "Connecting to signaling server...", Toast.LENGTH_SHORT).show();
                signalingClient.connect();
                // UI updates will happen in WebSocket callbacks or onOfferReceived
            } else if (isSignalingConnected && !isStreaming) {
                Toast.makeText(this, "Waiting for offer from web client...", Toast.LENGTH_SHORT).show();
            } else if (isStreaming) {
                Toast.makeText(this, "Already streaming", Toast.LENGTH_SHORT).show();
            }
        });

        stopButton.setOnClickListener(v -> {
            if (isStreaming) {
                Log.d(TAG, "Stop button clicked. Stopping streaming...");
                performStopStreaming();
            } else {
                Toast.makeText(this, "Not streaming", Toast.LENGTH_SHORT).show();
            }
        });

        stopButton.setEnabled(false);
        startMediaProjectionRequest();
    }

    private void performStopStreaming() {
        JniBridge.nativeStopStreaming();
        if (signalingClient != null && isSignalingConnected) {
            signalingClient.disconnect();
        }
        isStreaming = false;
        isSignalingConnected = false; // Reset signaling state
        binding.buttonStartStream.setEnabled(true);
        binding.buttonStopStream.setEnabled(false);
        Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Streaming stopped and resources released.");
    }

    private void startMediaProjectionRequest() {
        Log.d(TAG, "Requesting media projection permission...");
        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager != null) {
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "Media projection permission GRANTED.");
                startScreenCaptureService(resultCode, data);
            } else {
                Log.w(TAG, "Media projection permission DENIED.");
                Toast.makeText(this, "Screen capture permission denied. Cannot start streaming.", Toast.LENGTH_LONG).show();
                if (signalingClient != null && isSignalingConnected) {
                    signalingClient.disconnect(); 
                }
                isSignalingConnected = false;
                binding.buttonStartStream.setEnabled(true); 
                binding.buttonStopStream.setEnabled(false);
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
        Log.d(TAG, "onDestroy called.");
        if (isStreaming) {
            performStopStreaming(); // Use the common stop logic
        } else if (signalingClient != null && isSignalingConnected) {
            signalingClient.disconnect();
        }
        stopScreenCaptureService();
        JniBridge.setNativeCallback(null);
    }

    @Override
    public void onRequestReceived() {
        Log.d(TAG, "Request received from signaling server.");
        runOnUiThread(() -> JniBridge.nativeStartStreaming());
    }

    @Override
    public void onOfferReceived(String sdp) {
        Log.d(TAG, "Offer received from signaling server: " + sdp.substring(0, Math.min(sdp.length(), 100)) + "..."); // Log snippet
        runOnUiThread(() -> JniBridge.nativeOnOfferReceived(sdp));
    }

    @Override
    public void onAnswerReceived(String sdp) {

        runOnUiThread(() -> JniBridge.nativeOnAnswerReceived(sdp));
    }

    @Override
    public void onIceCandidateReceived(String sdpMid, int sdpMLineIndex, String sdp) {
        Log.d(TAG, "ICE candidate received: mid=" + sdpMid + ", index=" + sdpMLineIndex + ", sdp=" + sdp);
//        try {
//            Thread.sleep(1000);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
        JniBridge.nativeOnIceCandidateReceived(sdpMid, sdpMLineIndex, sdp);
    }

    @Override
    public void onWebSocketOpen() {
        Log.d(TAG, "WebSocket connection opened.");
        isSignalingConnected = true;
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Connected to Signaling Server. Waiting for offer...", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onWebSocketClose() {
        Log.d(TAG, "WebSocket connection closed.");
        isSignalingConnected = false;
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Disconnected from Signaling Server", Toast.LENGTH_SHORT).show();
            if (isStreaming) { 
                Log.d(TAG, "WebSocket closed during streaming. Stopping stream.");
                performStopStreaming();
            } else {
                binding.buttonStartStream.setEnabled(true);
                binding.buttonStopStream.setEnabled(false);
            }
        });
    }

    @Override
    public void onWebSocketError(Exception ex) {
        Log.e(TAG, "WebSocket error: " + ex.getMessage(), ex);
        isSignalingConnected = false;
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Signaling error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            if (isStreaming) {
                Log.e(TAG, "WebSocket error during streaming. Stopping stream.");
                performStopStreaming();
            } else {
                binding.buttonStartStream.setEnabled(true);
                binding.buttonStopStream.setEnabled(false);
            }
        });
    }
}