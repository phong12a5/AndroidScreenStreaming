const connectButton = document.getElementById('connectButton');
const remoteVideo = document.getElementById('remoteVideo');
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
let localStream; // Not used for screen receiving, but good to have for future

const pcConfig = {
    iceServers: [{ urls: STUN_SERVER }]
};

connectButton.onclick = () => {
    if (!ws || ws.readyState === WebSocket.CLOSED) {
        connectWebSocket();
    } else if (ws.readyState === WebSocket.OPEN) {
        if (!pc) {
            statusDisplay.textContent = 'WebSocket connected. Initializing WebRTC and sending offer...';
            createPeerConnectionAndOffer(); // Changed: Now web creates offer
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
        statusDisplay.textContent = 'Status: Connected. Creating WebRTC connection and sending offer...';
        // When WS opens, create PC and send offer
        createPeerConnectionAndOffer();
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
        remoteVideo.srcObject = null;
    };
}

// New function to create PC and offer
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
            }
        };

        // Setup remote track handler
        pc.ontrack = (event) => {
            log('Remote track received!', event.streams[0]);
            if (remoteVideo.srcObject !== event.streams[0]) {
                remoteVideo.srcObject = event.streams[0];
                statusDisplay.textContent = 'Status: Streaming!';
                log('Remote stream added to video element.');
            }
        };
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
            log("Message from DataChannel:", event.data);
            // Handle messages from Android if any (Android is primarily sending frames)
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

// Optional: Auto-connect on load, or require button press
// connectWebSocket(); // Uncomment to auto-connect
// For this setup, we wait for the Android app (offeror) to start,
// so the web client (answerer) should connect its WebSocket and be ready.
// The "Connect" button will primarily ensure WebSocket is up and PC is ready.
