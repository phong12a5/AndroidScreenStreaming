const connectButton = document.getElementById('connectButton');
// const remoteVideo = document.getElementById('remoteVideo'); // REMOVED: Using canvas now
const remoteCanvas = document.getElementById('remoteCanvas'); // NEW: Canvas element
const statusDisplay = document.getElementById('status');

// --- Configuration ---
// IMPORTANT: Replace with the actual IP/hostname of your signaling server if not running locally
// or if your Android device/emulator cannot reach localhost directly.
// If Android device and PC are on the same Wi-Fi: use PC\'s LAN IP.
// If Android emulator on same PC: \'ws://10.0.2.2:8080\' might not work from browser to PC,
// use \'ws://localhost:8080\' or PC\'s LAN IP.
const SIGNALING_SERVER_URL = 'ws://localhost:8080'; // Adjust if necessary
const STUN_SERVER = 'stun:stun.l.google.com:19302';

let ws;
let pc;
let dataChannel; // Added for creating DataChannel
// let localStream; // Not used for screen receiving, but good to have for future

// NEW: WebCodecs variables
let videoDecoder;
let canvasCtx;
let pendingCodecConfig = null; // To store SPS/PPS until the first key frame
let lastPts = 0; // For generating timestamps if not provided by sender

// Message type prefixes (assuming Android/C++ will send these)
const MSG_TYPE_CODEC_CONFIG = 0x01;
const MSG_TYPE_VIDEO_FRAME = 0x02;

const pcConfig = {
    iceServers: [{ urls: STUN_SERVER }]
};

connectButton.onclick = () => {
    if (!ws || ws.readyState === WebSocket.CLOSED) {
        connectWebSocket();
    } else if (ws.readyState === WebSocket.OPEN) {
        if (!pc) {
            statusDisplay.textContent = 'WebSocket connected. Initializing WebCodecs, WebRTC, and sending offer...'; // Updated
            initializeWebCodecsAndCreateOffer(); // NEW: Wrapper
        } else {
            statusDisplay.textContent = "Already connected or connecting.";
        }
    }
};

function log(message) {
    console.log(message);
    // statusDisplay.textContent = message; // Can be too verbose
}

function logError(message, error) {
    console.error(message, error);
    // statusDisplay.textContent = message; // Can be too verbose
}

function connectWebSocket() {
    log('Connecting to signaling server...');
    statusDisplay.textContent = 'Status: Connecting to signaling server...';
    ws = new WebSocket(SIGNALING_SERVER_URL);

    ws.onopen = () => {
        log('Connected to signaling server.');
        statusDisplay.textContent = 'Status: Connected. Initializing WebCodecs, WebRTC, and sending offer...'; // Updated
        initializeWebCodecsAndCreateOffer(); // NEW: Wrapper
    };

    ws.onmessage = async (event) => {
        const message = JSON.parse(event.data);
        log('Received message:', message);

        // Removed: if (!pc && (message.type === 'offer' || message.type === 'candidate'))
        // Because PC should be created before any message is processed if web is offerer

        if (message.type === 'answer') { // Changed: Handle answer
            if (!pc) {
                logError("PC not ready for answer.");
                return;
            }
            log('Received answer, setting remote description...');
            statusDisplay.textContent = 'Status: Answer received. Connection establishing...';
            try {
                await pc.setRemoteDescription(new RTCSessionDescription(message));
                log('Remote description (answer) set.');
            } catch (error) {
                logError('Error handling answer:', error);
            }
        } else if (message.type === 'offer') { // Changed: This is now unexpected
            log('Received offer (unexpected for offerer role):', message);
            statusDisplay.textContent = 'Status: Received an offer, but this client should send offers.';
        } else if (message.type === 'candidate') {
            try {
                if (message.candidate) {
                    log('Received ICE candidate, adding...');
                    await pc.addIceCandidate(new RTCIceCandidate(message.candidate));
                }
            } catch (error) {
                logError('Error adding received ICE candidate:', error);
            }
        } else {
            log('Unknown message type:', message.type);
        }
    };

    ws.onerror = (error) => {
        logError('WebSocket error:', error);
        statusDisplay.textContent = 'Status: WebSocket error. See console.';
    };

    ws.onclose = () => {
        log('Disconnected from signaling server.');
        statusDisplay.textContent = 'Status: Disconnected from Signaling Server.';
        if (pc) {
            pc.close();
            pc = null;
        }
        if (dataChannel) {
            dataChannel = null;
        }
        if (videoDecoder && videoDecoder.state !== 'closed') { // NEW: Close decoder
            videoDecoder.close();
            videoDecoder = null;
        }
        if (canvasCtx) { // NEW: Clear canvas
            canvasCtx.clearRect(0, 0, remoteCanvas.width, remoteCanvas.height);
        }
        pendingCodecConfig = null;
    };
}

