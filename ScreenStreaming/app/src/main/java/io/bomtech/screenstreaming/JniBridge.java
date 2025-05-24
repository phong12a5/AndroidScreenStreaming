package io.bomtech.screenstreaming;

import android.util.Log; // Thêm import này

public class JniBridge {

    private static final String TAG = "JniBridge";
    private static SignalingClient signalingClient;
    private static NativeCallback nativeCallback;
    private static volatile boolean isNativeDataChannelReady = false;
    public static AccessibilityService accessibilityService;

    static {
        System.loadLibrary("screenstreaming");
    }

    // Declare native methods
    public static native void nativeInit(JniBridge ins);
    public static native void nativeDestroy();
    public static native void nativeConfigScreen(int width, int height, int density, int modWidth, int modHeight);
    public static native void nativeStartStreaming();
    public static native void nativeStopStreaming();
    public static native void nativeNewConnection(String clientId);
    public static native void nativeOnAnswerReceived(String clientId, String sdp);
    public static native void nativeOnIceCandidateReceived(String clientId, String sdpMid, int sdpMLineIndex, String sdp);
    public static native void nativeSendCodecConfigData(byte[] data, int size); // New method for codec config
    public static native void nativeSendEncodedFrame(byte[] data, int size, boolean isKeyFrame, long presentationTimeUs); // New method for encoded frames

    // Callbacks from C++ to Java (to be called from native code)
    // These methods will be called by the C++ layer to send SDP offers, answers, or ICE candidates to the Java layer,
    // which can then forward them through the signaling channel.

    // Phương thức để thiết lập SignalingClient từ MainActivity hoặc nơi khác
    public static void setSignalingClient(SignalingClient client) {
        signalingClient = client;
    }

    // Phương thức để thiết lập NativeCallback từ MainActivity hoặc nơi khác
    public static void setNativeCallback(NativeCallback callback) {
        nativeCallback = callback;
    }

    public static void onLocalDescription(String type, String sdp) {
        if (signalingClient != null) {
            signalingClient.sendSdp(type, sdp);
        } else {
            Log.w(TAG, "SignalingClient not set in JniBridge. Cannot send SDP.");
        }
    }

    public static void onLocalIceCandidate(String sdpMid, int sdpMLineIndex, String candidateSdp) {
        Log.d(TAG, "onLocalIceCandidate: sdpMid=" + sdpMid + ", sdpMLineIndex=" + sdpMLineIndex + ", candidate=" + candidateSdp); // Sử dụng Log.d
        if (signalingClient != null) {
            signalingClient.sendIceCandidate(sdpMid, sdpMLineIndex, candidateSdp);
        } else {
            Log.w(TAG, "SignalingClient not set in JniBridge. Cannot send ICE candidate.");
        }
    }

    public static void onDataChannelMessage(String message) {
        Log.d(TAG, "onDataChannelMessage: message=" + message); // Sử dụng Log.d
        // Xử lý tin nhắn từ data channel nếu cần
    }

    // Called from native code when the C++ DataChannel opens
    public static void onNativeDataChannelOpen() {
        Log.i(TAG, "Native DataChannel is now OPEN and ready to send data.");
        isNativeDataChannelReady = true; // SET FLAG
        // TODO: Consider notifying ScreenCaptureService or setting a flag
        // that ScreenCaptureService can check before sending frames.
        if (nativeCallback != null) {
            nativeCallback.onNativeDataChannelOpen();
        } else {
            Log.w(TAG, "NativeCallback not set in JniBridge. Cannot notify DataChannel open.");
        }
    }

    // NEW: Called from native code when the C++ DataChannel closes
    public static void onNativeDataChannelClose() {
        Log.i(TAG, "Native DataChannel is now CLOSED.");
        isNativeDataChannelReady = false; // RESET FLAG
        if (nativeCallback != null) {
            nativeCallback.onNativeDataChannelClose();
        } else {
            Log.w(TAG, "NativeCallback not set in JniBridge. Cannot notify DataChannel close.");
        }
    }

    // NEW: Method for ScreenCaptureService to check DC readiness
    public static boolean isDataChannelReady() {
        return isNativeDataChannelReady;
    }

    public interface NativeCallback {
        public void onNativeDataChannelClose();
        public void onNativeDataChannelOpen();
    }
}
