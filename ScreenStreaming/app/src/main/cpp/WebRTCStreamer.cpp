#include "WebRTCStreamer.h"
#include "rtc/rtp.hpp"
#include <rtc/rtc.hpp>
#include <iostream>
#include <jni.h> // Required for JNI calls
#include <android/log.h> // Required for Android logging
#include <vector> // Required for std::vector

// Added for H264RtpPacketizer and related components
#include "rtc/rtppacketizer.hpp"
#include "rtc/h264rtppacketizer.hpp"
#include "rtc/rtcpnackresponder.hpp"
#include "rtc/rtcpsrreporter.hpp"
#include "rtc/nalunit.hpp" // For NalUnit::Separator
#include <chrono>         // For std::chrono

// NEW: Threading and Queueing
#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <atomic>

// For htonl
#ifdef _WIN32
#include <winsock2.h>
#else
#include <arpa/inet.h>
#endif

#if defined(__ANDROID__)
#include <pthread.h>
#include <sys/resource.h> // For setpriority
#include <unistd.h> // for gettid
#endif


// Define log tag for Android logging
#define LOG_TAG "WebRTCStreamer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static std::shared_ptr<WebRTCStreamer> g_webRTCStreamer;
static JavaVM* g_javaVM = nullptr;
static jobject g_jniBridgeInstance = nullptr;

#define MAX_FRAME_QUEUE_SIZE 60

JNIEnv* getJNIEnv() {
    JNIEnv *env;
    if (g_javaVM == nullptr) {
        LOGE("JavaVM is null");
        return nullptr;
    }
    int status = g_javaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        LOGI("Attaching current thread to JavaVM");
        if (g_javaVM->AttachCurrentThread(&env, nullptr) != 0) {
            LOGE("Failed to attach current thread to JavaVM");
            return nullptr;
        }
    } else if (status == JNI_EVERSION) {
        LOGE("JNI version not supported");
        return nullptr;
    } else if (status != JNI_OK) {
        LOGE("Failed to get JNIEnv: %d", status);
        return nullptr;
    }
    return env;
}

// Helper function to convert std::string to jstring
jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// Helper function to convert jstring to std::string
std::string jstringToString(JNIEnv* env, jstring jStr) {
    if (!jStr) return "";
    const char *chars = env->GetStringUTFChars(jStr, nullptr);
    std::string str = chars;
    env->ReleaseStringUTFChars(jStr, chars);
    return str;
}


// JNI OnLoad to cache JavaVM
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad called");
    g_javaVM = vm;
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNIEnv in JNI_OnLoad");
        return JNI_ERR;
    }
    // Find the JniBridge class
    jclass jniBridgeClass = env->FindClass("io/bomtech/screenstreaming/JniBridge");
    if (jniBridgeClass == nullptr) {
        LOGE("Failed to find JniBridge class");
        return JNI_ERR;
    }
    // Create a global reference to an instance of JniBridge (or use a static class if preferred)
    // This part might need adjustment based on how JniBridge is instantiated or accessed in your Java code.
    // If JniBridge has static methods, you might not need g_jniBridgeInstance for calling them.
    // For callbacks, you typically pass jobject instance from Java to native, then make it global ref.
    // For now, we'll assume static methods on JniBridge for callbacks.
    return JNI_VERSION_1_6;
}

WebRTCStreamer::WebRTCStreamer() {
    LOGI("WebRTCStreamer constructor");
    clients.clear();
}

WebRTCStreamer::~WebRTCStreamer() {
    LOGI("WebRTCStreamer destructor");
    stopStreaming();
}