// NEW: Function to initialize WebCodecs and then create PeerConnection + Offer
function initializeWebCodecsAndCreateOffer() {
    if (!remoteCanvas) {
        logError("Canvas element #remoteCanvas not found. Please add it to your HTML.");
        statusDisplay.textContent = "Error: Canvas for video not found.";
        return;
    }
    canvasCtx = remoteCanvas.getContext('2d');

    if (!window.VideoDecoder) {
        logError("WebCodecs API (VideoDecoder) is not supported by this browser.");
        statusDisplay.textContent = "Error: WebCodecs not supported.";
        return;
    }

    videoDecoder = new VideoDecoder({
        output: (videoFrame) => {
            // Ensure canvas is sized correctly for the video frame
            if (remoteCanvas.width !== videoFrame.codedWidth || remoteCanvas.height !== videoFrame.codedHeight) {
                remoteCanvas.width = videoFrame.codedWidth;
                remoteCanvas.height = videoFrame.codedHeight;
            }
            canvasCtx.drawImage(videoFrame, 0, 0);
            videoFrame.close();
        },
        error: (e) => {
            logError('VideoDecoder error:', e);
            statusDisplay.textContent = "Error: Video decoder error.";
        }
    });

    // Configure the decoder.
    // For H.264, the 'description' field (SPS/PPS in avcC format) can often be omitted
    // if the SPS/PPS NALUs are prepended to the first key frame(s).
    // The codec string 'avc1.42E01E' is a common one for H.264 Baseline Profile.
    // If issues persist, this string might need to match Android's MediaFormat more precisely.
    try {
        videoDecoder.configure({ codec: 'avc1.42E01E' }); // Example codec string
        log("VideoDecoder configured.");
    } catch (e) {
        logError("Failed to configure VideoDecoder:", e);
        statusDisplay.textContent = "Error: Failed to configure video decoder.";
        return;
    }
    
    // Now that WebCodecs is initialized (or at least the decoder object is created),
    // proceed with WebRTC setup.
    createPeerConnectionAndOffer();
}

