package io.bomtech.screenstreaming;

import android.util.Log; // Thêm import này

public class JniBridge {

    private static final String TAG = "JniBridge"; // Thêm TAG cho logging
    private static SignalingClient signalingClient; // Thêm tham chiếu đến SignalingClient

    // Load the native library
    static {
        System.loadLibrary("screenstreaming");
    }

    // Declare native methods
    public static native void nativeInit(JniBridge bridgeInstance); // Thay đổi chữ ký
    public static native void nativeStartStreaming();
    public static native void nativeStopStreaming();
    public static native void nativeOnOfferReceived(String sdp);
    public static native void nativeOnAnswerReceived(String sdp);
    public static native void nativeOnIceCandidateReceived(String sdpMid, int sdpMLineIndex, String sdp);
    public static native void nativeSendData(String message); // For sending data from Java to C++
    public static native void nativeSendFrameData(byte[] frameData, int width, int height, int format); // New method for sending frame data

    // Callbacks from C++ to Java (to be called from native code)
    // These methods will be called by the C++ layer to send SDP offers, answers, or ICE candidates to the Java layer,
    // which can then forward them through the signaling channel.

    // Phương thức để thiết lập SignalingClient từ MainActivity hoặc nơi khác
    public static void setSignalingClient(SignalingClient client) {
        signalingClient = client;
    }

    public static void onLocalDescription(String type, String sdp) {
        Log.d(TAG, "onLocalDescription: type=" + type + ", sdp=" + sdp); // Sử dụng Log.d
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
        // TODO: Consider notifying ScreenCaptureService or setting a flag
        // that ScreenCaptureService can check before sending frames.
    }
}
