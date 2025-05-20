package io.bomtech.screenstreaming;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;

public class SignalingClient {
    private static final String TAG = "SignalingClient";
    private WebSocketClient webSocketClient;
    private SignalingListener listener;

    public interface SignalingListener {
        void onOfferReceived(String sdp);
        void onAnswerReceived(String sdp);
        void onIceCandidateReceived(String sdpMid, int sdpMLineIndex, String sdp);
        void onWebSocketOpen();
        void onWebSocketClose();
        void onWebSocketError(Exception ex);
    }

    public SignalingClient(String serverUrl, SignalingListener listener) {
        this.listener = listener;
        try {
            URI serverUri = new URI(serverUrl);
            webSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "WebSocket opened");
                    if (listener != null) {
                        listener.onWebSocketOpen();
                    }
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "Received message: " + message);
                    try {
                        JSONObject json = new JSONObject(message);
                        String type = json.optString("type");
                        switch (type) {
                            case "offer":
                                if (listener != null) {
                                    listener.onOfferReceived(json.getString("sdp"));
                                }
                                break;
                            case "answer":
                                if (listener != null) {
                                    listener.onAnswerReceived(json.getString("sdp"));
                                }
                                break;
                            case "candidate":
                                if (listener != null) {
                                    JSONObject candidateJson = json.getJSONObject("candidate");
                                    String sdpMid = candidateJson.getString("sdpMid");
                                    int sdpMLineIndex = candidateJson.getInt("sdpMLineIndex");
                                    String sdp = candidateJson.getString("candidate");
                                    listener.onIceCandidateReceived(sdpMid, sdpMLineIndex, sdp);
                                }
                                break;
                            default:
                                Log.w(TAG, "Unknown message type: " + type);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing JSON message", e);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "WebSocket closed, code: " + code + ", reason: " + reason + ", remote: " + remote);
                    if (listener != null) {
                        listener.onWebSocketClose();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket error", ex);
                    if (listener != null) {
                        listener.onWebSocketError(ex);
                    }
                }
            };
        } catch (URISyntaxException e) {
            Log.e(TAG, "Error creating WebSocket URI", e);
            if (listener != null) {
                listener.onWebSocketError(e);
            }
        }
    }

    public void connect() {
        if (webSocketClient != null && !webSocketClient.isOpen()) {
            Log.d(TAG, "Connecting WebSocket...");
            webSocketClient.connect();
        }
    }

    public void disconnect() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            Log.d(TAG, "Disconnecting WebSocket...");
            webSocketClient.close();
        }
    }

    public void sendSdp(String type, String sdp) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject message = new JSONObject();
                message.put("type", type); // "offer" or "answer"
                message.put("sdp", sdp);
                Log.d(TAG, "Sending SDP: " + message.toString());
                webSocketClient.send(message.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Error creating SDP JSON message", e);
            }
        } else {
            Log.w(TAG, "WebSocket not open. Cannot send SDP.");
        }
    }

    public void sendIceCandidate(String sdpMid, int sdpMLineIndex, String candidateSdp) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject message = new JSONObject();
                message.put("type", "candidate");
                JSONObject candidateObj = new JSONObject();
                candidateObj.put("sdpMid", sdpMid);
                candidateObj.put("sdpMLineIndex", sdpMLineIndex);
                candidateObj.put("candidate", candidateSdp);
                message.put("candidate", candidateObj);
                Log.d(TAG, "Sending ICE candidate: " + message.toString());
                webSocketClient.send(message.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Error creating ICE candidate JSON message", e);
            }
        } else {
            Log.w(TAG, "WebSocket not open. Cannot send ICE candidate.");
        }
    }
}
