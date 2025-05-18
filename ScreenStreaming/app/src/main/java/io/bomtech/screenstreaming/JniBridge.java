package io.bomtech.screenstreaming;

public class JniBridge {

    // Load the native library
    static {
        System.loadLibrary("screenstreaming");
    }

    // Declare native methods
    public static native void nativeInit();
    public static native void nativeStartStreaming();
    public static native void nativeStopStreaming();
    public static native void nativeOnOfferReceived(String sdp);
    public static native void nativeOnAnswerReceived(String sdp);
    public static native void nativeOnIceCandidateReceived(String sdpMid, int sdpMLineIndex, String sdp);
    public static native void nativeSendData(String message); // For sending data from Java to C++

    // Callbacks from C++ to Java (to be called from native code)
    // These methods will be called by the C++ layer to send SDP offers, answers, or ICE candidates to the Java layer,
    // which can then forward them through the signaling channel.

    public static void onLocalDescription(String type, String sdp) {
        // Implementation: Send this SDP to the remote peer via your signaling channel
        // This could involve using a WebSocket, HTTP POST, etc.
        // For now, we can just log it or update UI if needed.
        System.out.println("JniBridge.onLocalDescription: type=" + type + ", sdp=" + sdp);
//        if (MainActivity.getInstance() != null) {
//            MainActivity.getInstance().runOnUiThread(() -> {
//                // Example: MainActivity.getInstance().sendSdpOffer(sdp);
//                // Or MainActivity.getInstance().sendSdpAnswer(sdp);
//                 if ("offer".equalsIgnoreCase(type)) {
//                    // MainActivity.getInstance().handleOfferSdp(sdp);
//                 } else if ("answer".equalsIgnoreCase(type)) {
//                    // MainActivity.getInstance().handleAnswerSdp(sdp);
//                 }
//            });
//        }
    }

    public static void onLocalIceCandidate(String sdpMid, int sdpMLineIndex, String candidateSdp) {
        // Implementation: Send this ICE candidate to the remote peer via your signaling channel
        System.out.println("JniBridge.onLocalIceCandidate: sdpMid=" + sdpMid + ", sdpMLineIndex=" + sdpMLineIndex + ", candidate=" + candidateSdp);
//        if (MainActivity.getInstance() != null) {
//            MainActivity.getInstance().runOnUiThread(() -> {
//                // Example: MainActivity.getInstance().sendIceCandidate(sdpMid, sdpMLineIndex, candidateSdp);
//            });
//        }
    }

    public static void onDataChannelMessage(String message) {
        // Implementation: Handle message received on the DataChannel from the remote peer
        System.out.println("JniBridge.onDataChannelMessage: message=" + message);
//        if (MainActivity.getInstance() != null) {
//            MainActivity.getInstance().runOnUiThread(() -> {
//                // Example: MainActivity.getInstance().displayReceivedMessage(message);
//            });
//        }
    }
}
