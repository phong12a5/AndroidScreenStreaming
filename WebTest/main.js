const remoteVideo = document.getElementById('remoteVideo');
const startButton = document.getElementById('startButton');
const stopButton = document.getElementById('stopButton');
const statusDiv = document.getElementById('status');

let pc; // RTCPeerConnection
let signalingSocket; // WebSocket cho signaling

const SIGNALING_SERVER_URL = 'ws://localhost:8080/ws'; // THAY ĐỔI URL NÀY!

// Cấu hình STUN server (cần thiết cho NAT traversal)
const iceConfiguration = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        // Thêm TURN server nếu cần cho các mạng phức tạp hơn
        // {
        //   urls: 'turn:your.turn.server:3478',
        //   username: 'user',
        //   credential: 'password'
        // }
    ]
};

function updateStatus(message) {
    console.log(message);
    statusDiv.textContent = `Status: ${message}`;
}

function startSession() {
     if (pc) {
        updateStatus("Session already active. Please disconnect first.");
        return;
    }
    updateStatus("Connecting to signaling server...");
    startButton.disabled = true;

    signalingSocket = new WebSocket(SIGNALING_SERVER_URL);

    signalingSocket.onopen = async () => { // async to use await
        updateStatus("Connected to signaling server. Sending request...");
        createPeerConnection(); // Create PC instance
        try {
            signalingSocket.send(JSON.stringify({ type: 'request' }));
            updateStatus("Request sent. Waiting for offer...");
        } catch (error) {
            handleError("Error sending request: " + error);
        }
    };

    signalingSocket.onmessage = async (event) => {
        const message = JSON.parse(event.data);
        console.log("Received from signaling:", message);

        if (message.type === 'offer') { // Handle offer from the server (Android)
            if (!pc) {
                // This should ideally not happen if createPeerConnection is called in onopen
                console.warn("PeerConnection not created when offer received. Creating now.");
                createPeerConnection();
            }
            updateStatus("Offer received. Setting remote description and creating answer...");
            try {
                await pc.setRemoteDescription(new RTCSessionDescription(message));
                const answer = await pc.createAnswer();
                await pc.setLocalDescription(answer);
                updateStatus("Answer created. Sending answer...");
                signalingSocket.send(JSON.stringify(pc.localDescription)); // Send the answer
            } catch (error) {
                handleError("Error processing offer or creating/sending answer: " + error);
            }
        } else if (message.type === 'answer') { // This case is less likely in the new flow
            if (pc && pc.signalingState === 'have-local-offer') {
                updateStatus("Answer received. Setting remote description...");
                try {
                    await pc.setRemoteDescription(new RTCSessionDescription(message));
                } catch (error) {
                    handleError("Error setting remote description (answer): " + error);
                }
            } else {
                console.warn("Received answer, but not expecting one (e.g., not in 'have-local-offer' state). Current signaling state:", pc ? pc.signalingState : "pc is null");
            }
        } else if (message.type === 'candidate') {
            if (pc && pc.remoteDescription) { // Chỉ add candidate sau khi đã set remote description
                try {
                    const candidate = new RTCIceCandidate(message.candidate);
                    await pc.addIceCandidate(candidate);
                    updateStatus("ICE candidate added.");
                } catch (error) {
                    handleError("Error adding ICE candidate: " + error);
                }
            } else {
                console.warn("PeerConnection not ready for candidate or no remote description.");
                // Cân nhắc việc xếp hàng candidate nếu chúng đến quá sớm
            }
        } else if (message.type === 'disconnect' || message.type === 'bye') {
            updateStatus("Remote peer disconnected.");
            stopSession();
        } else {
            console.warn("Unknown message type from signaling:", message.type);
        }
    };

    signalingSocket.onerror = (error) => {
        handleError("Signaling WebSocket error: " + error);
        startButton.disabled = false;
    };

    signalingSocket.onclose = () => {
        updateStatus("Disconnected from signaling server.");
        if (pc) {
            stopSession(); // Dọn dẹp nếu socket đóng đột ngột
        }
        startButton.disabled = false;
        stopButton.classList.add('hidden');
    };
}

function createPeerConnection() {
    updateStatus("Creating PeerConnection...");
    pc = new RTCPeerConnection(iceConfiguration);

    pc.onicecandidate = (event) => {
        if (event.candidate) {
            updateStatus("Generated ICE candidate. Sending...");
            // Gửi candidate đến peer kia thông qua signaling server
            // Message cần có cấu trúc mà Android app (libdatachannel) hiểu được
            // Ví dụ: { type: 'candidate', candidate: event.candidate.toJSON() }
            // libdatachannel có thể mong đợi mid, sdpMLineIndex, sdp
            const candidateMessage = {
                type: 'candidate',
                candidate: {
                    sdpMid: event.candidate.sdpMid,
                    sdpMLineIndex: event.candidate.sdpMLineIndex,
                    candidate: event.candidate.candidate
                }
            };
            signalingSocket.send(JSON.stringify(candidateMessage));
        }
    };

    pc.oniceconnectionstatechange = () => {
        updateStatus(`ICE connection state: ${pc.iceConnectionState}`);
        if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
            stopButton.classList.remove('hidden');
            startButton.disabled = true;
        } else if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'closed') {
            stopSession();
        }
    };

    pc.ontrack = (event) => {
        updateStatus("Video track received!");
        if (event.streams && event.streams[0]) {
            remoteVideo.srcObject = event.streams[0];
        } else {
            // Fallback cho trình duyệt cũ hơn
            let inboundStream = new MediaStream();
            inboundStream.addTrack(event.track);
            remoteVideo.srcObject = inboundStream;
        }
        remoteVideo.play().catch(e => console.error("Autoplay failed:", e));
    };

    // Thêm transceivers nếu bạn là người tạo offer (trong trường hợp này, web là người nhận offer)
    // Nếu web là người tạo offer (ít phổ biến hơn trong kịch bản này):
    // pc.addTransceiver('video', { direction: 'recvonly' });
    // pc.addTransceiver('audio', { direction: 'recvonly' }); // Nếu có audio
}

function stopSession() {
    updateStatus("Disconnecting...");
    if (pc) {
        pc.close();
        pc = null;
    }
    if (signalingSocket && signalingSocket.readyState === WebSocket.OPEN) {
        // Gửi message "bye" đến peer kia (tùy chọn, để dọn dẹp phía bên kia)
        signalingSocket.send(JSON.stringify({ type: 'bye' }));
        signalingSocket.close();
    }
    signalingSocket = null;
    remoteVideo.srcObject = null;
    startButton.disabled = false;
    stopButton.classList.add('hidden');
    updateStatus("Disconnected.");
}

function handleError(errorMessage) {
    console.error(errorMessage);
    updateStatus(`Error: ${errorMessage}`);
    // Có thể thêm logic reset hoặc thông báo cho người dùng
    if (pc) stopSession(); // Dừng phiên nếu có lỗi nghiêm trọng
    startButton.disabled = false;
}

// Khởi tạo
updateStatus("Ready. Click 'Connect to Stream' to start.");