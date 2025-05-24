#ifndef WEBRTCSTREAMER_H
#define WEBRTCSTREAMER_H

#include <rtc/rtc.hpp>
#include <string>
#include <utility>
#include <vector>
#include <memory>
#include <thread>               // For std::thread
#include <mutex>                // For std::mutex
#include <condition_variable>   // For std::condition_variable
#include <queue>                // For std::queue
#include <atomic>               // For std::atomic

struct ClientContext {
    std::string id;
    std::shared_ptr<rtc::PeerConnection> pc;
    std::shared_ptr<rtc::DataChannel> dc;
    std::shared_ptr<rtc::Track> track;
    std::shared_ptr<rtc::Candidate> localCandidate;
    std::shared_ptr<rtc::Candidate> remoteCandidate;
    bool isDataChannelOpen;

    explicit ClientContext(std::string  clientId)
        : id(std::move(clientId)), pc(nullptr), isDataChannelOpen(false),
          localCandidate(nullptr), remoteCandidate(nullptr)
        {}
};

struct QueuedFrame {
    rtc::binary data;
    rtc::FrameInfo frameInfo;
    bool isKeyFrame_log;
    int original_size_log;
    int64_t pts_log;

    QueuedFrame() : frameInfo(0), isKeyFrame_log(false), original_size_log(0), pts_log(0) {}
};

class WebRTCStreamer {
public:
    WebRTCStreamer();
    ~WebRTCStreamer();

    void initConnection(std::shared_ptr<ClientContext> &client);
    void startStreaming();
    void stopStreaming();
    void newConnection(const std::string& clientId);
    void handleAnswer(const std::string& clientId, const std::string& sdp);
    void handleIceCandidate(const std::string& clientId, const std::string& sdpMid, int sdpMLineIndex, const std::string& sdp);
    void sendCodecConfigData(const uint8_t* data, int size);
    void sendEncodedFrame(const char* data, int size, bool isKeyFrame, int64_t pts);

private:
    void sendingThreadLoop(); // Declaration for the sending thread's main function

    std::thread m_sendingThread;
    std::queue<QueuedFrame> m_frameQueue;
    std::mutex m_queueMutex;
    std::condition_variable m_queueCondVar;
    std::atomic<bool> m_isStreamingActive{false};
    std::vector<std::byte> stored_codec_config_data;
    std::map<std::string, std::shared_ptr<ClientContext>> clients;
};

#endif // WEBRTCSTREAMER_H