void WebRTCStreamer::initConnection(std::shared_ptr<ClientContext> &client) {
    LOGI("WebRTCStreamer::initConnection: %s", client->id.c_str());
    rtc::Configuration config;

    config.iceServers.emplace_back("stun:stun.l.google.com:19302");
    config.iceServers.emplace_back(rtc::IceServer("turn:149.28.142.115:3478", 3478, "admin", "Pdt1794@")); // Added TURN server

    client->pc = std::make_shared<rtc::PeerConnection>(config);
    auto pc = client->pc;

    pc->onStateChange([this, client](rtc::PeerConnection::State state) {
        LOGI("[%s] PeerConnection State: %d", client->id.c_str(), state);
        if (state == rtc::PeerConnection::State::Disconnected ||
            state == rtc::PeerConnection::State::Failed ||
            state == rtc::PeerConnection::State::Closed) {
            LOGI("PeerConnection closed or failed -> remove client: %s", client->id.c_str());
            this->clients.erase(client->id);
        }
    });

    pc->onTrack([](std::shared_ptr<rtc::Track> track) {
        LOGI("Track received: %s, open: %d, des:", track->mid().c_str(), track->isOpen(), track->description().description().c_str());
    });
    pc->onGatheringStateChange([client](rtc::PeerConnection::GatheringState state) {
        LOGI("[%s] PeerConnection Gathering State: %d", client->id.c_str(), state);
    });

    pc->onLocalDescription([this](rtc::Description description) {
        LOGI("Local Description: type=%s, sdp=%s", description.typeString().c_str(), std::string(description).c_str());
        JNIEnv* env = getJNIEnv();
        if (!env || !g_jniBridgeInstance) {
            LOGE("JNIEnv or JniBridge instance is null in onLocalDescription");
            return;
        }
        jclass cls = env->GetObjectClass(g_jniBridgeInstance);
        jmethodID mid = env->GetStaticMethodID(cls, "onLocalDescription", "(Ljava/lang/String;Ljava/lang/String;)V");
        if (mid == nullptr) {
            LOGE("Failed to find onLocalDescription method");
            env->DeleteLocalRef(cls);
            return;
        }
        jstring typeStr = stringToJstring(env, description.typeString());
        jstring sdpStr = stringToJstring(env, std::string(description));
        env->CallStaticVoidMethod(cls, mid, typeStr, sdpStr);
        env->DeleteLocalRef(cls);
        env->DeleteLocalRef(typeStr);
        env->DeleteLocalRef(sdpStr);
    });

    pc->onLocalCandidate([this](rtc::Candidate candidate) {
        LOGI("Local Candidate: mid=%s, sdp=%s", candidate.mid().c_str(), std::string(candidate).c_str());
        JNIEnv* env = getJNIEnv();
        if (!env || !g_jniBridgeInstance) {
        LOGE("JNIEnv or JniBridge instance is null in onLocalCandidate");
            return;
        }
        
        jclass cls = env->GetObjectClass(g_jniBridgeInstance);
        jmethodID mid = env->GetStaticMethodID(cls, "onLocalIceCandidate", "(Ljava/lang/String;ILjava/lang/String;)V");
        if (mid == nullptr) {
            LOGE("Failed to find onLocalIceCandidate method");
            env->DeleteLocalRef(cls);
            return;
        }
        jstring midStr = stringToJstring(env, candidate.mid());
        jstring candidateStr = stringToJstring(env, std::string(candidate));
        env->CallStaticVoidMethod(cls, mid, midStr, 0 /* sdpMLineIndex placeholder */, candidateStr);
        env->DeleteLocalRef(cls);
        env->DeleteLocalRef(midStr);
        env->DeleteLocalRef(candidateStr);
    });

    // Video track setup using H264RtpPacketizer
    const rtc::SSRC kVideoSSRC = 42; // SSRC for the video track
    uint8_t payloadType = 96;        // H.264 payload type, must match SDP
    std::string cname = "android-screen-stream"; // Canonical Name for RTCP
    std::string msid = "android-stream-id";   // Media Stream ID
    std::string videoTrackId = "video0";      // Track ID for the description

    rtc::Description::Video videoDesc(videoTrackId, rtc::Description::Direction::SendOnly);
    videoDesc.addH264Codec(payloadType); // Payload type 96 for H264
    videoDesc.addSSRC(kVideoSSRC, cname, msid, cname); // Add SSRC information

    client->track = pc->addTrack(videoDesc); // Add track to PeerConnection
    if (client->track) {
        LOGI("Video track added to PeerConnection with SSRC: %u, PT: %u", kVideoSSRC, payloadType);
        auto rtpConfig = std::make_shared<rtc::RtpPacketizationConfig>(
                kVideoSSRC, cname, payloadType, rtc::H264RtpPacketizer::ClockRate
        );

        auto packetizer = std::make_shared<rtc::H264RtpPacketizer>(
                rtc::NalUnit::Separator::LongStartSequence, rtpConfig
        );

        auto srReporter = std::make_shared<rtc::RtcpSrReporter>(rtpConfig);
        packetizer->addToChain(srReporter);

        auto nackResponder = std::make_shared<rtc::RtcpNackResponder>();
        packetizer->addToChain(nackResponder);

        client->track->setMediaHandler(packetizer); // Set the packetizer as the media handler for the track
        LOGI("H264RtpPacketizer (Separator::Length) set as media handler for the video track.");

        client->track->onOpen([this, kVideoSSRC]() {
            LOGI("Video track (SSRC: %u) opened.", kVideoSSRC);
        });
    }


    client->dc = pc->createDataChannel("screenStream"); // REMOVED: DataChannel will be created by the offerer (web client)

    client->dc->onOpen([client]() {
        LOGI("DataChannel from %s is opened", client->id.c_str());
    });

    client->dc->onClosed([client]() {
        LOGI("DataChannel from %s is closed", client->id.c_str());
    });

    client->dc->onMessage([client](auto data) {
        if (std::holds_alternative<std::string>(data))
            LOGI("Message from %s received: %s", client->id.c_str(), std::get<std::string>(data).c_str());
        else
         LOGI("Binary message from %s received, size=%zu", client->id.c_str(), std::get<rtc::binary>(data).size());
    });

    client->pc->setLocalDescription();
}