async function createPeerConnectionAndOffer() {
    if (pc) {
        log("PeerConnection already exists.");
        return;
    }
    log('Creating PeerConnection...');
    try {
        pc = new RTCPeerConnection(pcConfig);

        // Setup ICE candidate handler
        pc.onicecandidate = (event) => {
            if (event.candidate) {
                log('Generated ICE candidate, sending...', event.candidate);
                ws.send(JSON.stringify({
                    type: 'candidate',
                    candidate: event.candidate
                }));
            }
        };

        // Setup ICE connection state change handler
        pc.oniceconnectionstatechange = () => {
            log(`ICE connection state: ${pc.iceConnectionState}`);
            statusDisplay.textContent = `Status: ICE ${pc.iceConnectionState}`;
            if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'closed') {
                // Handle connection failure
                if (videoDecoder && videoDecoder.state !== 'closed') { // NEW: Close decoder
                    videoDecoder.close();
                }
            }
        };

        // REMOVED: pc.ontrack handler, as video comes via DataChannel
        // pc.ontrack = (event) => {
        //     log('Remote track received!', event.streams[0]);
        //     if (remoteVideo.srcObject !== event.streams[0]) {
        //         remoteVideo.srcObject = event.streams[0];
        //         statusDisplay.textContent = 'Status: Streaming!';
        //         log('Remote stream added to video element.');
        //     }
        // };
        log('PeerConnection created.');

        // Create DataChannel - Web client (offerer) creates it
        log('Creating DataChannel...');
        dataChannel = pc.createDataChannel("screenStream"); // Same name Android expects
        dataChannel.onopen = () => {
            log("DataChannel 'screenStream' opened.");
            statusDisplay.textContent = 'Status: DataChannel open.';
            // dataChannel.send("Hello from Web Client!"); // Optional: test message
        };
        dataChannel.onclose = () => {
            log("DataChannel 'screenStream' closed.");
        };
        dataChannel.onerror = (error) => {
            logError("DataChannel error:", error);
        };
        dataChannel.onmessage = (event) => {
            log("Raw message from DataChannel:", event); // Can be very verbose
            if (event.data instanceof ArrayBuffer) {
                const dataView = new DataView(event.data);
                if (dataView.byteLength < 1) {
                    logError("Received empty ArrayBuffer from DataChannel.");
                    return;
                }
                const messageType = dataView.getUint8(0);
                const actualData = event.data.slice(1); // Get data without the prefix byte

                if (messageType === MSG_TYPE_CODEC_CONFIG) {
                    log(`Received Codec Config (SPS/PPS), length: ${actualData.byteLength}`);
                    pendingCodecConfig = actualData;
                    // NOTE: We are assuming SPS/PPS will be prepended to the first key frame.
                    // If VideoDecoder.configure needs an avcC box via 'description', this part would be more complex.
                } else if (messageType === MSG_TYPE_VIDEO_FRAME) {
                    if (dataView.byteLength < 2) {
                        logError("Video frame message too short.");
                        return;
                    }
                    const isKeyFrame = dataView.getUint8(1) === 1;
                    const frameDataNalu = event.data.slice(2); // Slice after type and isKeyFrame flag
                    // log(`Received Video Frame, length: ${frameDataNalu.byteLength}, isKey: ${isKeyFrame}`);

                    if (videoDecoder && videoDecoder.state === 'configured') {
                        let dataToSend = frameDataNalu;
                        if (isKeyFrame && pendingCodecConfig) {
                            log("Prepending stored Codec Config to Key Frame.");
                            dataToSend = concatenateArrayBuffers(pendingCodecConfig, frameDataNalu);
                            pendingCodecConfig = null; // Clear after use
                        }

                        // Timestamp: Android sends presentationTimeUs. This needs to be extracted from payload.
                        // For now, using a placeholder. This is a CRITICAL TODO.
                        // The C++ side needs to send the PTS (presentationTimeUs).
                        // Example if PTS (long/8bytes) was after isKeyFrame:
                        // const timestamp = dataView.getBigUint64(2, true); // true for little-endian
                        // const nalUnitData = event.data.slice(10); // 1 type + 1 key + 8 PTS
                        const currentTimestamp = Date.now() * 1000; // Placeholder in microseconds

                        try {
                            const chunk = new EncodedVideoChunk({
                                type: isKeyFrame ? 'key' : 'delta',
                                timestamp: currentTimestamp, // TODO: Use actual PTS from Android
                                data: dataToSend
                            });
                            videoDecoder.decode(chunk);
                        } catch (e) {
                            logError("Error decoding video chunk:", e);
                        }
                    } else {
                        // log("VideoDecoder not ready or not configured, frame dropped.");
                    }
                } else {
                    logError(`Unknown message type from DataChannel: ${messageType}`);
                }
            } else {
                log("Message from DataChannel (not ArrayBuffer):", event.data);
                // Handle other types of messages if necessary
            }
        };
        log("DataChannel 'screenStream' created by web client.");

        // Create and send offer
        log('Creating offer...');
        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);
        log('Offer created and local description set. Sending offer...');
        ws.send(JSON.stringify(offer));
        statusDisplay.textContent = 'Status: Offer sent. Waiting for answer...';

    } catch (error) {
        logError("Error creating PeerConnection or offer:", error);
        statusDisplay.textContent = 'Status: Error creating WebRTC connection. See console.';
    }
}

// NEW: Helper function to concatenate ArrayBuffers
function concatenateArrayBuffers(buffer1, buffer2) {
    const tmp = new Uint8Array(buffer1.byteLength + buffer2.byteLength);
    tmp.set(new Uint8Array(buffer1), 0);
    tmp.set(new Uint8Array(buffer2), buffer1.byteLength);
    return tmp.buffer;
}

// Optional: Auto-connect on load, or require button press
// connectWebSocket(); // Uncomment to auto-connect
// For this setup, we wait for the Android app (offeror) to start,
// so the web client (answerer) should connect its WebSocket and be ready.
// The "Connect" button will primarily ensure WebSocket is up and PC is ready.
