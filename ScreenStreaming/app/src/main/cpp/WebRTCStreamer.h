
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
    // Add other necessary methods for WebRTC signaling, media handling, etc.

private:
    std::shared_ptr<rtc::PeerConnection> pc;
    std::shared_ptr<rtc::DataChannel> dc;
    // Add other WebRTC related members
};

#endif // WEBRTCSTREAMER_H