void WebRTCStreamer::sendingThreadLoop() {
    LOGI("Sending thread started.");

    pid_t tid = gettid();
    if (setpriority(PRIO_PROCESS, tid, -20) != 0) {
        LOGW("Failed to set sending thread priority using setpriority: %s. errno: %d", strerror(errno), errno);
    } else {
        LOGI("Successfully set sending thread priority for tid %d using setpriority.", tid);
    }

    while (m_isStreamingActive) {
        QueuedFrame frame;
        {
            std::unique_lock<std::mutex> lock(m_queueMutex);
            m_queueCondVar.wait(lock, [this] {
                return !m_frameQueue.empty() || !m_isStreamingActive;
            });

            if (!m_isStreamingActive && m_frameQueue.empty()) {
                LOGI("Sending thread stopping: streaming inactive and queue empty.");
                break;
            }
            if (m_frameQueue.empty()) {
                continue;
            }

            frame = std::move(m_frameQueue.front());
            m_frameQueue.pop();
        }

        for (auto& client : clients) {
            auto track = client.second->track;
            if (track && track->isOpen()) {
                try {
                    auto send_start_time = std::chrono::high_resolution_clock::now();

                    track->sendFrame(frame.data, frame.frameInfo);

                    auto send_end_time = std::chrono::high_resolution_clock::now();
                    auto send_duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(send_end_time - send_start_time).count();

                    if (frame.isKeyFrame_log) {
                        LOGD("Frame sent from queue. OrigSize: %d, SentSize: %zu, KeyFrame: %d, PTS: %lld, RTP TS: %lld, SendTime: %lld ms, Queue: %zu",
                             frame.original_size_log, frame.data.size(), frame.isKeyFrame_log,
                             frame.pts_log, frame.frameInfo.timestamp, send_duration_ms,
                             m_frameQueue.size());
                    }
                } catch (const std::exception& e) {
                    LOGE("Exception in sending thread while sending frame (SSRC %u): %s. Queue size: %zu",
                         track->description().getSSRCs().empty() ? 0 : track->description().getSSRCs()[0], e.what(), m_frameQueue.size());
                } catch (...) {
                    LOGE("Unknown exception in sending thread while sending frame (SSRC %u). Queue size: %zu",
                         track->description().getSSRCs().empty() ? 0 : track->description().getSSRCs()[0], m_frameQueue.size());
                }
            } else {
                LOGW("Track is not open or null in sending thread, discarding frame. Frame PTS: %lld. Queue size: %zu", frame.pts_log, m_frameQueue.size());
            }
        }
    }

    LOGI("Sending thread finished. Remaining queue size: %zu", m_frameQueue.size());

    std::lock_guard<std::mutex> lock(m_queueMutex);
    std::queue<QueuedFrame> empty;
    std::swap(m_frameQueue, empty);
    LOGI("Frame queue cleared on sending thread exit.");
}


void WebRTCStreamer::startStreaming() {
    LOGI("WebRTCStreamer::startStreaming");
    if (!m_isStreamingActive || (m_sendingThread.joinable() && m_sendingThread.get_id() == std::thread::id())) {
        LOGI("Attempting to start sending thread.");
        m_isStreamingActive = true;
        // Clear queue before starting, in case of restart
        {
            std::lock_guard<std::mutex> lock(m_queueMutex);
            std::queue<QueuedFrame> empty;
            std::swap(m_frameQueue, empty);
            LOGI("Frame queue cleared before starting new thread.");
        }
        if (m_sendingThread.joinable()) { // If thread object exists but is finished
            m_sendingThread.join(); // Clean up previous thread resource
            LOGI("Joined previous sending thread instance.");
        }
        m_sendingThread = std::thread(&WebRTCStreamer::sendingThreadLoop, this);
        LOGI("Sending thread created.");
    } else {
        LOGI("Sending thread already active or thread object valid.");
    }
}

