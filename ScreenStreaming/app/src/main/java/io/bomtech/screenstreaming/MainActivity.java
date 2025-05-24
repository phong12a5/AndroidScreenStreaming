package io.bomtech.screenstreaming;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.UUID;

import io.bomtech.screenstreaming.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity implements SignalingClient.SignalingListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_MEDIA_PROJECTION = 1;
    // Thay thế bằng địa chỉ IP của máy tính chạy Signaling Server
    // Nếu dùng emulator trên cùng máy: "ws://10.0.2.2:8080"
    // Nếu dùng thiết bị thật trong cùng mạng LAN: "ws://<IP_MAY_TINH_CUA_BAN>:8080"
//    private static final String SIGNALING_SERVER_URL = "ws://104.207.146.14:8080"; // Ensure this is correct//ws://127.0.0.1:8000/
    private static final String SIGNALING_SERVER_URL = "ws://192.168.1.108:8080"; // Ensure this is correct//ws://127.0.0.1:8000/

    private ActivityMainBinding binding;
    private SignalingClient signalingClient;
    private boolean isStreaming = false;
    private boolean isSignalingConnected = false;
    AlertDialog acspDialog = null;
    AlertDialog.Builder acspDialogBuilder = null;
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

        Button startButton = binding.buttonStartStream;

        startButton.setOnClickListener(v -> {
            // check running service
            if (!isStreaming) {
                startButton.setText("Stop Stream");
                startMediaProjectionRequest();
                isStreaming = true;
            } else {
                startButton.setText("Start Stream");
                performStopStreaming();
            }
        });

        JniBridge.setNativeCallback(nativeCallback);

        String androidId = UUID.randomUUID().toString();
        signalingClient = new SignalingClient(SIGNALING_SERVER_URL + "/" + androidId, this);
        JniBridge.setSignalingClient(signalingClient);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isAccessibilitySettingsOn(this)) {
            startAccessibilityPermissionDialog();
        }
    }

    private void performStopStreaming() {
        JniBridge.nativeStopStreaming();
        if (signalingClient != null && isSignalingConnected) {
            signalingClient.disconnect();
        }
        isStreaming = false;
        isSignalingConnected = false; // Reset signaling state
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
                JniBridge.nativeStartStreaming();
                signalingClient.connect();
            } else {
                Log.w(TAG, "Media projection permission DENIED.");
                Toast.makeText(this, "Screen capture permission denied. Cannot start streaming.", Toast.LENGTH_LONG).show();
                if (signalingClient != null && isSignalingConnected) {
                    signalingClient.disconnect(); 
                }
                isSignalingConnected = false;
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

    private void startAccessibilityPermissionDialog() {
        final ImageView image = new ImageView(this);
        if (acspDialogBuilder == null) {
            acspDialogBuilder = new AlertDialog.Builder(this);
            acspDialogBuilder.setView(image);
            acspDialogBuilder.setMessage("On the next screen select " + this.getResources().getString(R.string.app_name) + ", turn on the toggle, and then press OK.");
            acspDialogBuilder.setPositiveButton("next", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                }
            });

            acspDialogBuilder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    startAccessibilityPermissionDialog();
                }
            });
            acspDialog = acspDialogBuilder.create();
        }
        if (!acspDialog.isShowing())
            acspDialog.show();
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
    public void onRequestReceived(String clientId) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "New client connected: " + clientId, Toast.LENGTH_LONG).show();
            JniBridge.nativeNewConnection(clientId);
        });
    }

    @Override
    public void onOfferReceived(String clientId, String sdp) { }

    @Override
    public void onAnswerReceived(String clientId, String sdp) {
        runOnUiThread(() -> JniBridge.nativeOnAnswerReceived(clientId, sdp));
    }

    @Override
    public void onIceCandidateReceived(String clientId, String sdpMid, int sdpMLineIndex, String sdp) {
        Log.d(TAG, "ICE candidate received: mid=" + sdpMid + ", index=" + sdpMLineIndex + ", sdp=" + sdp);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        JniBridge.nativeOnIceCandidateReceived(clientId, sdpMid, sdpMLineIndex, sdp);
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
    }

    @Override
    public void onWebSocketError(Exception ex) {
        Log.e(TAG, "WebSocket error: " + ex.getMessage(), ex);
        isSignalingConnected = false;
    }

    static public boolean isAccessibilitySettingsOn(Context mContext) {
        int accessibilityEnabled = 0;
        final String service = mContext.getPackageName() + "/" + AccessibilityService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    mContext.getApplicationContext().getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
            Log.d(TAG, "accessibilityEnabled = " + accessibilityEnabled);
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "Error finding setting, default accessibility to not found: "
                    + e.getMessage());
        }
        TextUtils.SimpleStringSplitter mStringColonSplitter = new TextUtils.SimpleStringSplitter(':');

        if (accessibilityEnabled == 1) {
            Log.d(TAG, "***ACCESSIBILITY IS ENABLED*** -----------------");
            String settingValue = Settings.Secure.getString(
                    mContext.getApplicationContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue);
                while (mStringColonSplitter.hasNext()) {
                    String accessibilityService = mStringColonSplitter.next();

                    Log.d(TAG, "-------------- > accessibilityService :: " + accessibilityService + " " + service);
                    if (accessibilityService.equalsIgnoreCase(service)) {
                        Log.d(TAG, "We've found the correct setting - accessibility is switched on!");
                        return true;
                    }
                }
            }
        } else {
            Log.d(TAG, "***ACCESSIBILITY IS DISABLED***");
        }
        return false;
    }
}