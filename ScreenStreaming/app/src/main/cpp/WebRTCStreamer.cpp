#include "WebRTCStreamer.h"
#include <rtc/rtc.hpp> 
#include <iostream>
#include <jni.h> // Required for JNI calls
#include <android/log.h> // Required for Android logging

// Define log tag for Android logging
#define LOG_TAG "WebRTCStreamer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global WebRTCStreamer instance and JNIEnv pointer
static std::shared_ptr<WebRTCStreamer> g_webRTCStreamer;
static JavaVM* g_javaVM = nullptr;
static jobject g_jniBridgeInstance = nullptr; // Global reference to JniBridge instance

// Helper function to get JNIEnv for the current thread
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
    // Initialization, if any, can go here or in init()
}

WebRTCStreamer::~WebRTCStreamer() {
    LOGI("WebRTCStreamer destructor");
    stopStreaming();
}

void WebRTCStreamer::init() {
    LOGI("WebRTCStreamer::init");
    rtc::Configuration config;
    // Example: config.iceServers.emplace_back("stun:stun.l.google.com:19302");
    // Add your STUN/TURN servers here if needed for NAT traversal
    config.iceServers.emplace_back("stun:stun.l.google.com:19302");


    pc = std::make_shared<rtc::PeerConnection>(config);

    pc->onStateChange([](rtc::PeerConnection::State state) {
//        LOGI("PeerConnection State: %s", rtc::toString(state).c_str());
    });

    pc->onGatheringStateChange([](rtc::PeerConnection::GatheringState state) {
//        LOGI("PeerConnection Gathering State: %s", rtc::toString(state).c_str());
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
        // sdpMLineIndex is part of the candidate string usually, or can be passed if available directly
        // For libdatachannel, the candidate string itself is usually sufficient.
        // If sdpMLineIndex is explicitly needed and not easily parsed, this might need adjustment.
        // Here, we'll pass a placeholder 0 for sdpMLineIndex if not directly available.
        // The structure of rtc::Candidate might provide this, check libdatachannel docs.
        // For simplicity, let's assume it's not directly used or embedded in a way that needs separate extraction here.
        env->CallStaticVoidMethod(cls, mid, midStr, 0 /* sdpMLineIndex placeholder */, candidateStr);
        env->DeleteLocalRef(cls);
        env->DeleteLocalRef(midStr);
        env->DeleteLocalRef(candidateStr);
    });

    dc = pc->createDataChannel("screenStream");
    LOGI("DataChannel 'screenStream' created");

    dc->onOpen([this]() {
        LOGI("DataChannel opened");
        JNIEnv* env = getJNIEnv();
        // Optionally notify Java that DataChannel is open
    });

    dc->onClosed([this]() {
        LOGI("DataChannel closed");
        // Optionally notify Java
    });

    dc->onMessage([this](auto data) { 
        if (std::holds_alternative<std::string>(data)) {
            std::string message = std::get<std::string>(data);
            LOGI("DataChannel message received: %s", message.c_str());
            onDataChannelMessage(message); // Forward to existing handler
            JNIEnv* env = getJNIEnv();
            if (!env || !g_jniBridgeInstance) {
                 LOGE("JNIEnv or JniBridge instance is null in onMessage");
                return;
            }
            jclass cls = env->GetObjectClass(g_jniBridgeInstance);
            jmethodID mid = env->GetStaticMethodID(cls, "onDataChannelMessage", "(Ljava/lang/String;)V");
            if (mid == nullptr) {
                LOGE("Failed to find onDataChannelMessage method");
                env->DeleteLocalRef(cls);
                return;
            }
            jstring messageStr = stringToJstring(env, message);
            env->CallStaticVoidMethod(cls, mid, messageStr);
            env->DeleteLocalRef(cls);
            env->DeleteLocalRef(messageStr);
        } else {
            LOGI("DataChannel binary message received (not handled)");
        }
    });
}

void WebRTCStreamer::startStreaming() {
    LOGI("WebRTCStreamer::startStreaming");
    if (!pc) {
        LOGI("PeerConnection not initialized, calling init()");
        init(); 
    }
    LOGI("Setting local description (creating offer)");
    pc->setLocalDescription(); 
}