void WebRTCStreamer::stopStreaming() {
    LOGI("WebRTCStreamer::stopStreaming");

    if (m_isStreamingActive) {
        m_isStreamingActive = false;      // Signal the sending thread to stop
        m_queueCondVar.notify_one(); // Wake up the sending thread if it's waiting
        LOGI("Signaled sending thread to stop.");
    }

    if (m_sendingThread.joinable()) {
        LOGI("Joining sending thread...");
        m_sendingThread.join();
        LOGI("Sending thread joined.");
    } else {
        LOGI("Sending thread was not joinable.");
    }
    
    // Clear the queue one last time after thread has stopped
    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        std::queue<QueuedFrame> empty;
        std::swap(m_frameQueue, empty);
        LOGI("Frame queue cleared in stopStreaming.");
    }


    // release all clients
    clients.clear();
}

void WebRTCStreamer::handleAnswer(const std::string& clientId, const std::string& sdp) {
    LOGI("WebRTCStreamer::handleAnswer -> client: %s", clientId.c_str());
    auto client = clients.find(clientId);
    if (client == clients.end()) {
        LOGE("Client not found in handleAnswer");
        return;
    }
    auto pc = client->second->pc;
    if (!pc) {
        LOGE("PeerConnection not initialized in handleAnswer");
        return;
    }

    rtc::Description answer(sdp, "answer");
    pc->setRemoteDescription(answer);
}

void WebRTCStreamer::handleIceCandidate(const std::string& clientId, const std::string& sdpMid, int sdpMLineIndex, const std::string& sdp) {
    LOGI("WebRTCStreamer::handleIceCandidate -> client: %s", clientId.c_str());
    auto client = clients.find(clientId);
    if (client == clients.end()) {
        LOGE("Client not found in handleAnswer");
        return;
    }
    auto pc = client->second->pc;
    if (!pc) {
        LOGE("PeerConnection not initialized in handleIceCandidate");
        return;
    }
    rtc::Candidate candidate(sdp, sdpMid);
    pc->addRemoteCandidate(candidate);
}

std::vector<std::byte> stored_codec_config_data;
void WebRTCStreamer::sendCodecConfigData(const uint8_t* data, int size) {
    if (!data || size <= 0) {
        LOGE("Invalid codec config data received in sendCodecConfigData.");
        return;
    }
    LOGI("Storing codec config data (SPS/PPS), size: %d", size);
    stored_codec_config_data.assign(reinterpret_cast<const std::byte*>(data),
                                    reinterpret_cast<const std::byte*>(data) + size);
}

void WebRTCStreamer::sendEncodedFrame(const char* data, int size, bool isKeyFrame, int64_t pts) {
    if (size <= 0) { // Changed from size == 0 to size <= 0
        LOGE("Encoded frame data size is invalid (%d), not queuing.", size);
        return;
    }

    if (!m_isStreamingActive) {
        return;
    }


    rtc::binary sample_to_queue;
    const std::byte* frame_byte_data = reinterpret_cast<const std::byte*>(data);

    if (isKeyFrame && !stored_codec_config_data.empty()) {
        sample_to_queue.reserve(stored_codec_config_data.size() + size);
        sample_to_queue.insert(sample_to_queue.end(), stored_codec_config_data.begin(), stored_codec_config_data.end());
        sample_to_queue.insert(sample_to_queue.end(), frame_byte_data, frame_byte_data + size);
    } else {
        sample_to_queue.assign(frame_byte_data, frame_byte_data + size);
    }

    if (sample_to_queue.empty()) {
        LOGE("Sample to queue is empty after processing. Original frame size: %d, isKeyFrame: %d, PTS: %lld", size, isKeyFrame, pts);
        return;
    }

    int64_t rtpTimestamp = (pts * 90000LL) / 1000000LL;

    rtc::FrameInfo frameInfo(rtpTimestamp);
    frameInfo.payloadType = 96;

    QueuedFrame qFrame;
    qFrame.data = std::move(sample_to_queue);
    qFrame.frameInfo = frameInfo;
    qFrame.isKeyFrame_log = isKeyFrame;
    qFrame.original_size_log = size;
    qFrame.pts_log = pts;

    {
        std::lock_guard<std::mutex> lock(m_queueMutex);
        if (m_frameQueue.size() < MAX_FRAME_QUEUE_SIZE) {
            m_frameQueue.push(std::move(qFrame));
        } else {
            LOGW("Frame queue is full (size: %zu / %d). Dropping current frame. PTS: %lld, isKeyFrame: %d",
                 m_frameQueue.size(), MAX_FRAME_QUEUE_SIZE, pts, isKeyFrame);
            return;
        }
    }
    m_queueCondVar.notify_one();
}

