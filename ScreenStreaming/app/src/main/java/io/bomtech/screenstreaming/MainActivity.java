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
    private static final String SIGNALING_SERVER_URL = "ws://192.168.1.103:8080"; // Ensure this is correct

    private ActivityMainBinding binding;
    private SignalingClient signalingClient;
    private boolean isStreaming = false;
    private boolean isSignalingConnected = false; // To track WebSocket connection state
    private String pendingOfferSdp = null; // To store offer SDP until permission is granted
    private java.util.List<Runnable> pendingIceCandidates = new java.util.ArrayList<>(); // MODIFIED: Added list for pending ICE candidates

    private JniBridge.NativeCallback nativeCallback = new JniBridge.NativeCallback() {
        @Override
        public void onNativeDataChannelClose() {
            Log.d(TAG, "Data channel is ready.");
            // Handle data channel readiness if needed
        }

        @Override
        public void onNativeDataChannelOpen() {
            Log.d(TAG, "Data channel is closed.");
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
    }

    private void performStopStreaming() {
        JniBridge.nativeStopStreaming();
        if (signalingClient != null && isSignalingConnected) {
            signalingClient.disconnect();
        }
        stopScreenCaptureService();
        isStreaming = false;
        isSignalingConnected = false; // Reset signaling state
        pendingOfferSdp = null;
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
        } else {
            Log.e(TAG, "MediaProjectionManager is null");
            Toast.makeText(this, "Cannot start screen capture: MediaProjectionManager is null", Toast.LENGTH_LONG).show();
            if (signalingClient != null && isSignalingConnected) {
                signalingClient.disconnect(); // Disconnect if we can't even request permission
            }
            isSignalingConnected = false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "Media projection permission GRANTED.");
                startScreenCaptureService(resultCode, data);

                if (pendingOfferSdp != null) {
                    Log.d(TAG, "Processing pending offer after permission grant.");
                    JniBridge.nativeOnOfferReceived(pendingOfferSdp);
                    pendingOfferSdp = null; // Clear the stored offer

                    // MODIFIED: Process any queued ICE candidates
                    Log.d(TAG, "Processing " + pendingIceCandidates.size() + " pending ICE candidates.");
                    for (Runnable pendingCandidate : pendingIceCandidates) {
                        pendingCandidate.run();
                    }
                    pendingIceCandidates.clear();
                    // END MODIFICATION

                    isStreaming = true;
                    binding.buttonStartStream.setEnabled(false);
                    binding.buttonStopStream.setEnabled(true);
                    Toast.makeText(this, "Streaming started (Answerer)", Toast.LENGTH_SHORT).show();
                } else {
                    Log.w(TAG, "Media projection granted, but no pending offer found.");
                    pendingIceCandidates.clear(); // MODIFIED: Clear candidates here too
                    if (!isStreaming) { 
                        binding.buttonStartStream.setEnabled(true);
                        binding.buttonStopStream.setEnabled(false);
                    }
                }
            } else {
                Log.w(TAG, "Media projection permission DENIED.");
                Toast.makeText(this, "Screen capture permission denied. Cannot start streaming.", Toast.LENGTH_LONG).show();
                pendingOfferSdp = null; 
                pendingIceCandidates.clear(); // MODIFIED: Clear candidates on denial
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
            // If not streaming but signaling is connected (e.g. waiting for offer), disconnect it.
            signalingClient.disconnect();
        }

        JniBridge.setNativeCallback(null);
        // JniBridge.nativeRelease(); // Consider adding a nativeRelease if you have global C++ resources to clean up
    }

    // --- SignalingListener Callbacks ---
    @Override
    public void onOfferReceived(String sdp) {
        Log.d(TAG, "Offer received from signaling server: " + sdp.substring(0, Math.min(sdp.length(), 100)) + "..."); // Log snippet
        if (!isStreaming) {
            pendingOfferSdp = sdp;
            // Request media projection permission. The offer will be processed in onActivityResult.
            runOnUiThread(this::startMediaProjectionRequest);
        } else {
            Log.w(TAG, "Offer received while already streaming. Ignoring.");
        }
        Log.d(TAG, "Offer received. Pending offer SDP: " + pendingOfferSdp);
    }

    @Override
    public void onAnswerReceived(String sdp) {
        // This should not happen if Android is the answerer.
        Log.w(TAG, "Answer received unexpectedly (Android is Answerer): " + sdp.substring(0, Math.min(sdp.length(), 100)) + "...");
        // runOnUiThread(() -> JniBridge.nativeOnAnswerReceived(sdp)); // Commented out
    }

    @Override
    public void onIceCandidateReceived(String sdpMid, int sdpMLineIndex, String sdp) {
        Log.d(TAG, "ICE candidate received: mid=" + sdpMid + ", index=" + sdpMLineIndex + ", sdp=" + sdp);

        // MODIFIED: Queue ICE candidate if offer is pending permission
        if (pendingOfferSdp != null) {
            Log.d(TAG, "Offer is pending, queueing ICE candidate.");
            pendingIceCandidates.add(() -> {
                Log.d(TAG, "Processing queued ICE candidate: mid=" + sdpMid);
                JniBridge.nativeOnIceCandidateReceived(sdpMid, sdpMLineIndex, sdp);
            });
        } else {
            Log.d(TAG, "Offer not pending, processing ICE candidate immediately.");
            runOnUiThread(() -> JniBridge.nativeOnIceCandidateReceived(sdpMid, sdpMLineIndex, sdp));
        }
        // END MODIFICATION
    }

    @Override
    public void onWebSocketOpen() {
        Log.d(TAG, "WebSocket connection opened.");
        isSignalingConnected = true;
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Connected to Signaling Server. Waiting for offer...", Toast.LENGTH_LONG).show();
            // UI can reflect "waiting for offer" state if needed
            // binding.buttonStartStream.setText("Waiting for Offer"); // Example
        });
    }

    @Override
    public void onWebSocketClose() {
        Log.d(TAG, "WebSocket connection closed.");
        isSignalingConnected = false;
        pendingOfferSdp = null; 
        pendingIceCandidates.clear(); // MODIFIED: Clear pending candidates on WS close
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Disconnected from Signaling Server", Toast.LENGTH_SHORT).show();
            if (isStreaming) { 
                Log.d(TAG, "WebSocket closed during streaming. Stopping stream.");
                performStopStreaming();
            } else {
                // If not streaming, just update UI to allow reconnecting
                binding.buttonStartStream.setEnabled(true);
                binding.buttonStopStream.setEnabled(false);
                // binding.buttonStartStream.setText("Start Stream"); // Reset button text
            }
        });
    }

    @Override
    public void onWebSocketError(Exception ex) {
        Log.e(TAG, "WebSocket error: " + ex.getMessage(), ex);
        isSignalingConnected = false;
        pendingOfferSdp = null;
        pendingIceCandidates.clear(); // MODIFIED: Clear pending candidates on WS error
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Signaling error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            if (isStreaming) {
                Log.e(TAG, "WebSocket error during streaming. Stopping stream.");
                performStopStreaming();
            } else {
                binding.buttonStartStream.setEnabled(true);
                binding.buttonStopStream.setEnabled(false);
                // binding.buttonStartStream.setText("Start Stream");
            }
        });
    }
}