void WebRTCStreamer::stopStreaming() {
    LOGI("WebRTCStreamer::stopStreaming");
    if (dc && dc->isOpen()) {
        LOGI("Closing DataChannel");
        dc->close();
    }
    if (pc && pc->state() != rtc::PeerConnection::State::Closed) {
        LOGI("Closing PeerConnection");
        pc->close();
    }
    // Release global reference if it was created for a specific instance
    if (g_jniBridgeInstance) {
        JNIEnv* env = getJNIEnv();
        if (env) {
            env->DeleteGlobalRef(g_jniBridgeInstance);
            g_jniBridgeInstance = nullptr;
        }
    }
}

void WebRTCStreamer::onDataChannelMessage(std::string message) {
    // This is an internal C++ handler, already logged in dc->onMessage
    // It can be used for C++ specific logic before or after notifying Java
}

void WebRTCStreamer::sendDataChannelMessage(std::string message) {
    LOGI("WebRTCStreamer::sendDataChannelMessage: %s", message.c_str());
    if (dc && dc->isOpen()) {
        dc->send(message);
        LOGI("Message sent via DataChannel");
    } else {
        LOGE("DataChannel is not open. Cannot send message.");
    }
}

void WebRTCStreamer::handleOffer(const std::string& sdp) {
    LOGI("WebRTCStreamer::handleOffer");
    if (!pc) {
        init();
    }
    rtc::Description offer(sdp, "offer");
    pc->setRemoteDescription(offer);
    pc->setLocalDescription(); // Create answer
}

void WebRTCStreamer::handleAnswer(const std::string& sdp) {
    LOGI("WebRTCStreamer::handleAnswer");
    if (!pc) {
        LOGE("PeerConnection not initialized in handleAnswer");
        return;
    }
    rtc::Description answer(sdp, "answer");
    pc->setRemoteDescription(answer);
}

void WebRTCStreamer::handleIceCandidate(const std::string& sdpMid, int sdpMLineIndex, const std::string& sdp) {
    LOGI("WebRTCStreamer::handleIceCandidate");
    if (!pc) {
        LOGE("PeerConnection not initialized in handleIceCandidate");
        return;
    }
    rtc::Candidate candidate(sdp, sdpMid);
    // candidate.setSdpMLineIndex(sdpMLineIndex); // If libdatachannel supports this setter
    pc->addRemoteCandidate(candidate);
}


// JNI Bridge Implementations
extern "C" {

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeInit(JNIEnv *env, jclass clazz, jobject bridgeInstance) {
    LOGI("nativeInit called");
    if (!g_jniBridgeInstance) { // Make bridgeInstance global if not already
        g_jniBridgeInstance = env->NewGlobalRef(bridgeInstance);
        if (!g_jniBridgeInstance) {
            LOGE("Failed to create global reference for JniBridge instance");
            return;
        }
    }
    if (!g_webRTCStreamer) {
        g_webRTCStreamer = std::make_shared<WebRTCStreamer>();
    }
    g_webRTCStreamer->init();
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
Java_io_bomtech_screenstreaming_JniBridge_nativeOnOfferReceived(JNIEnv *env, jclass clazz, jstring sdp) {
    LOGI("nativeOnOfferReceived called");
    if (g_webRTCStreamer) {
        g_webRTCStreamer->handleOffer(jstringToString(env, sdp));
    } else {
        LOGE("WebRTCStreamer not initialized in nativeOnOfferReceived");
    }
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeOnAnswerReceived(JNIEnv *env, jclass clazz, jstring sdp) {
    LOGI("nativeOnAnswerReceived called");
    if (g_webRTCStreamer) {
        g_webRTCStreamer->handleAnswer(jstringToString(env, sdp));
    } else {
        LOGE("WebRTCStreamer not initialized in nativeOnAnswerReceived");
    }
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeOnIceCandidateReceived(JNIEnv *env, jclass clazz, jstring sdpMid, jint sdpMLineIndex, jstring sdp) {
    LOGI("nativeOnIceCandidateReceived called");
    if (g_webRTCStreamer) {
        g_webRTCStreamer->handleIceCandidate(jstringToString(env, sdpMid), sdpMLineIndex, jstringToString(env, sdp));
    } else {
        LOGE("WebRTCStreamer not initialized in nativeOnIceCandidateReceived");
    }
}

JNIEXPORT void JNICALL
Java_io_bomtech_screenstreaming_JniBridge_nativeSendData(JNIEnv *env, jclass clazz, jstring message) {
    LOGI("nativeSendData called");
    if (g_webRTCStreamer) {
        g_webRTCStreamer->sendDataChannelMessage(jstringToString(env, message));
    } else {
        LOGE("WebRTCStreamer not initialized in nativeSendData");
    }
}

} // extern "C"
