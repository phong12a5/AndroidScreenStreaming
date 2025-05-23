
#ifndef WEBRTCSTREAMER_H
#define WEBRTCSTREAMER_H

#include <rtc/rtc.hpp>
#include <string>
#include <vector>
#include <memory>

class WebRTCStreamer {
public:
    WebRTCStreamer();
    ~WebRTCStreamer();

    void init();
    void startStreaming();
    void stopStreaming();
    void onDataChannelMessage(std::string message);
    void sendDataChannelMessage(std::string message);
    void handleOffer(const std::string& sdp);
    void handleAnswer(const std::string& sdp);
    void handleIceCandidate(const std::string& sdpMid, int sdpMLineIndex, const std::string& sdp);

private:
    std::shared_ptr<rtc::PeerConnection> pc;
    std::shared_ptr<rtc::DataChannel> dc;
    // Add other WebRTC related members
};

#endif // WEBRTCSTREAMER_H