void WebRTCStreamer::newConnection(const std::string &clientId) {
    if (clients.find(clientId) != clients.end()) {
        LOGE("Client with ID %s already exists.", clientId.c_str());
        return;
    }

    std::shared_ptr<ClientContext> client = std::make_shared<ClientContext>(clientId);
    clients[clientId] = client;
    initConnection(client);
}


extern "C" {

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeInit(JNIEnv *env, jclass clazz, jobject bridgeInstance) {
    LOGI("nativeInit called");
    if (!g_jniBridgeInstance) {
        g_jniBridgeInstance = env->NewGlobalRef(bridgeInstance);
        if (!g_jniBridgeInstance) {
            LOGE("Failed to create global reference for JniBridge instance");
            return;
        }
    }
    if (!g_webRTCStreamer) {
        g_webRTCStreamer = std::make_shared<WebRTCStreamer>();
    }
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeDestroy(JNIEnv *env, jclass clazz) {
    LOGI("nativeDestroy called");
    if (g_jniBridgeInstance) {
        env->DeleteGlobalRef(g_jniBridgeInstance);
        g_jniBridgeInstance = nullptr;
    }

    if (g_webRTCStreamer) {
        g_webRTCStreamer->stopStreaming();
        g_webRTCStreamer.reset();
    }
}


JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeStartStreaming(JNIEnv *env, jclass clazz) {
    LOGI("nativeStartStreaming called");
    if (g_webRTCStreamer) {
        g_webRTCStreamer->startStreaming();
    } else {
        LOGE("WebRTCStreamer not initialized in nativeStartStreaming");
    }
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeStopStreaming(JNIEnv *env, jclass clazz) {
    LOGI("nativeStopStreaming called");
    if (g_webRTCStreamer) {
        g_webRTCStreamer->stopStreaming();
        // Consider when to release g_webRTCStreamer, e.g., on app exit or specific JNI call
    } else {
        LOGE("WebRTCStreamer not initialized in nativeStopStreaming");
    }
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeNewConnection(JNIEnv *env, jclass clazz, jstring clientId) {
    LOGI("nativeNewConnection called");
    if (g_webRTCStreamer) {
        g_webRTCStreamer->newConnection(jstringToString(env, clientId));
    } else {
        LOGE("WebRTCStreamer not initialized in nativeStopStreaming");
    }
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeOnAnswerReceived(JNIEnv *env, jclass clazz, jstring clientId, jstring sdp) {
    LOGI("nativeOnAnswerReceived called");
    if (g_webRTCStreamer) {
        g_webRTCStreamer->handleAnswer(jstringToString(env, clientId), jstringToString(env, sdp));
    } else {
        LOGE("WebRTCStreamer not initialized in nativeOnAnswerReceived");
    }
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeOnIceCandidateReceived(JNIEnv *env, jclass clazz, jstring clientId, jstring sdpMid, jint sdpMLineIndex, jstring sdp) {
    LOGI("nativeOnIceCandidateReceived called");
    if (g_webRTCStreamer) {
        g_webRTCStreamer->handleIceCandidate(jstringToString(env, clientId), jstringToString(env, sdpMid), sdpMLineIndex, jstringToString(env, sdp));
    } else {
        LOGE("WebRTCStreamer not initialized in nativeOnIceCandidateReceived");
    }
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeSendCodecConfigData(
        JNIEnv *env, jclass clazz, jbyteArray dataArray, jint size) {
    if (!g_webRTCStreamer) {
        LOGE("WebRTCStreamer not initialized in nativeSendCodecConfigData");
        return;
    }
    jbyte* data = env->GetByteArrayElements(dataArray, nullptr);
    if (data == nullptr) {
        LOGE("Failed to get byte array elements from dataArray in nativeSendCodecConfigData");
        return;
    }
    g_webRTCStreamer->sendCodecConfigData(reinterpret_cast<const uint8_t*>(data), size);
    env->ReleaseByteArrayElements(dataArray, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeSendEncodedFrame(
        JNIEnv *env, jclass clazz, jbyteArray dataArray, jint size, jboolean isKeyFrame, jlong presentationTimeUs) {
    if (!g_webRTCStreamer) {
        return;
    }
    jbyte* data = env->GetByteArrayElements(dataArray, nullptr);
    if (data == nullptr) {
        return;
    }
    g_webRTCStreamer->sendEncodedFrame(reinterpret_cast<const char*>(data), size, isKeyFrame, presentationTimeUs);
    env->ReleaseByteArrayElements(dataArray, data, JNI_ABORT);
}


} // extern "C"
